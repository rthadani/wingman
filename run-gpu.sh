#!/usr/bin/env bash
# GPU variant of run.sh: starts Wingman with the medium model on the CUDA build
# of whisper.cpp. Needs WHISPER_CPP_DIR pointing at the whisper.cpp checkout
# built with -DGGML_CUDA=ON. LD_LIBRARY_PATH is required because the bundled
# libggml under deps.edn would otherwise win and load the CPU-only build.
set -uo pipefail
cd "$(dirname "$0")"

export WHISPER_CPP_DIR="${WHISPER_CPP_DIR:-$HOME/whisper-pinned}"
BUILD="$WHISPER_CPP_DIR/build"

# WHISPER_MODEL_FILE overrides the model; WHISPER_MODEL is ignored on purpose.
export WHISPER_MODEL="${WHISPER_MODEL_FILE:-$PWD/models/ggml-medium.en.bin}"
echo "whisper model: $WHISPER_MODEL"

if [ ! -f "$WHISPER_MODEL" ]; then
  echo "error: whisper model not found at $WHISPER_MODEL" >&2
  exit 1
fi

if [ ! -f "$BUILD/src/libwhisper.so" ]; then
  echo "error: missing $BUILD/src/libwhisper.so - build whisper.cpp at pinned commit c96906d with -DGGML_CUDA=ON" >&2
  exit 1
fi

export LD_LIBRARY_PATH="$BUILD/ggml/src:$BUILD/src${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export JAVA_TOOL_OPTIONS="-Djna.library.path=$BUILD/src${JAVA_TOOL_OPTIONS:+ $JAVA_TOOL_OPTIONS}"

exec "$PWD/run.sh" "${1:-start}"
