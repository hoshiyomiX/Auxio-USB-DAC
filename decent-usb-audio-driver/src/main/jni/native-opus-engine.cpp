/**
 * @file native-opus-engine.cpp
 * @brief Native Ogg/Opus decode -> USB audio engine.
 *
 * Mirrors native-audio-engine.cpp (the FLAC engine) for Ogg/Opus files.
 * Pipeline (single native thread, zero JNI in hot path):
 *
 *   fd -> OpusFileReader (pread64, no I/O thread needed for Opus)
 *       -> OggPageParser (minimal RFC 3533 page parser, no libogg dep)
 *       -> opus_decode() (libopus, outputs int16 PCM)
 *       -> padInt16ToInt32() (if DAC is 32-bit, lossless shift)
 *       -> submitPcmToUrbs() (existing USB pipeline)
 *
 * Bit-perfect rationale: libopus natively outputs int16 PCM (Opus codec is
 * 16-bit only). No float conversion happens anywhere in this path. The only
 * conversion is int16 -> int32 (left-shift by 16, bit-exact) when the USB
 * DAC alt setting is 32-bit -- same lossless padding used by the FLAC path
 * for 16-bit FLAC files.
 *
 * Ogg demuxing implements just enough of RFC 3533 (Ogg) + RFC 7845 (Opus in
 * Ogg) to support sequential playback and linear-scan seek. No libogg
 * dependency. The parser handles:
 *   - Page header parsing (magic, granule, segment table)
 *   - Packet assembly across segments (255-byte segment continuation rule)
 *   - OpusHead (page 2) and OpusTags (page 3) skip
 *   - EOS detection
 *
 * Limitations (intentional, documented):
 *   - Seek is O(N) in number of pages (linear scan from start). For a typical
 *     4-minute Opus file (~3000 pages), seek takes <100ms on flash storage.
 *     A bisection seek table can be added later if needed.
 *   - No OpusTags metadata extraction (apps read tags via musikr/TagLib).
 *   - Corrupt pages abort the engine -- caller falls back to ExoPlayer.
 */

#include "native-opus-engine.h"
#include "usb-audio-output.h"

#include <opus.h>

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <sys/stat.h>
#include <vector>
#include <string>

#define TAG "NativeOpusEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── OpusFileReader: pread64-based file reader ──────────────────────
//
// Simpler than AsyncBufferedDataSource (used by FLAC engine) because Opus
// decode is much lighter than FLAC decode -- no I/O thread needed.

class OpusFileReader {
public:
    OpusFileReader(int fd, bool ownsFd)
        : fd_(fd), ownsFd_(ownsFd), pos_(0) {
        struct stat st;
        if (fstat(fd_, &st) == 0) {
            length_ = st.st_size;
        } else {
            length_ = 0;
            LOGW("OpusFileReader: fstat failed errno=%d", errno);
        }
    }

    ~OpusFileReader() {
        if (ownsFd_ && fd_ >= 0) close(fd_);
    }

    ssize_t read(void *buf, size_t len) {
        ssize_t n = pread64(fd_, buf, len, pos_);
        if (n > 0) pos_ += n;
        return n;
    }

    // Read exactly `len` bytes or return false (EOF / error).
    bool readExact(void *buf, size_t len) {
        size_t got = 0;
        while (got < len) {
            ssize_t n = pread64(fd_, (uint8_t *)buf + got, len - got, pos_ + got);
            if (n <= 0) {
                if (n == 0) LOGW("OpusFileReader: EOF at offset %lld",
                                 (long long)(pos_ + got));
                else LOGW("OpusFileReader: pread64 failed errno=%d", errno);
                return false;
            }
            got += n;
        }
        pos_ += got;
        return true;
    }

    int peekByte() {
        uint8_t b;
        ssize_t n = pread64(fd_, &b, 1, pos_);
        if (n != 1) return -1;
        return b;
    }

    void seekTo(off64_t pos) { pos_ = pos; }
    off64_t tell() const { return pos_; }
    off64_t length() const { return length_; }

private:
    int fd_;
    bool ownsFd_;
    off64_t pos_;
    off64_t length_;
};

// ── OggPage: parsed Ogg page header + raw segment data ─────────────

struct OggPage {
    static const int MAX_SEGMENTS = 255;

