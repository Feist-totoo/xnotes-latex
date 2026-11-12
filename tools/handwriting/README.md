# Offline handwriting research and reproduction

This directory is developer tooling, not part of the Android runtime. The preview
does not meet the complete quality target: see [the report](../../docs/FORMULA_ACCURACY_V4.md),
including the image-only regression and the zero net subject-profile gain.

For a normal APK build, run `python tools/prepare_handwriting_model.py` and
`python tools/package_formula_mods.py`; the converted graphs are pinned by size
and SHA-256. No evaluation data or Python interpreter is needed on the tablet.

For research reproduction, use Python 3.12 and `requirements.txt`, plus requests
for downloads. Install the pinned MathML renderer with
`npm ci --prefix tools/formula_metrics`.

1. Clone https://github.com/JG1VPP/GryphOne into `build/references/GryphOne` and
   checkout `5f5f5f5f29fe9dc8d4f68f2ff851fd549929af58`. Do not modify upstream files.
2. Create `build/accuracy-v4`. Download official release `ECCV2026` assets into it:
   - `gryph_e63e36e_mathwriting_sym_mutual_dino_vits8.pth`: 117,917,425 bytes,
     SHA-256 `d6430f361e144cc64eda9be6451478bcf8268f1ba191536fcb2380fd2708ae3f`.
   - `gryph_mathwriting_valid_test.pkl`: 164,301,093 bytes,
     SHA-256 `d76e4a4fe27c7bbee1ae51da8843f04bdbf3a36c354bde37650a2b2326c378f4`.
   The data-only pickle loader rejects all class construction. The checkpoint is
   loaded with `weights_only=True` and strict state-dict matching.
3. Download https://storage.googleapis.com/mathwriting_data/mathwriting-2024.tgz
   to `build/accuracy-v4/mathwriting-2024.tgz`: 3,096,141,721 bytes,
   SHA-256 `f1977f120b0efa8abf715ac544b0596b213b099133da99b5e64f946730929269`.
   `range_download.py URL DEST --size BYTES --sha256 HASH` supports verified range
   downloads without executing archive contents.
4. Run `tools/handwriting_benchmark.py fetch --destination build/accuracy-v3`,
   then `tools/handwriting/prepare_legacy_ink.py`. This restores the original v3
   sample identities and safe InkML members, including the conversion parity input.
5. Run `prepare_subjects.py`, then `train_priors.py`, then `prepare_confirmation.py`.
   Use paths relative to the repository root (e.g. `python tools/handwriting/prepare_subjects.py`).
   They reproduce deterministic selection, verify original human provenance, and
   learn counts strictly from TRAIN. Confirmation samples come from unused official
   VALID, not from a new official TEST partition. Never move them into training.
6. `export_gryph.py` reproduces encoder/cached-attention and decoder-step wrappers,
   checks PyTorch/FP32 ONNX parity, and quantizes MatMul/Gemm per-channel to signed
   8-bit weights. The DINO configuration is tracked here; all learned weights come
   from the official checkpoint. No network download occurs inside model loading.
7. Run the frozen inference contract, for example:

   ```sh
   python tools/handwriting/probe_onnx.py --quant --steps 20 --candidates 4 --rescore --data build/accuracy-v4/confirmation --limit 240 --output build/accuracy-v4/confirmation-reproduced.json
   python tools/handwriting/score_candidates.py build/accuracy-v4/confirmation-reproduced.json --manifest build/accuracy-v4/confirmation/manifest.json
   ```

   `--rescore` is the shipped masked-quarter scoring. Without it the script runs
   the rejected self-conditioned scoring experiment. The optional `--skeleton`
   bitmap experiment also failed validation and is **not** the app's pen path.
8. Prepare real Android fixtures with `prepare_android_fixtures.py DATA`, or
   `prepare_android_fixtures.py DATA --confirmation`. These enter only the
   androidTest APK. `OfflineStrokeBenchmarkTest` supports instrumentation arguments
   `dataset=handwriting-confirmation` and `roundtrip=true` to verify the actual
   Stroke/FormulaDraft storage path where its input pixels differ from raw InkML.
   Shut off Wi-Fi/mobile data and enable airplane mode before offline tests.
   Predictions and per-stage times are written to the app's external test directory.
9. `CompatFormulaSpeedTest` uses the same three fixed human cases on v3 and v4 APKs.
   It bridges the old/new suspend API without changing either recognizer. Compare
   warm and explicit-reload runs, not the first model unpack with an already
   unpacked installation. Never deploy test downgrades to the user's real tablet.

All original/reference data and the learned statistics retain Google LLC's
CC BY-NC-SA 4.0 conditions. The APK contains no original handwriting, evaluation
images, or private user screenshots. Only public provenance, predictions and
fingerprints are committed in `docs/benchmarks/v4`.
