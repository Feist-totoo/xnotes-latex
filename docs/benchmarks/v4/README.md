# v4 preview evidence

See [the complete report](../../FORMULA_ACCURACY_V4.md). This preview does **not**
replace v3: pen-coordinate recognition improved, while image-only recognition
regressed substantially, and subject scoring did not produce a net confirmation gain.

- `configuration.json`: frozen model, decoder, masked scoring and profile parameters.
- `initial-splits.json`, `confirmation-split.json`: deterministic selection and hashes.
- `*-samples.json`, `human-verification.json`: original human-data provenance, labels and hashes.
- `glm-subject-baseline.json`: previous model on the same 200 subject cases.
- `subject-regression.json`: revised 200-case regression and pack ablation.
- `android-confirmation.json`: 240 real cases, including production Stroke storage rounding.
- `legacy-mathwriting.json`, `legacy-images.json`: both original v3 benchmark groups,
  including the large image-only regression. No failures are hidden or dropped.

Metric: unchanged conservative MathML structural agreement, not semantic equivalence,
not a confidence percentage, and not merely successful rendering. Routing uses only
predictions and enabled packages, never the sample's ground-truth subject.

Google LLC's MathWriting 2024 references/derived statistics are CC BY-NC-SA 4.0:
https://github.com/google-research/google-research/tree/master/mathwriting
https://creativecommons.org/licenses/by-nc-sa/4.0/
Only public labels, predictions and fingerprints are redistributed here; no raw ink,
images or private user samples. HWE evaluation provenance remains in ../v3/README.md.