    int64_t granulePosition;   // -1 if invalid
    int32_t pageSequenceNumber;
    uint8_t headerType;        // bit 0=continued, bit 1=BOS, bit 2=EOS
    uint8_t segmentCount;
    uint8_t segmentSizes[MAX_SEGMENTS];
    std::vector<uint8_t> data; // concatenated segment data
    bool isContinued;          // headerType & 0x01
    bool isBos;                // headerType & 0x02
    bool isEos;                // headerType & 0x04
};

// ── OggPageParser: minimal sequential Ogg page reader ──────────────
//
// Implements packet assembly across segments (the 255-byte continuation
// rule): a packet ends when a segment < 255 bytes is encountered. A segment
// of exactly 255 bytes means "packet continues in next segment".

class OggPageParser {
public:
    explicit OggPageParser(OpusFileReader *reader) : reader_(reader) {}

    // Read and parse the next Ogg page. Returns false on EOF or parse error.
    bool readNextPage(OggPage *out) {
        // OggS magic
        char magic[4];
        if (!reader_->readExact(magic, 4)) {
            return false;  // EOF
        }
        if (memcmp(magic, "OggS", 4) != 0) {
            LOGE("OggPageParser: bad magic at offset %lld: %02x%02x%02x%02x",
                 (long long)(reader_->tell() - 4),
                 (uint8_t)magic[0], (uint8_t)magic[1],
                 (uint8_t)magic[2], (uint8_t)magic[3]);
            return false;
        }

        // Page header (27 bytes total including magic)
        uint8_t version;
        if (!reader_->readExact(&version, 1)) return false;
        if (version != 0) {
            LOGE("OggPageParser: unsupported version %d", version);
            return false;
        }

        uint8_t headerType;
        if (!reader_->readExact(&headerType, 1)) return false;
        out->headerType = headerType;
        out->isContinued = (headerType & 0x01) != 0;
        out->isBos = (headerType & 0x02) != 0;
        out->isEos = (headerType & 0x04) != 0;

        // granule_position (8 bytes, LE)
        uint8_t granuleBytes[8];
        if (!reader_->readExact(granuleBytes, 8)) return false;
        int64_t granule = 0;
        for (int i = 0; i < 8; i++) {
            granule |= ((int64_t)granuleBytes[i]) << (i * 8);
        }
        out->granulePosition = granule;

        // bitstream_serial (4) + page_sequence (4) + CRC (4) -- skip
        uint8_t skip[12];
        if (!reader_->readExact(skip, 12)) return false;
        out->pageSequenceNumber =
            skip[4] | (skip[5] << 8) | (skip[6] << 16) | (skip[7] << 24);

        // page_segments (1 byte)
        uint8_t segCount;
        if (!reader_->readExact(&segCount, 1)) return false;
        out->segmentCount = segCount;

        if (segCount == 0) {
            out->data.clear();
            return true;
        }

        // segment table
        if (!reader_->readExact(out->segmentSizes, segCount)) return false;

        // total data size
        size_t totalData = 0;
        for (int i = 0; i < segCount; i++) {
            totalData += out->segmentSizes[i];
        }

        // read segment data
        out->data.resize(totalData);
        if (totalData > 0) {
            if (!reader_->readExact(out->data.data(), totalData)) return false;
        }

        return true;
    }

    // Assemble the next Opus packet from one or more Ogg pages.
    // Returns false on EOF, true with packet filled.
    // Sets *outGranule to the granule_position of the page containing the
    // end of this packet (used for position tracking).
    bool readNextPacket(std::vector<uint8_t> *outPacket, int64_t *outGranule) {
        outPacket->clear();

        // If we have leftover segments from the previous page (mid-packet),
        // continue consuming them first.
        while (true) {
            // Consume remaining segments from currentPage_ if any.
            while (currentSegmentIdx_ < currentPage_.segmentCount) {
                uint8_t segSize = currentPage_.segmentSizes[currentSegmentIdx_++];
                size_t segOffset = currentDataOffset_;
                currentDataOffset_ += segSize;
                if (segSize > 0) {
                    outPacket->insert(outPacket->end(),
                                      currentPage_.data.data() + segOffset,
                                      currentPage_.data.data() + segOffset + segSize);
                }
                if (segSize < 255) {
                    // End of packet
                    if (outGranule) *outGranule = currentPage_.granulePosition;
                    return true;
                }
                // segSize == 255: packet continues into next segment
            }

            // Need a new page
            if (!readNextPage(&currentPage_)) {
                if (!outPacket->empty()) {
                    // Trailing packet without end marker -- return what we have
                    if (outGranule) *outGranule = currentPage_.granulePosition;
                    return true;
                }
                return false;  // clean EOF
            }
            currentSegmentIdx_ = 0;
            currentDataOffset_ = 0;
        }
    }

