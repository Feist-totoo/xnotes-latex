# xnotes LaTeX — 离线手写公式白板

Android 平板白板 fork：**手写侧栏 → 停笔本机识别 → LaTeX 预览纠错 → 可拖动公式块**，同时保留套索识别、分页笔记和无限画布。

- **[下载 v7 APK](https://github.com/Feist-totoo/xnotes-latex/releases/download/v0.9.8-latex.7-offline-sidebar/xnotes-latex-0.9.8-latex.7-offline-sidebar.apk)** · [Release](https://github.com/Feist-totoo/xnotes-latex/releases/tag/v0.9.8-latex.7-offline-sidebar)。约 **67.8 MB**，Android 8.0+，arm64-v8a / x86_64，沿用旧签名覆盖升级。
- 在 **偏好设置 → 离线 OCR 设置** 主动下载 **32.2 MB** 数学模型包；随后识别完全离线。Paddle API、令牌入口和云端识别已移除。APK 包含 ONNX 运行库，不内置模型权重。
- 工具栏 **Σ** 打开数学手写侧栏：上方书写，下方停笔自动预览；换行继续当前块，换块保留草稿，插入通过与图片相同的接口放到白板。支持手动修正 LaTeX。
- 偏好设置可调整自动识别、快速/均衡/精细模式、停笔等待、CPU 线程和学科包辅助排序。默认精细模式；本版沿用已有模型，没有宣称新的训练准确率提升。
- 修复工具栏插入图片的目标状态保存、后台读取、插入后定位和选中；保留图片横切/竖切/斜切、拖动、再次切割和撤销。[切图操作](docs/IMAGE_SPLIT.md)。
- 十个学科包独立启用与管理，高数/线代包的兼容先验参与接近候选的重排；不能保证复杂矩阵、下标或分式正确。保留公式源代码编辑和原有 LaTeX 空格修复。
- [操作、设置与测试说明](docs/OFFLINE_MATH_SIDEBAR.md) · [逐条真实手写基准](docs/benchmarks/v7-offline-sidebar/) · [模型和学科包 Release](https://github.com/Feist-totoo/xnotes-latex/releases/tag/v0.9.8-latex.5-full)。延迟来自 Android 模拟器，没有三星/S Pen 真机结果；“停笔预览”并非逐笔即时识别。
- **2523 项 JVM 测试、19 项 Android 回归通过**。160 条真实高数/线代手写结构匹配 95 条（59.4%），精细模式热运行引擎中位数约 1.51 秒；现有小模型仍需逐式校对。默认停笔等待另加 550 ms。
- 代码 AGPL-3.0，保留原 xnotes MIT 署名。模型/运行库许可证随应用附带；高数、线代统计数据为 CC BY-NC-SA 4.0。测试笔迹与用户截图不随应用分发。
- 下方保留上游介绍；商店与上游 Release 不包含本 fork 功能。

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
