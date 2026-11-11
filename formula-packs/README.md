# Independent subject packs

The ten editable JSON sources in this directory build into separate `.xformula`
assets on **GitHub Releases**. This is a data-only mod format, not executable code
or a set of ten separately trained OCR models. Core image recognition stays on
the tablet. Installed packs can be enabled, disabled, updated, imported or removed
independently, without restarting recognition or duplicating neural weights.

Build release assets with `python tools/package_formula_mods.py`. The generated
`formula-packs-index.json` records each package's immutable versioned Release URL,
byte size, SHA-256 and routing features. The application refreshes the catalog
only when requested, through the repository's latest GitHub Release asset.
App releases must also include the current catalog; package-only releases include
the catalog plus the independent packages. Failed downloads leave installed packs
unchanged. Initial core recognition and the bundled school-math pack work offline.

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
be disabled. Package selection never rewrites a recognized formula or silently
changes unfamiliar variables to textbook expressions.

For a local custom pack, edit a source JSON, give it a distinct ID, save it with a
`.xformula` extension and use “Import file”. Compatible installed versions persist
in private local storage. Downgrades require uninstalling the newer version first.

License: AGPL-3.0, as in the repository root.