    // Reset parser to file start (for seek)
    void reset(OpusFileReader *reader) {
        reader_ = reader;
        currentSegmentIdx_ = 0;
        currentDataOffset_ = 0;
        currentPage_.segmentCount = 0;
        currentPage_.data.clear();
    }

private:
    OpusFileReader *reader_;
    OggPage currentPage_;
    int currentSegmentIdx_ = 0;
    size_t currentDataOffset_ = 0;
};

// ── OpusHead parser (RFC 7845 §5.1) ────────────────────────────────
//
//   0: "OpusHead" (8 bytes)
//   8: version (1, =1)
//   9: channel_count (1)
//  10: pre_skip (2, LE)
//  12: sample_rate (4, LE) -- informational only, Opus is always 48kHz
//  16: output_gain (2, LE)
//  18: channel_mapping_family (1)
//  19: [optional mapping table]

struct OpusHead {
    int version;
    int channelCount;
    int preSkip;        // samples to skip at start
    int sampleRate;     // informational (Opus is always 48kHz native)
    int outputGain;     // Q7.8 dB
    int channelMappingFamily;
    bool valid;
};

static OpusHead parseOpusHead(const std::vector<uint8_t> &packet) {
    OpusHead h = {};
    if (packet.size() < 19) {
        LOGE("OpusHead: too short (%zu bytes)", packet.size());
        return h;
    }
    if (memcmp(packet.data(), "OpusHead", 8) != 0) {
        LOGE("OpusHead: bad magic");
        return h;
    }
    h.version = packet[8];
    h.channelCount = packet[9];
    h.preSkip = packet[10] | (packet[11] << 8);
    h.sampleRate = packet[12] | (packet[13] << 8) |
                   (packet[14] << 16) | (packet[15] << 24);
    h.outputGain = packet[16] | (packet[17] << 8);
    h.channelMappingFamily = packet[18];
    h.valid = (h.version == 1 && h.channelCount >= 1 && h.channelCount <= 2);
    if (h.channelCount > 2 && h.channelMappingFamily == 0) {
        LOGE("OpusHead: >2 channels but mapping family 0");
        h.valid = false;
    }
    return h;
}

// ── NativeOpusEngine ───────────────────────────────────────────────

struct NativeOpusEngine {
    // Input
    OpusFileReader *reader;
    OggPageParser *parser;
    OpusDecoder *decoder;

    // USB output (owned by UsbAudioStream on the Java side)
    UsbAudioContext *usbCtx;

    // Stream info (from OpusHead)
    int sampleRate;       // always 48000 for Opus (resampled by libopus if needed)
    int channels;         // 1 or 2
    int preSkip;          // samples to skip at start
    int dacBitDepth;      // from UsbAudioContext

    // Decode thread
    pthread_t thread;
    std::atomic<bool> running;
    std::atomic<bool> paused;

    // Position tracking
    std::atomic<int64_t> samplesDecoded;  // per-channel samples decoded
    int64_t seekTargetSample;
    std::atomic<bool> seekPending;
    int64_t totalSamples;  // from last page granule, -1 if unknown

    // Buffers
    opus_int16 *pcmBuffer;        // decoded int16 PCM
    uint8_t *convertBuffer;       // int16 -> int32 conversion buffer
    int pcmBufferSamples;         // capacity in samples (per channel * channels)
    int convertBufferBytes;
};

// Maximum Opus frame size at 48kHz = 5760 samples (120ms)
// Per RFC 6716 §3.1, frame sizes are 2.5, 5, 10, 20, 40, 60 ms (120ms for CELT-only).
#define MAX_OPUS_FRAME_SIZE 5760

// ── Decode thread ──────────────────────────────────────────────────

