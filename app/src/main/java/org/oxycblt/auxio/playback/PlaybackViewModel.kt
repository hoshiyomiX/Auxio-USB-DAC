/*
 * Copyright (c) 2021 Auxio Project
 * PlaybackViewModel.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.list.ListSettings
import org.oxycblt.auxio.list.adapter.UpdateInstructions
import org.oxycblt.auxio.playback.state.DeferredPlayback
import org.oxycblt.auxio.playback.state.PlaybackCommand
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.state.ShuffleMode
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * An [ViewModel] that provides a safe UI frontend for the current playback state.
 *
 * @author Alexander Capehart (OxygenCobalt)
 *
 * TODO: Debug subtle backwards movement of position on pause
 */
@HiltViewModel
class PlaybackViewModel
@Inject
constructor(
    private val playbackManager: PlaybackStateManager,
    private val playbackSettings: PlaybackSettings,
    private val commandFactory: PlaybackCommand.Factory,
    private val listSettings: ListSettings,
    private val audioInfoProvider: AudioInfoProvider,
    private val usbDacConnectionMonitor: UsbDacConnectionMonitor,
) : ViewModel(), PlaybackStateManager.Listener, PlaybackSettings.Listener {
    private var lastPositionJob: Job? = null
    private var audioInfoJob: Job? = null

    /**
     * Coroutine job that reactively refreshes [_audioInfo] whenever [usbDacConnectionMonitor]'s
     * `usbDacConnected` StateFlow emits a new value (i.e. on USB DAC plug/unplug events).
     *
     * Without this collector, the overlay would lag up to 500ms behind plug/unplug events — the
     * polling loop in [startAudioInfoPolling] only re-reads
     * `usbDacConnectionMonitor.usbDacConnected.value` every 500ms. This collector triggers an
     * immediate [refreshAudioInfo] call on every StateFlow emission, so the overlay reflects the
     * new pipeline state ("Android mixer (no DAC)" → "Pending (sink starting)" → "Native FLAC
     * (libFLAC)") within milliseconds of the physical plug/unplug event.
     */
    private var usbDacConnectionJob: Job? = null

    private val _song = MutableStateFlow<Song?>(null)
    /** The currently playing song. */
    val song: StateFlow<Song?>
        get() = _song

    private val _parent = MutableStateFlow<MusicParent?>(null)
    /** The [MusicParent] currently being played. Null if playback is occurring from all songs. */
    val parent: StateFlow<MusicParent?> = _parent
    private val _isPlaying = MutableStateFlow(false)
    /** Whether playback is ongoing or paused. */
    val isPlaying: StateFlow<Boolean>
        get() = _isPlaying

    private val _positionDs = MutableStateFlow(0L)
    /** The current position, in deci-seconds (1/10th of a second). */
    val positionDs: StateFlow<Long>
        get() = _positionDs

    private val _repeatMode = MutableStateFlow(RepeatMode.NONE)
    /** The current [RepeatMode]. */
    val repeatMode: StateFlow<RepeatMode>
        get() = _repeatMode

    private val _isShuffled = MutableStateFlow(false)
    /** Whether the queue is shuffled or not. */
    val isShuffled: StateFlow<Boolean>
        get() = _isShuffled

    private val _currentBarAction = MutableStateFlow(playbackSettings.barAction)
    /** The current secondary action to show alongside the play button in the playback bar. */
    val currentBarAction: StateFlow<ActionMode>
        get() = _currentBarAction

    private val _openPanel = MutableEvent<OpenPanel>()
    /**
     * A [OpenPanel] command that is awaiting a view capable of responding to it. Null if none
     * currently.
     */
    val openPanel: Event<OpenPanel>
        get() = _openPanel

    private val _pagerQueue = MutableStateFlow(PagerQueue(listOf(), 0))
    /** The current queue in a special bundled format suitable for the cover ViewPager2. */
    val pagerQueue: StateFlow<PagerQueue> = _pagerQueue

    private val _pagerCommand = MutableEvent<PagerCommand>()
    /** Specialized ViewPager2-friendly queue commands */
    val pagerCommand: Event<PagerCommand>
        get() = _pagerCommand

    private val _playbackDecision = MutableEvent<PlaybackDecision>()
    /**
     * A [PlaybackDecision] command that is awaiting a view capable of responding to it. Null if
     * none currently.
     */
    val playbackDecision: Event<PlaybackDecision>
        get() = _playbackDecision

    /**
     * The current audio session ID of the internal player. Null if no audio player is available.
     */
    val currentAudioSessionId: Int?
        get() = playbackManager.currentAudioSessionId

    private val _audioInfo =
        MutableStateFlow(
            AudioInfo.from(
                runCatching { audioInfoProvider.snapshot() }.getOrNull(),
                playbackSettings.usbDacMode,
                usbDacConnectionMonitor.usbDacConnected.value,
                _song.value,
            )
        )
    /**
     * The current audio pipeline info for the album-art overlay. Updated every 500ms while a song
     * is loaded, regardless of play/pause state or USB DAC connection state.
     */
    val audioInfo: StateFlow<AudioInfo> = _audioInfo

    private val _overlayVisible = MutableStateFlow(playbackSettings.audioInfoOverlayVisible)
    /** Whether the audio info overlay on the album art is currently visible. */
    val overlayVisible: StateFlow<Boolean> = _overlayVisible

    private val _usbDacMode = MutableStateFlow(playbackSettings.usbDacMode)
    /**
     * Whether USB DAC bit-perfect mode is currently enabled. Drives the player toolbar toggle icon.
     */
    val usbDacMode: StateFlow<Boolean> = _usbDacMode

    /**
     * Whether a USB Audio Class DAC is currently physically connected to the device. Updated in
     * real time from system broadcasts by [UsbDacConnectionMonitor]. Drives the gray-out state of
     * the toolbar toggle (in PlaybackPanelFragment) and the audio settings preference (in
     * AudioPreferenceFragment). When false, the toggle is disabled and dimmed; when true, the
     * toggle is enabled and (if the user has not manually disabled it) reflects the current
     * [usbDacMode] preference.
     */
    val usbDacConnected: StateFlow<Boolean> = usbDacConnectionMonitor.usbDacConnected

    init {
        playbackManager.addListener(this)
        playbackSettings.registerListener(this)
        startAudioInfoPolling()
        startUsbDacConnectionCollection()
    }

    override fun onCleared() {
        playbackManager.removeListener(this)
        playbackSettings.unregisterListener(this)
        audioInfoJob?.cancel()
        usbDacConnectionJob?.cancel()
    }

    override fun onIndexMoved(index: Int) {
        L.d("Index moved, updating current song")
        _positionDs.value = playbackManager.progression.calculateElapsedPositionMs().msToDs()
        _song.value = playbackManager.currentSong

        _pagerCommand.put(PagerCommand(update = null, scroll = index))
        _pagerQueue.value = _pagerQueue.value.copy(index = index)
    }

    override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) {
        // Other types of queue changes preserve the current song.
        if (change.type == QueueChange.Type.SONG) {
            L.d("Queue changed, updating current song")
            _song.value = playbackManager.currentSong
        }

        _pagerCommand.put(
            PagerCommand(
                update = change.instructions,
                scroll = index.takeIf { change.type != QueueChange.Type.MAPPING },
            )
        )
        _pagerQueue.value = PagerQueue(queue = queue, index = index)
    }

    override fun onQueueReordered(queue: List<Song>, index: Int, isShuffled: Boolean) {
        L.d("Queue completely changed, updating current song")
        _isShuffled.value = isShuffled

        _pagerCommand.put(PagerCommand(update = UpdateInstructions.Replace(0), scroll = index))
        _pagerQueue.value = PagerQueue(queue = queue, index = index)
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) {
        L.d("New playback started, updating playback information")
        _song.value = playbackManager.currentSong
        _parent.value = parent
        _isShuffled.value = isShuffled

        _pagerCommand.put(PagerCommand(update = UpdateInstructions.Replace(0), scroll = index))
        _pagerQueue.value = PagerQueue(queue = queue, index = index)
    }

    override fun onProgressionChanged(progression: Progression) {
        L.d("Player state changed, starting new position polling")
        _isPlaying.value = progression.isPlaying
        // Still need to update the position now due to co-routine launch delays
        _positionDs.value = progression.calculateElapsedPositionMs().msToDs()
        // Replace the previous position co-routine with a new one that uses the new
        // state information.
        lastPositionJob?.cancel()
        lastPositionJob =
            viewModelScope.launch {
                while (true) {
                    _positionDs.value = progression.calculateElapsedPositionMs().msToDs()
                    // Wait a deci-second for the next position tick.
                    delay(100)
                }
            }
    }

    override fun onRepeatModeChanged(repeatMode: RepeatMode) {
        _repeatMode.value = repeatMode
    }

    override fun onBarActionChanged() {
        _currentBarAction.value = playbackSettings.barAction
    }

    override fun onAudioInfoOverlayChanged() {
        _overlayVisible.value = playbackSettings.audioInfoOverlayVisible
    }

    override fun onUsbDacModeChanged() {
        _usbDacMode.value = playbackSettings.usbDacMode
        // #3 fix: Trigger immediate overlay refresh when USB DAC mode changes.
        // Previously the overlay only updated on the next 500ms polling tick,
        // causing visible lag when toggling USB DAC on/off. This call forces
        // an immediate snapshot so the overlay reflects the new pipeline state
        // (e.g. "Bit-perfect" → "Off") without waiting for the next poll.
        viewModelScope.launch { refreshAudioInfo() }
    }

    /**
     * Toggle USB DAC bit-perfect mode at runtime. Persists to settings and dispatches to listeners.
     */
    fun toggleUsbDacMode() {
        L.d("Toggling USB DAC bit-perfect mode")
        val newValue = !_usbDacMode.value
        playbackSettings.usbDacMode = newValue
        // _usbDacMode is updated by onUsbDacModeChanged() which fires from the settings dispatch.
    }

    /**
     * Start a polling coroutine that refreshes [audioInfo] every 500ms while the ViewModel is
     * alive. The polling runs regardless of play/pause state so that the overlay continues to show
     * the last-known pipeline state even when USB DAC is unplugged mid-playback (per user spec:
     * overlay persists visible).
     *
     * The snapshot call is dispatched to [Dispatchers.IO] because the underlying
     * [com.decent.usbaudio.media3.UsbAudioSink.snapshotAudioInfo] is `@Synchronized` — if the audio
     * renderer thread is mid-`configure()` when the polling tick fires, the main thread would block
     * until the lock is released, causing visible jank during fragment transitions (e.g. Settings →
     * Home back-navigation). Moving the snapshot off the main thread eliminates this contention
     * source. The [StateFlow] update itself is thread-safe.
     *
     * Note: this loop is the fallback for non-event-driven changes (e.g. native engine startup
     * window, FFmpeg buffer fill progress). For plug/unplug events, see
     * [startUsbDacConnectionCollection] which triggers an immediate refresh.
     */
    private fun startAudioInfoPolling() {
        audioInfoJob?.cancel()
        audioInfoJob =
            viewModelScope.launch {
                while (isActive) {
                    refreshAudioInfo()
                    delay(AUDIO_INFO_POLL_MS)
                }
            }
    }

    /**
     * Start a coroutine that reactively collects [UsbDacConnectionMonitor.usbDacConnected] and
     * triggers an immediate [refreshAudioInfo] on every emission. This eliminates the up-to-500ms
     * lag between a physical plug/unplug event and the overlay reflecting the new pipeline state.
     *
     * Why this is needed in addition to [startAudioInfoPolling]:
     * - The polling loop reads `usbDacConnectionMonitor.usbDacConnected.value` (snapshot) every
     *   500ms — so on a plug/unplug event, the overlay can be stale by up to 500ms.
     * - The user perceives plug/unplug as an instantaneous event (physical action) and expects the
     *   overlay to reflect the new state immediately, not "a moment later".
     * - The `usbDacConnected` StateFlow itself updates immediately (driven by BroadcastReceiver in
     *   [UsbDacConnectionMonitor]) — toolbar gray-out already benefits from this reactivity. This
     *   collector extends the same reactivity to the [audioInfo] overlay.
     *
     * The collector emits the current value immediately on subscription (StateFlow behavior), so
     * the overlay is refreshed once at startup. Subsequent emissions trigger refreshes on
     * plug/unplug events only.
     */
    private fun startUsbDacConnectionCollection() {
        usbDacConnectionJob?.cancel()
        usbDacConnectionJob =
            viewModelScope.launch {
                usbDacConnectionMonitor.usbDacConnected.collect { _ ->
                    // DAC plug/unplug event — refresh overlay immediately instead of waiting
                    // for the next polling tick (up to 500ms lag).
                    refreshAudioInfo()
                }
            }
    }

    /**
     * Take a fresh snapshot from [audioInfoProvider] and update [_audioInfo]. Shared by both the
     * 500ms polling loop ([startAudioInfoPolling]) and the reactive plug/unplug collector
     * ([startUsbDacConnectionCollection]). Thread-safe: the snapshot is dispatched to
     * [Dispatchers.IO] (because the underlying call is `@Synchronized` and may block), and the
     * [_audioInfo] StateFlow update is atomic.
     */
    private suspend fun refreshAudioInfo() {
        val snapshot =
            withContext(Dispatchers.IO) { runCatching { audioInfoProvider.snapshot() }.getOrNull() }
        _audioInfo.value =
            AudioInfo.from(
                snapshot,
                playbackSettings.usbDacMode,
                usbDacConnectionMonitor.usbDacConnected.value,
                _song.value,
            )
    }

    /** Toggle the visibility of the audio info overlay on the album art. Persisted to settings. */
    fun toggleAudioInfoOverlay() {
        L.d("Toggling audio info overlay visibility")
        val newValue = !_overlayVisible.value
        playbackSettings.audioInfoOverlayVisible = newValue
        _overlayVisible.value = newValue
    }

    // --- PLAYING FUNCTIONS ---

    fun play(song: Song, with: PlaySong) {
        L.d("Playing $song with $with")
        playWithImpl(song, with, ShuffleMode.IMPLICIT)
    }

    fun playExplicit(song: Song, with: PlaySong) {
        playWithImpl(song, with, ShuffleMode.OFF)
    }

    fun shuffleExplicit(song: Song, with: PlaySong) {
        playWithImpl(song, with, ShuffleMode.ON)
    }

    /** Shuffle all songs in the music library. */
    fun shuffleAll() {
        L.d("Shuffling all songs")
        playFromAllImpl(null, ShuffleMode.ON)
    }

    /**
     * Play a [Song] from one of it's [Artist]s.
     *
     * @param song The [Song] to play.
     * @param artist The [Artist] to play from. Must be linked to the [Song]. If null, the user will
     *   be prompted on what artist to play. Defaults to null.
     */
    fun playFromArtist(song: Song, artist: Artist? = null) {
        playFromArtistImpl(song, artist, ShuffleMode.IMPLICIT)
    }

    /**
     * Play a [Song] from one of it's [Genre]s.
     *
     * @param song The [Song] to play.
     * @param genre The [Genre] to play from. Must be linked to the [Song]. If null, the user will
     *   be prompted on what artist to play. Defaults to null.
     */
    fun playFromGenre(song: Song, genre: Genre? = null) {
        playFromGenreImpl(song, genre, ShuffleMode.IMPLICIT)
    }

    private fun playWithImpl(song: Song, with: PlaySong, shuffle: ShuffleMode) {
        when (with) {
            is PlaySong.FromAll -> playFromAllImpl(song, shuffle)
            is PlaySong.FromAlbum -> playFromAlbumImpl(song, shuffle)
            is PlaySong.FromArtist -> playFromArtistImpl(song, with.which, shuffle)
            is PlaySong.FromGenre -> playFromGenreImpl(song, with.which, shuffle)
            is PlaySong.FromPlaylist -> playFromPlaylistImpl(song, with.which, shuffle)
            is PlaySong.ByItself -> playItselfImpl(song, shuffle)
        }
    }

    private fun playItselfImpl(song: Song, shuffle: ShuffleMode) {
        playbackManager.play(
            requireNotNull(commandFactory.song(song, shuffle)) {
                "Invalid playback parameters [$song $shuffle]"
            }
        )
    }

    private fun playFromAllImpl(song: Song?, shuffle: ShuffleMode) {
        val params =
            if (song != null) {
                commandFactory.songFromAll(song, shuffle)
            } else {
                commandFactory.all(shuffle)
            }

        playImpl(params)
    }

    private fun playFromAlbumImpl(song: Song, shuffle: ShuffleMode) {
        L.d("Playing $song from album")
        playImpl(commandFactory.songFromAlbum(song, shuffle))
    }

    private fun playFromArtistImpl(song: Song, artist: Artist?, shuffle: ShuffleMode) {
        val params = commandFactory.songFromArtist(song, artist, shuffle)
        if (params != null) {
            playbackManager.play(params)
            return
        }
        L.d(
            "Cannot use given artist parameter for $song [$artist from ${song.artists}], showing choice dialog"
        )
        startPlaybackDecision(PlaybackDecision.PlayFromArtist(song))
    }

    private fun playFromGenreImpl(song: Song, genre: Genre?, shuffle: ShuffleMode) {
        val params = commandFactory.songFromGenre(song, genre, shuffle)
        if (params != null) {
            playbackManager.play(params)
            return
        }
        L.d(
            "Cannot use given genre parameter for $song [$genre from ${song.genres}], showing choice dialog"
        )
        startPlaybackDecision(PlaybackDecision.PlayFromArtist(song))
    }

    private fun playFromPlaylistImpl(song: Song, playlist: Playlist, shuffle: ShuffleMode) {
        L.d("Playing $song from $playlist")
        playImpl(commandFactory.songFromPlaylist(song, playlist, shuffle))
    }

    private fun startPlaybackDecision(decision: PlaybackDecision) {
        val existing = _playbackDecision.flow.value
        if (existing != null) {
            L.d("Already handling decision $existing, ignoring $decision")
            return
        }
        _playbackDecision.put(decision)
    }

    /**
     * Play an [Album].
     *
     * @param album The [Album] to play.
     */
    fun play(album: Album) {
        L.d("Playing $album")
        playImpl(commandFactory.album(album, ShuffleMode.OFF))
    }

    /**
     * Shuffle an [Album].
     *
     * @param album The [Album] to shuffle.
     */
    fun shuffle(album: Album) {
        L.d("Shuffling $album")
        playImpl(commandFactory.album(album, ShuffleMode.ON))
    }

    /**
     * Play an [Artist].
     *
     * @param artist The [Artist] to play.
     */
    fun play(artist: Artist) {
        L.d("Playing $artist")
        playImpl(commandFactory.artist(artist, ShuffleMode.OFF))
    }

    /**
     * Shuffle an [Artist].
     *
     * @param artist The [Artist] to shuffle.
     */
    fun shuffle(artist: Artist) {
        L.d("Shuffling $artist")
        playImpl(commandFactory.artist(artist, ShuffleMode.ON))
    }

    /**
     * Play a [Genre].
     *
     * @param genre The [Genre] to play.
     */
    fun play(genre: Genre) {
        L.d("Playing $genre")
        playImpl(commandFactory.genre(genre, ShuffleMode.OFF))
    }

    /**
     * Shuffle a [Genre].
     *
     * @param genre The [Genre] to shuffle.
     */
    fun shuffle(genre: Genre) {
        L.d("Shuffling $genre")
        playImpl(commandFactory.genre(genre, ShuffleMode.ON))
    }

    /**
     * Play a [Playlist].
     *
     * @param playlist The [Playlist] to play.
     */
    fun play(playlist: Playlist) {
        L.d("Playing $playlist")
        playImpl(commandFactory.playlist(playlist, ShuffleMode.OFF))
    }

    /**
     * Shuffle a [Playlist].
     *
     * @param playlist The [Playlist] to shuffle.
     */
    fun shuffle(playlist: Playlist) {
        L.d("Shuffling $playlist")
        playImpl(commandFactory.playlist(playlist, ShuffleMode.ON))
    }

    /**
     * Play a list of [Song]s.
     *
     * @param songs The [Song]s to play.
     */
    fun play(songs: List<Song>) {
        L.d("Playing ${songs.size} songs")
        playImpl(commandFactory.songs(songs, ShuffleMode.OFF))
    }

    /**
     * Shuffle a list of [Song]s.
     *
     * @param songs The [Song]s to shuffle.
     */
    fun shuffle(songs: List<Song>) {
        L.d("Shuffling ${songs.size} songs")
        playImpl(commandFactory.songs(songs, ShuffleMode.ON))
    }

    private fun playImpl(command: PlaybackCommand?) {
        playbackManager.play(requireNotNull(command) { "Invalid playback parameters" })
    }

    /**
     * Start the given [DeferredPlayback] to be completed eventually. This can be used to enqueue a
     * playback action at startup to then occur when the music library is fully loaded.
     *
     * @param action The [DeferredPlayback] to perform eventually.
     */
    fun playDeferred(action: DeferredPlayback) {
        L.d("Starting action $action")
        playbackManager.playDeferred(action)
    }

    // --- PLAYER FUNCTIONS ---

    /**
     * Seek to the given position in the currently playing [Song].
     *
     * @param positionDs The position to seek to, in deci-seconds (1/10th of a second).
     */
    fun seekTo(positionDs: Long) {
        L.d("Seeking to ${positionDs}ds")
        playbackManager.seekTo(positionDs.dsToMs())
    }

    /** Step back by 10 seconds in the current song. */
    fun stepBackwards() {
        L.d("Stepping back 10 seconds")
        val currentPositionMs = playbackManager.progression.calculateElapsedPositionMs()
        val newPositionMs = (currentPositionMs - 10000).coerceAtLeast(0)
        playbackManager.seekTo(newPositionMs)
    }

    /** Step forward by 10 seconds in the current song. */
    fun stepForward() {
        L.d("Stepping forward 10 seconds")
        val currentPositionMs = playbackManager.progression.calculateElapsedPositionMs()
        val currentSong = playbackManager.currentSong
        if (currentSong != null) {
            val newPositionMs =
                (currentPositionMs + STEP_INCREMENT).coerceAtMost(currentSong.durationMs)
            playbackManager.seekTo(newPositionMs)
        }
    }

    // --- QUEUE FUNCTIONS ---

    /** Skip to the next [Song]. */
    fun next() {
        L.d("Skipping to next song")
        playbackManager.next()
    }

    /** Skip to the previous [Song]. */
    fun prev() {
        L.d("Skipping to previous song")
        playbackManager.prev()
    }

    /**
     * Add a [Song] to the top of the queue.
     *
     * @param song The [Song] to add.
     */
    fun playNext(song: Song) {
        L.d("Playing $song next")
        playbackManager.playNext(song)
    }

    /**
     * Add a [Album] to the top of the queue.
     *
     * @param album The [Album] to add.
     */
    fun playNext(album: Album) {
        L.d("Playing $album next")
        playbackManager.playNext(listSettings.albumSongSort.songs(album.songs))
    }

    /**
     * Add a [Artist] to the top of the queue.
     *
     * @param artist The [Artist] to add.
     */
    fun playNext(artist: Artist) {
        L.d("Playing $artist next")
        playbackManager.playNext(listSettings.artistSongSort.songs(artist.songs))
    }

    /**
     * Add a [Genre] to the top of the queue.
     *
     * @param genre The [Genre] to add.
     */
    fun playNext(genre: Genre) {
        L.d("Playing $genre next")
        playbackManager.playNext(listSettings.genreSongSort.songs(genre.songs))
    }

    /**
     * Add a [Playlist] to the top of the queue.
     *
     * @param playlist The [Playlist] to add.
     */
    fun playNext(playlist: Playlist) {
        L.d("Playing $playlist next")
        playbackManager.playNext(playlist.songs)
    }

    /**
     * Add [Song]s to the top of the queue.
     *
     * @param songs The [Song]s to add.
     */
    fun playNext(songs: List<Song>) {
        L.d("Playing ${songs.size} songs next")
        playbackManager.playNext(songs)
    }

    /**
     * Add a [Song] to the end of the queue.
     *
     * @param song The [Song] to add.
     */
    fun addToQueue(song: Song) {
        L.d("Adding $song to queue")
        playbackManager.addToQueue(song)
    }

    /**
     * Add a [Album] to the end of the queue.
     *
     * @param album The [Album] to add.
     */
    fun addToQueue(album: Album) {
        L.d("Adding $album to queue")
        playbackManager.addToQueue(listSettings.albumSongSort.songs(album.songs))
    }

    /**
     * Add a [Artist] to the end of the queue.
     *
     * @param artist The [Artist] to add.
     */
    fun addToQueue(artist: Artist) {
        L.d("Adding $artist to queue")
        playbackManager.addToQueue(listSettings.artistSongSort.songs(artist.songs))
    }

    /**
     * Add a [Genre] to the end of the queue.
     *
     * @param genre The [Genre] to add.
     */
    fun addToQueue(genre: Genre) {
        L.d("Adding $genre to queue")
        playbackManager.addToQueue(listSettings.genreSongSort.songs(genre.songs))
    }

    /**
     * Add a [Playlist] to the end of the queue.
     *
     * @param playlist The [Playlist] to add.
     */
    fun addToQueue(playlist: Playlist) {
        L.d("Adding $playlist to queue")
        playbackManager.addToQueue(playlist.songs)
    }

    /**
     * Add [Song]s to the end of the queue.
     *
     * @param songs The [Song]s to add.
     */
    fun addToQueue(songs: List<Song>) {
        L.d("Adding ${songs.size} songs to queue")
        playbackManager.addToQueue(songs)
    }

    // --- STATUS FUNCTIONS ---

    /** Toggle [isPlaying] (i.e from playing to paused) */
    fun togglePlaying() {
        L.d("Toggling playing state")
        playbackManager.playing(!playbackManager.progression.isPlaying)
    }

    /** Toggle [isShuffled] (ex. from on to off) */
    fun toggleShuffled() {
        L.d("Toggling shuffled state")
        playbackManager.shuffled(!playbackManager.isShuffled)
    }

    /**
     * Toggle [repeatMode] (ex. from [RepeatMode.NONE] to [RepeatMode.TRACK])
     *
     * @see RepeatMode.increment
     */
    fun toggleRepeatMode() {
        L.d("Toggling repeat mode")
        playbackManager.repeatMode(playbackManager.repeatMode.increment())
    }

    // --- UI CONTROL ---

    /** Open the main panel, closing all other panels. */
    fun openMain() = openImpl(OpenPanel.MAIN)

    /** Open the playback panel, closing the queue panel if needed. */
    fun openPlayback() = openImpl(OpenPanel.PLAYBACK)

    /**
     * Open the queue panel, assuming that it exists in the current layout, is collapsed, and with
     * the playback panel already being expanded.
     */
    fun openQueue() = openImpl(OpenPanel.QUEUE)

    private fun openImpl(panel: OpenPanel) {
        val existing = openPanel.flow.value
        if (existing != null) {
            L.d("Already opening $existing, ignoring opening $panel")
            return
        }
        _openPanel.put(panel)
    }

    private companion object {
        private const val STEP_INCREMENT = 10000 // ms
        private const val AUDIO_INFO_POLL_MS = 200L
    }
}

data class PagerQueue(val queue: List<Song>, val index: Int)

data class PagerCommand(val update: UpdateInstructions?, val scroll: Int?)

/**
 * Command for controlling the main playback panel UI.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
enum class OpenPanel {
    /** Open the main view, collapsing all other panels. */
    MAIN,
    /** Open the playback panel, collapsing the queue panel if applicable. */
    PLAYBACK,
    /**
     * Open the queue panel, assuming that it exists in the current layout, is collapsed, and with
     * the playback panel already being expanded. Do nothing if these conditions are not met.
     */
    QUEUE,
}

/**
 * Command for opening decision dialogs when playback from a [Song] is ambiguous.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
sealed interface PlaybackDecision {
    /** The [Song] currently attempting to be played from. */
    val song: Song

    /** Navigate to a dialog to determine which [Artist] a [Song] should be played from. */
    class PlayFromArtist(override val song: Song) : PlaybackDecision

    /** Navigate to a dialog to determine which [Genre] a [Song] should be played from. */
    class PlayFromGenre(override val song: Song) : PlaybackDecision
}
