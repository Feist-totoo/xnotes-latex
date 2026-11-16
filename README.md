# xnotes LaTeX — 离线公式白板

**v9：停笔确认排版、分段与原笔迹草稿编辑。**

- [下载 APK](https://github.com/Feist-totoo/xnotes-latex/releases/download/v0.9.8-latex.9-confirm/xnotes-latex-0.9.8-latex.9-confirm.apk) · [Release](https://github.com/Feist-totoo/xnotes-latex/releases/tag/v0.9.8-latex.9-confirm) · [操作说明](docs/CONFIRMED_FORMULA_EDIT.md)。约 67.9 MB，Android 8+，arm64 / x86_64，同签名覆盖升级。
- 顶部 Σ 启用后，停笔仅显示 **✓ 渲染 / ；分段**。点 ✓ 才运行一次本地 OCR；点 ；保留这批笔迹，下一笔开启新批次。自由套索选择后可点「渲染选中笔记」。
- 选中公式点 **修改原笔记**，右上小窗补写、擦除、撤销/恢复；✓ 确认替换，× 取消。打开 TeX 可编辑源码；实际改动 LaTeX 后清除草稿笔迹，转为纯源码编辑，此操作与提交都可撤销。
- 默认工具栏第三组为撤销/恢复。清理公式替换后的手写前缓冲，避免下一笔带回旧笔迹；按批次维护范围缓存，确认前不运行识别。
- 继续使用原离线模型与十个学科包。首次到偏好设置下载 32.2 MB 模型，之后断网使用；已有模型无需重下，没有 Paddle 或识别上传。
- 图片仍用剪刀原位划线切块，双侧主题色 30% 蒙版、独立拖动、连续切分与撤销。
- 本次不声明模型准确率提升。历史 v7 的真实高数/线代结构匹配 **95/160**；[历史测试](docs/benchmarks/v7-offline-sidebar/)与[v9 测试报告](docs/benchmarks/v9-confirm/TEST-REPORT.json)。模拟器验证不能代替三星/S Pen 真机流畅度测试。
- 代码 AGPL-3.0，保留上游署名；模型、运行库和学科包沿用其附带许可证。测试笔迹不进入 APK。下方为上游介绍。

---

<p align="center">
  <img src="docs/readme-banner.png" width="100%" alt="xnotes: a handwriting-first notebook for Android" />
</p>

<p align="center">
  <a href="https://github.com/shardulvs/xnotes-android/releases/latest"><img src="https://img.shields.io/github/v/release/shardulvs/xnotes-android?style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://f-droid.org/en/packages/com.xnotes"><img src="https://img.shields.io/f-droid/v/com.xnotes?style=flat-square" alt="F-Droid" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-orange?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
  <a href="https://github.com/sponsors/shardulvs"><img src="https://img.shields.io/badge/sponsor-%E2%9D%A4-ec6cb9?style=flat-square&logo=githubsponsors&logoColor=white" alt="Sponsor" /></a>
</p>

<p align="center">
  <a href="https://f-droid.org/en/packages/com.xnotes"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="70" alt="Get it on F-Droid" /></a>
  <a href="https://play.google.com/store/apps/details?id=com.xnotes"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="70" alt="Get it on Google Play" /></a>
</p>

<p align="center">
  <img src="docs/screenshots/editor.png" width="90%" alt="The xnotes editor with a PDF open in dark mode, annotated by hand with a note, an arrow, a highlight and an underline, and page thumbnails down the left" />
  <br />
  <em>Annotating a PDF in dark mode, with the pages inverted</em>
</p>

<p align="center">
  <img src="docs/screenshots/explorer.png" width="90%" alt="The xnotes file explorer in gallery view, showing a grid of imported book covers with page counts" />
  <br />
  <em>Browsing imported PDFs in the gallery view</em>
</p>

---

## Features

- **Ink that feels like a pen**: pressure-sensitive and low latency, with pens and highlighters.
- **Neon glow**: make any pen glow.
- **Disappearing ink**: ink that fades away on its own, great for teaching and presenting.
- **Tape**: cover answers with tape and tap to peel it back, for testing yourself.
- **Shape snapping**: draw a rough shape, hold, and it snaps clean.
- **Sharp at any zoom**: ink and PDFs stay crisp however far you zoom in.
- **Notebooks and an infinite canvas**: pages when you want structure, endless space when you don't.
- **Book layouts**: single, double or cover pages, scrolling or page flips.
- **PDF import**: bring in any PDF and write all over it.
- **Room for side notes**: widen a PDF's margins on any edge and take notes on the side.
- **Insert space**: drag anywhere on a page to open up room there, even in the middle of a PDF.
- **PDF text markups**: highlight, underline and strike out text, add comments, search it.
- **PDF colour filters**: invert, sepia, contrast, brightness, multiply and screen.
- **Images stay untouched**: filter a PDF and its photos and images keep their true colours.
- **Dark and OLED modes**: easy on the eyes at night, true black on OLED screens.
- **Real PDF export**: sharp vector output with selectable text, links and bookmarks.
- **Markdown**: write in markdown and it formats as you type.
- **Typed notes**: tables, LaTeX equations and code highlighting, right on the page.
- **Audio recording**: record a lecture as you write, and your ink replays in sync with it.
- **20+ page templates**: Cornell, planners, music staves, isometric and more.
- **Endless themes**: any accent colour, eight styles for each, dual tone and Material You.
- **Make it yours**: rearrange the toolbar, float it or dock it on any edge, and pick its size.
- **Split view**: two notes side by side.
- **A real file explorer**: grid, gallery, list, column and timeline views, with plenty to customise.
- **Your notes, your folders**: notes are saved as files in a folder you choose, not locked away in an app's database, so you stay in full control of them.
- **Works with stylus pens**: S Pen and many others.
- **Always editable**: nothing gets flattened, so every stroke can be moved, restyled or erased later.
- **Private**: open source, no account, no ads, no tracking. Whiteboard editing and LaTeX typesetting work offline. Handwriting recognition uses Paddle AI Studio after you explicitly upload a selection; GitHub package updates are optional.

## Install

| Channel | |
|---|---|
| [GitHub Releases](https://github.com/shardulvs/xnotes-android/releases/latest) | Signed APK |
| [F-Droid](https://f-droid.org/en/packages/com.xnotes) | Built reproducibly from source |
| [Google Play](https://play.google.com/store/apps/details?id=com.xnotes) | Automatic updates |

GitHub and F-Droid ship the same signed APK, so you can switch between them without reinstalling.

## Build from source

Needs JDK 21 or 22 (tested with 22), NDK 27.0.12077973 and CMake 3.22.1.

```bash
git clone https://github.com/shardulvs/xnotes-android.git
cd xnotes-android
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```