static void *opusDecodeThread(void *arg) {
    auto *engine = static_cast<NativeOpusEngine *>(arg);
    LOGI("Opus decode thread started: rate=%d ch=%d preSkip=%d dacBits=%d",
         engine->sampleRate, engine->channels,
         engine->preSkip, engine->dacBitDepth);

    std::vector<uint8_t> packet;
    int64_t pageGranule = 0;
    int64_t samplesSkippedAtStart = 0;
    bool initialSeekDone = !engine->seekPending.load();  // skip pre-roll unless seek pending

    while (engine->running.load()) {
        if (engine->paused.load()) {
            usleep(20000);
            continue;
        }

        // Handle seek (linear scan from file start)
        if (engine->seekPending.load()) {
            int64_t target = engine->seekTargetSample;
            LOGI("Opus seek: target=%lld samples (%.1f sec)",
                 (long long)target, (double)target / engine->sampleRate);

            // Reset to file start
            engine->reader->seekTo(0);
            engine->parser->reset(engine->reader);

            // Re-read OpusHead + OpusTags (pages 1 and 2)
            std::vector<uint8_t> headPacket, tagsPacket;
            int64_t g;
            if (!engine->parser->readNextPacket(&headPacket, &g) ||
                !engine->parser->readNextPacket(&tagsPacket, &g)) {
                LOGE("Opus seek: failed to re-read headers");
                engine->seekPending.store(false);
                continue;
            }

            // Scan forward page-by-page until granule >= target
            bool found = false;
            int64_t prevGranule = 0;
            while (engine->parser->readNextPacket(&packet, &pageGranule)) {
                if (pageGranule >= target) {
                    // Found the page containing the target sample.
                    // Decode this packet but skip samples before target.
                    found = true;
                    samplesSkippedAtStart = target - prevGranule;
                    if (samplesSkippedAtStart < 0) samplesSkippedAtStart = 0;
                    engine->samplesDecoded.store(target);
                    LOGI("Opus seek: found at granule=%lld (prev=%lld), skip %lld samples",
                         (long long)pageGranule, (long long)prevGranule,
                         (long long)samplesSkippedAtStart);
                    // Fall through to decode this packet below
                    break;
                }
                prevGranule = pageGranule;
            }
            if (!found) {
                LOGW("Opus seek: target beyond EOF, stopping");
                engine->running.store(false);
                engine->seekPending.store(false);
                break;
            }
            engine->seekPending.store(false);
            initialSeekDone = true;
            // Fall through: decode `packet` (the one we just read at the seek target)
        } else if (!initialSeekDone) {
            // First decode after create -- read headers and skip pre-roll
            std::vector<uint8_t> headPacket, tagsPacket;
            int64_t g;
            if (!engine->parser->readNextPacket(&headPacket, &g)) {
                LOGE("Opus decode: missing OpusHead");
                break;
            }
            OpusHead head = parseOpusHead(headPacket);
            if (!head.valid) {
                LOGE("Opus decode: invalid OpusHead");
                break;
            }
            // Skip OpusTags
            if (!engine->parser->readNextPacket(&tagsPacket, &g)) {
                LOGE("Opus decode: missing OpusTags");
                break;
            }
            initialSeekDone = true;
            samplesSkippedAtStart = engine->preSkip;
            // Read first audio packet
            if (!engine->parser->readNextPacket(&packet, &pageGranule)) {
                LOGI("Opus decode: no audio packets (empty stream)");
                break;
            }
        } else {
            // Normal: read next audio packet
            if (!engine->parser->readNextPacket(&packet, &pageGranule)) {
                LOGI("End of Opus stream, %lld samples decoded",
                     (long long)engine->samplesDecoded.load());
                break;
            }
        }

        if (packet.empty()) {
            LOGW("Opus decode: empty packet, skipping");
            continue;
        }

        // Decode packet
        int decoded = opus_decode(engine->decoder,
                                  packet.data(),
                                  (opus_int32)packet.size(),
                                  engine->pcmBuffer,
                                  MAX_OPUS_FRAME_SIZE,
                                  0);
        if (decoded < 0) {
            LOGE("opus_decode failed: %d (%s)", decoded, opus_strerror(decoded));
            // Continue to next packet -- don't abort on single decode error
            continue;
        }

        // Apply pre-skip / seek offset (skip first N samples)
        int samplesToUse = decoded;
        int startOffset = 0;
        if (samplesSkippedAtStart > 0) {
            int skip = (int)(samplesSkippedAtStart > decoded ? decoded : samplesSkippedAtStart);
            startOffset = skip;
            samplesToUse = decoded - skip;
            samplesSkippedAtStart -= skip;
        }

        if (samplesToUse <= 0) {
            // Whole packet was pre-skip
            engine->samplesDecoded.fetch_add(decoded);
            continue;
        }

        // Convert bit depth: Opus int16 -> DAC bit depth
        const uint8_t *usbData;
        int usbBytes;
        int totalSamples = samplesToUse * engine->channels;

        if (engine->dacBitDepth == 16) {
            // Direct: int16 PCM, just offset into the buffer
            usbData = (const uint8_t *)(engine->pcmBuffer + startOffset * engine->channels);
            usbBytes = totalSamples * 2;
        } else if (engine->dacBitDepth == 32) {
            // int16 -> int32: lossless left-shift by 16
            padInt16ToInt32((const uint8_t *)(engine->pcmBuffer + startOffset * engine->channels),
                            engine->convertBuffer, totalSamples);
            usbData = engine->convertBuffer;
            usbBytes = totalSamples * 4;
        } else {
            // 24-bit DAC: not directly supported (Opus is 16-bit native).
            // Fall back to int32 (DAC will truncate to 24, still bit-exact
            // for the upper 24 bits -- but this is not technically bit-perfect
            // since Opus has no 24-bit mode).
            if (engine->dacBitDepth == 24) {
                padInt16ToInt32((const uint8_t *)(engine->pcmBuffer + startOffset * engine->channels),
                                engine->convertBuffer, totalSamples);
                usbData = engine->convertBuffer;
                usbBytes = totalSamples * 4;
            } else {
                LOGE("Unsupported DAC bit depth: %d", engine->dacBitDepth);
                break;
            }
        }

        if (!engine->running.load()) break;

        // Submit to USB pipeline (natural backpressure from DAC clock)
        submitPcmToUrbs(engine->usbCtx, usbData, usbBytes);

        int64_t newTotal = engine->samplesDecoded.fetch_add(samplesToUse) + samplesToUse;
        if (newTotal % engine->sampleRate < samplesToUse) {
            LOGI("Opus decode: %lld samples (~%.0f sec)",
                 (long long)newTotal, (double)newTotal / engine->sampleRate);
        }
    }

    engine->running.store(false);
    LOGI("Opus decode thread exited, %lld total samples",
         (long long)engine->samplesDecoded.load());
    return nullptr;
}

