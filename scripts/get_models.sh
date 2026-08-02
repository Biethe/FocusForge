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

# ---------------------------------------------------------------------------
# The coach model — SmolLM2-360M-Instruct, Q4_K_M GGUF (Apache-2.0).
#
# NOT downloaded automatically and NEVER committed. The app holds no INTERNET
# permission (CLAUDE.md §4.3), so the model reaches the phone by hand:
# download here on the PC, copy to the phone, import it in-app.
# ---------------------------------------------------------------------------
cat <<'INSTRUCTIONS'

=== Coach model (manual, one time) ===

1. On this PC, open https://huggingface.co and search for:

       SmolLM2-360M-Instruct GGUF

   Use the HuggingFaceTB original or a community GGUF build (bartowski's are
   the usual ones). Open "Files and versions".

2. Download the file whose name ends:

       ...SmolLM2-360M-Instruct-Q4_K_M.gguf

   Expect roughly 250-300 MB. If the file you are looking at is over 400 MB you
   have picked a larger quant — go back and take Q4_K_M. Do NOT take a bigger
   model "for quality": the A20e has 3 GB of RAM and a 700 MB budget.

3. Check the licence on the model card says Apache-2.0 before downloading
   (CLAUDE.md §4.2 allows MIT / Apache-2.0 / BSD only).

4. Copy it to the phone over USB, anywhere you can find again — Downloads is
   fine. Then in FocusForge: "LLM smoke test" -> "Import .gguf model" and pick
   it. The app copies it into its own storage once; after that the file in
   Downloads can be deleted.

The model is gitignored and must never be committed.

INSTRUCTIONS
