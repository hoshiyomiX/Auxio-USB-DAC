#!/bin/bash
# Setup script for decent-usb-audio-driver (Opus native decoder)
# Downloads the xiph/opus source code required to build libopus into
# libdecent_usb_audio.so for bit-perfect OGG/Opus playback via USB DAC.
#
# Mirrors the pattern used by decent-media3-decoder-flac/setup.sh:
#   - Download release tarball (deterministic version, no git history)
#   - Extract to src/main/jni/libopus/
#   - CMake add_subdirectory() in CMakeLists.txt picks it up at build time
#
# Re-run this script after deleting src/main/jni/libopus/ to upgrade libopus.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LIBOPUS_DIR="$SCRIPT_DIR/src/main/jni/libopus"
OPUS_VERSION="1.5.2"
OPUS_TARBALL="opus-${OPUS_VERSION}.tar.gz"
OPUS_URL="https://github.com/xiph/opus/releases/download/v${OPUS_VERSION}/${OPUS_TARBALL}"

if [ -d "$LIBOPUS_DIR" ]; then
    echo "libopus already exists at $LIBOPUS_DIR"
    echo "To re-download, delete the directory and run this script again."
    exit 0
fi

echo "Downloading xiph/opus v${OPUS_VERSION}..."
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

curl -fSL "$OPUS_URL" -o "$TMP_DIR/$OPUS_TARBALL"

echo "Extracting to $LIBOPUS_DIR..."
mkdir -p "$LIBOPUS_DIR"
tar -xzf "$TMP_DIR/$OPUS_TARBALL" -C "$TMP_DIR"
# Tarball extracts to opus-<version>/ — move contents into libopus/
mv "$TMP_DIR/opus-${OPUS_VERSION}/"* "$LIBOPUS_DIR/"
mv "$TMP_DIR/opus-${OPUS_VERSION}"/.[!.]* "$LIBOPUS_DIR/" 2>/dev/null || true

echo ""
echo "Done! libopus v${OPUS_VERSION} is ready at $LIBOPUS_DIR"
echo "You can now build with: ./gradlew :decent-usb-audio-driver:assembleDebug"
echo ""
echo "Note: libopus is built as a static library and linked into"
echo "libdecent_usb_audio.so. No separate .so is shipped."
