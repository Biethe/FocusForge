#!/usr/bin/env bash
# Fetches model files that are NEVER committed (models/ and *.task are gitignored).
# The gradle task :app:downloadModels does the same thing automatically at build time;
# this script exists for offline/manual setup and CI cache priming.
set -euo pipefail

FACE_LANDMARKER_URL="https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
DEST="$(dirname "$0")/../app/src/main/assets/models/face_landmarker.task"

mkdir -p "$(dirname "$DEST")"
if [ -s "$DEST" ]; then
    echo "Already present: $DEST ($(wc -c < "$DEST") bytes)"
else
    echo "Downloading face_landmarker.task (~3.7 MB, pinned float16/1)..."
    curl -fL --retry 3 -o "$DEST" "$FACE_LANDMARKER_URL"
    echo "Saved: $DEST ($(wc -c < "$DEST") bytes)"
fi
