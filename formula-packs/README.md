# Independent subject packs

The ten editable JSON sources in this directory build into separate `.xformula`
assets on **GitHub Releases**. This is a data-only mod format, not executable code
or a set of ten separately trained OCR models. Core image recognition stays on
the tablet. Installed packs can be enabled, disabled, updated, imported or removed
independently, without restarting recognition or duplicating neural weights.

Build these preview assets with `python tools/package_formula_mods.py --tag v0.9.8-latex.4-preview`. The generated
`formula-packs-index.json` records each package's immutable versioned Release URL,
byte size, SHA-256 and routing features. The application refreshes the catalog
only when requested, through the preview's Release catalog (the stable v3 channel remains unchanged). The catalog may be refreshed by the maintainer while package filenames remain versioned.
App releases must also include the current catalog; package-only releases include
the catalog plus the independent packages. Failed downloads leave installed packs
unchanged. Initial core recognition and the bundled school-math, calculus and linear-algebra packs work offline.

Each pack declares `format: xnotes-formula-pack`, `schema: 1`, `type: vocabulary`,
an ASCII `id`, integer `version`, `minAppVersionCode`, names, review reminders,
`signals`, `symbols` and `templates`. The maximum file size is 128 KiB. Templates
are editable LaTeX; inserting a whole template requires explicit confirmation.
Files contain no regex programs, scripts, native libraries or network endpoints.

`signals` map short literal TeX token sequences to integer weights (1–8).
The built-in router reuses the output of the local image recognizer, handles
command boundaries and optional braces around single tokens, and recommends up to
three packs when the score is at least four. It reports matching features; scores
are **not calibrated probabilities**. No extra visual model is loaded. An empty
recommendation keeps the manually enabled general helpers. Automatic routing can
be disabled. For v2 recognition packs, switching packages can rerank the already generated
visual alternatives before editing. User edits and explicitly chosen alternatives
are preserved. No package can generate a replacement textbook expression.

For a local custom pack, edit a source JSON, give it a distinct ID, save it with a
`.xformula` extension and use “Import file”. Compatible installed versions persist
in private local storage. Downgrades require uninstalling the newer version first.

## Recognition resources (v2, app versionCode 72+)

Calculus and linear algebra include an optional `recognition` object with a pinned
engine ID, vocabulary hash, smoothing alpha, bounded scoring strength and maximum
visual-score gap. `pairs` contains sparse `[symbolId, structureId, count]` rows.
The Android parser rejects incompatible vocabularies, duplicate or out-of-range
rows, oversized files and unsupported values. These are data, never executable mods.

The resources are learned only from HUMAN TRAIN labels in Google MathWriting
2024: calculus 38,842 examples, linear algebra 8,859. No valid/test cases contribute
counts. `tools/handwriting/train_priors.py` reproduces them from the verified
archive. The neural model is shared; turning a pack on does not duplicate weights
or re-encode the image. Eight other subject packs remain editing resources; their
availability is not evidence of higher recognition accuracy.

The recognition statistics are a separate CC BY-NC-SA 4.0 resource derived from
Google LLC's MathWriting dataset, with attribution, noncommercial and share-alike
conditions. Source and license URLs are embedded in each package. Original raw
handwriting and test images are not bundled. Application code and editing content
remain AGPL-3.0, as in the repository root. See the measured pack ablation and
limitations in [the v4 report](../docs/FORMULA_ACCURACY_V4.md).


Experimental scoring is OFF by default: the 240-case confirmation set remained
201/240 with it on (one fix, one regression). Enabling an editing pack alone does
not enable this experiment. See the full report before using the preview.
