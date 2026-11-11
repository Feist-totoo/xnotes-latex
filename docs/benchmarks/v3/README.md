# Evaluation records

Public MathWriting data: Google Research, **CC-BY-NC-SA 4.0**, evaluation only.
Source: https://github.com/google-research-datasets/mathwriting and
https://storage.googleapis.com/mathwriting_data/mathwriting-2024-excerpt.tgz
Archive SHA-256: cba038def001480a89962b25cb20a60df4c4145e94c86ef9d2af65f192cb82bc

Public HWE data: https://huggingface.co/datasets/alephpi/UniMER-Test (hwe rows 100–199),
original benchmark https://github.com/opendatalab/UniMERNet.
The reports and fingerprints are for evaluation/reproduction, not a training
dataset or an independently licensed redistribution of the original images.
Original datasets retain their terms. No public sample images, private user image,
or test APK is distributed with the application.

`*-scored.json` contains every public reference and prediction, including failures.
Scores are conservative MathML tree agreement, not a calibrated OCR accuracy or
CAS equivalence. `tools/score_formula_structure.py` documents normalization rules.
The older Texo baseline uses the same samples. MathWriting test and HWE 100–199 were
not used to pick the engine or preprocessing in this implementation; upstream
training-data overlap cannot be ruled out for any publicly released model.

`android-speed.json` records three executions of the same locally held regression
image. Its contents are not published. Cold/warm/cold outputs were identical.
This small speed measurement does not establish a hardware performance guarantee.