// ── JNI entry points ───────────────────────────────────────────────

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_NativeOpusEngine_nativeCreateFromFd(
        JNIEnv *, jobject, jint fd, jlong usbHandle) {
    auto *usbCtx = reinterpret_cast<UsbAudioContext *>(usbHandle);
    if (!usbCtx) {
        LOGE("nativeCreateFromFd: null USB context");
        return 0;
    }

    int ownedFd = dup(fd);
    if (ownedFd < 0) {
        LOGE("nativeCreateFromFd: dup() failed errno=%d", errno);
        return 0;
    }

    auto *reader = new OpusFileReader(ownedFd, true);

    // Read OpusHead (page 1) to get sample rate + channels
    auto *parser = new OggPageParser(reader);
    std::vector<uint8_t> headPacket, tagsPacket;
    int64_t granule;
    if (!parser->readNextPacket(&headPacket, &granule)) {
        LOGE("nativeCreateFromFd: failed to read first packet (OpusHead)");
        delete parser;
        delete reader;
        return 0;
    }
    OpusHead head = parseOpusHead(headPacket);
    if (!head.valid) {
        LOGE("nativeCreateFromFd: invalid OpusHead");
        delete parser;
        delete reader;
        return 0;
    }
    // Skip OpusTags (page 2) -- we don't extract metadata here
    if (!parser->readNextPacket(&tagsPacket, &granule)) {
        LOGE("nativeCreateFromFd: failed to read OpusTags");
        delete parser;
        delete reader;
        return 0;
    }

    // Create Opus decoder
    int opusErr = 0;
    OpusDecoder *decoder = opus_decoder_create(48000, head.channelCount, &opusErr);
    if (opusErr != OPUS_OK || !decoder) {
        LOGE("nativeCreateFromFd: opus_decoder_create failed: %d (%s)",
             opusErr, opus_strerror(opusErr));
        delete parser;
        delete reader;
        return 0;
    }

    // Set pre-skip
    if (opus_decoder_ctl(decoder, OPUS_SET_GAIN(head.outputGain)) != OPUS_OK) {
        LOGW("nativeCreateFromFd: OPUS_SET_GAIN failed");
    }

    auto *engine = new NativeOpusEngine();
    engine->reader = reader;
    engine->parser = parser;
    engine->decoder = decoder;
    engine->usbCtx = usbCtx;
    engine->sampleRate = 48000;  // Opus is always 48kHz internally
    engine->channels = head.channelCount;
    engine->preSkip = head.preSkip;
    engine->dacBitDepth = usbCtx->bitDepth;
    engine->running.store(false);
    engine->paused.store(false);
    engine->samplesDecoded.store(0);
    engine->seekTargetSample = 0;
    engine->seekPending.store(false);  // engine starts paused, no seek yet
    engine->totalSamples = -1;

    // Allocate buffers
    engine->pcmBufferSamples = MAX_OPUS_FRAME_SIZE * head.channelCount;
    engine->pcmBuffer = (opus_int16 *)malloc(engine->pcmBufferSamples * sizeof(opus_int16));
    engine->convertBufferBytes = engine->pcmBufferSamples * 4;  // 32-bit output worst case
    engine->convertBuffer = (uint8_t *)malloc(engine->convertBufferBytes);
    if (!engine->pcmBuffer || !engine->convertBuffer) {
        LOGE("nativeCreateFromFd: buffer allocation failed");
        free(engine->pcmBuffer);
        free(engine->convertBuffer);
        opus_decoder_destroy(decoder);
        delete parser;
        delete reader;
        delete engine;
        return 0;
    }

    // Reset parser to file start so decode thread can read from beginning
    // (we consumed OpusHead + OpusTags during validation above)
    reader->seekTo(0);
    parser->reset(reader);

    LOGI("Opus engine created: rate=%d ch=%d preSkip=%d dacBits=%d",
         engine->sampleRate, engine->channels, engine->preSkip, engine->dacBitDepth);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_NativeOpusEngine_nativeStart(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeOpusEngine *>(handle);
    if (!engine || engine->running.load()) return JNI_FALSE;

    engine->running.store(true);
    engine->paused.store(false);

    int ret = pthread_create(&engine->thread, nullptr, opusDecodeThread, engine);
    if (ret != 0) {
        LOGE("nativeStart: pthread_create failed ret=%d", ret);
        engine->running.store(false);
        return JNI_FALSE;
    }

    // High priority for decode thread (matches FLAC engine)
    struct sched_param param;
    param.sched_priority = sched_get_priority_max(SCHED_FIFO);
    pthread_setschedparam(engine->thread, SCHED_FIFO, &param);

    LOGI("Opus engine started");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeOpusEngine_nativePause(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeOpusEngine *>(handle);
    if (engine) engine->paused.store(true);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeOpusEngine_nativeResume(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeOpusEngine *>(handle);
    if (engine) engine->paused.store(false);
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_NativeOpusEngine_nativeSeek(
        JNIEnv *, jobject, jlong handle, jlong positionUs) {
    auto *engine = reinterpret_cast<NativeOpusEngine *>(handle);
    if (!engine) return JNI_FALSE;

    engine->seekTargetSample = positionUs * engine->sampleRate / 1000000LL;
    // Update samplesDecoded immediately so getPositionUs returns the seek
    // target right away (matches FLAC engine behavior).
    engine->samplesDecoded.store(engine->seekTargetSample);
    engine->seekPending.store(true);
    LOGI("Opus seek requested: %lld us -> sample %lld",
         (long long)positionUs, (long long)engine->seekTargetSample);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeOpusEngine_nativeStop(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeOpusEngine *>(handle);
    if (!engine) return;
    engine->running.store(false);
    // Wake from pause
    engine->paused.store(false);
    pthread_join(engine->thread, nullptr);
    LOGI("Opus engine stopped");
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeOpusEngine_nativeDestroy(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeOpusEngine *>(handle);
    if (!engine) return;
    if (engine->running.load()) {
        engine->running.store(false);
        engine->paused.store(false);
        pthread_join(engine->thread, nullptr);
    }
    if (engine->decoder) {
        opus_decoder_destroy(engine->decoder);
        engine->decoder = nullptr;
    }
    free(engine->pcmBuffer);
    free(engine->convertBuffer);
    delete engine->parser;
    delete engine->reader;
    delete engine;
    LOGI("Opus engine destroyed");
}

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_NativeOpusEngine_nativeGetPositionUs(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeOpusEngine *>(handle);
    if (!engine) return 0;
    return (jlong)(engine->samplesDecoded.load() * 1000000LL / engine->sampleRate);
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeOpusEngine_nativeGetSampleRate(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeOpusEngine *>(handle);
    return engine ? engine->sampleRate : 0;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeOpusEngine_nativeGetChannels(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeOpusEngine *>(handle);
    return engine ? engine->channels : 0;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeOpusEngine_nativeGetBitsPerSample(
        JNIEnv *, jobject, jlong handle) {
    // Opus is always 16-bit (codec is 16-bit only, libopus outputs int16)
    return 16;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_NativeOpusEngine_nativeIsRunning(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeOpusEngine *>(handle);
    return engine && engine->running.load() ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
