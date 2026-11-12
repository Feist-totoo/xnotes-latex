# V5 public evidence

Read [the complete report](../../FORMULA_ACCURACY_V5.md) before interpreting scores.
Original HUMAN TRAIN/valid/test data: Google LLC MathWriting 2024,
https://github.com/google-research-datasets/mathwriting, CC BY-NC-SA 4.0.
Original image set: UniMER-Test HWE, same indices 100–199 used in v3, with SHA-256
fingerprints retained. No source images, pen coordinates, private screenshots or
private recognition results are committed here or included in the production APK.

- `confirmation-freeze.json`, `confirmation-samples.json`: new 160 official TEST
  IDs frozen before inference, human provenance, input fingerprints and selection
  limitations. Linear group adds vectors; not the old matrix-only distribution.
- `android-confirmation-samples.json`: both raw and production float raster hashes.
- `android-confirmation.json`: final Android candidates, native ranking and timings;
  five changed rasters rerun through actual Stroke/FormulaDraft and substituted.
- `confirmation-ablation.json`: conservative MathML metric, all 160 denominators,
  package off/on and candidate oracle (diagnostic, not default accuracy).
- `subject-regression.json`, `old-confirmation-regression.json`: previously used
  200/240 sets, now regressions, not fresh confirmation. Selection manifests remain
  in ../v4. `validation.json`, `validation-parameter-search.json`: tuning on the
  ORIGINAL validation 160 only. Not validation against the new confirmation.
- `legacy-mathwriting.json`, `legacy-images.json`: exact original v3 200-case mix,
  including failures; real input type selects the engine, never reference truth.
- `precision-validation.json`: fixed 100-image validation before HWE test.
- `decoder-compaction.json`, `vision-retained.json`: final model hashes and
  transformations. Vision retains source Q8_0 bytes. `final-model-freeze.json`
  captures the chosen configuration before its HWE evaluation started.
- `decoder-output-parity.json`: five fixed validation predictions before/after
  removing inactive NextN, all identical (initial Q5 vision experiment).
  This tests the decoder transformation, not final-model accuracy.
- `android-images.json`: 20 fixed HWE cases (indices 100,105,...,195), native offline
  calls through actual image dispatch. Native 8/20 vs desktop 9/20; 19/20
  structures agree. Case 135 gains an explicit multiplication dot on Android,
  retained as a strict-metric failure. Do not extrapolate the fixed subset score.
- `synthetic-ui-regression.json`: synthetic x=1 fails in BOTH engines. Excluded
  from all real-handwriting accuracy scores. Screenshots show explicit user edits.
- `release-verification.json`, `post-publish-verification.json`: build, signature,
  offline Android checks and GitHub asset verification. See report for test scope.

Reproduction tools: ../../../tools/handwriting. Fixed versions and model hashes:
../../../app/src/main/assets/formula-gryph/manifest.json and formula-precision.
Metrics retain symbol case, layout, accents and mathematical font distinctions;
there is no answer-solving or test-time lookup of reference formulas.


`rejected-q5-*` records the unshipped Q4/Q5 candidate. Its smaller visual weights
caused much slower native Android encoding, so the final APK retains Q8 vision.
The candidate's larger four-test batch was deliberately stopped after one completed
test and three timing runs; it is not recorded as a complete passing batch.

`targeted-native-prior-regressions.json` checks two KNOWN earlier fixes in the actual
APK (one missing matrix column separator, one missing modulus bar). Both base
predictions fail and both package-ranked predictions pass. This is targeted
regression coverage, not a 2/2 generalization or new accuracy benchmark.


`v5-fast-speed.json`, `v5-precision-speed.json`, `speed-summary.json` retain cold,
warm and reload timings on the same three public cases as v3/v4. PSS is a snapshot,
not peak memory. Quantizing the precision decoder did not prove faster inference.

`android-public-regressions.json`: the earlier 12 public MathWriting image fixtures,
only 1/12 structurally correct in the final Android image path. Runtime success
is not accuracy. Private regression rows are deliberately omitted.
