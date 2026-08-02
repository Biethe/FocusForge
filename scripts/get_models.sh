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
# The coach model — SmolLM2-360M-Instruct GGUF (Apache-2.0).
#
# NOT downloaded automatically and NEVER committed. The app holds no INTERNET
# permission (CLAUDE.md 4.3), so the model reaches the phone by hand: download
# here on the PC, copy to the phone, import it in-app.
#
# Repos and filenames below were verified against the Hugging Face API on
# 2026-08-02 — an earlier version of this script said "search for it", which
# sent the operator to the official repo that publishes only q8_0.
# ---------------------------------------------------------------------------
cat <<'INSTRUCTIONS'

=== Coach model (manual, one time) ===

PREFERRED — Q4_K_M, 271 MB. Either of these, both Apache-2.0:

  https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF
      file: SmolLM2-360M-Instruct-Q4_K_M.gguf

  https://huggingface.co/unsloth/SmolLM2-360M-Instruct-GGUF
      file: SmolLM2-360M-Instruct-Q4_K_M.gguf

ALSO USABLE — Q8_0, 386 MB, from the official repo:

  https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF
      file: smollm2-360m-instruct-q8_0.gguf

  This repo publishes ONLY q8_0 — there is no Q4_K_M there, so do not go
  looking for one. Q8_0 is 115 MB larger and slower to generate with, because
  every token moves more weight bytes through memory, and this phone is
  bandwidth-limited. Fine for proving the pipeline runs; use Q4_K_M for any
  number we report.

TWO TRAPS:

  * Do NOT use prithivMLmods/SmolLM2-360M-Instruct-GGUF. It has a Q4_K_M, but
    it is licensed creativeml-openrail-m, which is not on our allow-list of
    MIT / Apache-2.0 / BSD (CLAUDE.md 4.2).

  * Ignore any file named Q4_0_4_4, Q4_0_4_8 or Q4_0_8_8. Those are old
    ARM-repacked formats aimed at dotprod / i8mm / SVE hardware — which this
    phone does not have — and current llama.cpp has dropped them in favour of
    repacking at load time.

Check the model card says apache-2.0 before downloading. Then copy the file to
the phone over USB (Downloads is fine) and, in FocusForge, open
"LLM smoke test" -> "Import .gguf model" and pick it. The app copies it into
its own storage once; the copy in Downloads can then be deleted.

The model is gitignored and must never be committed.

INSTRUCTIONS
