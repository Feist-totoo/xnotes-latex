# xnotes LaTeX — 离线手写公式白板

Android 平板白板 fork：**顶部 Σ 开关 → 白板直接书写 → 停笔本机识别 → 原位同色 LaTeX 块**，保留套索识别、分页笔记和无限画布。

- **[下载 v8 APK](https://github.com/Feist-totoo/xnotes-latex/releases/download/v0.9.8-latex.8-inline/xnotes-latex-0.9.8-latex.8-inline.apk)** · [Release](https://github.com/Feist-totoo/xnotes-latex/releases/tag/v0.9.8-latex.8-inline)。约 **67.8 MB**，Android 8.0+，arm64-v8a / x86_64，沿用旧签名覆盖升级。
- 在 **偏好设置 → 离线 OCR 设置** 主动下载 **32.2 MB** 数学模型；之后完全离线。已有离线包可继续使用，APK 不内置权重，不含 Paddle API。
- 移除独立手写侧栏。顶部 **Σ** 启用后，停笔在原笔迹位置生成同色公式块；选中后可 **还原手写** 继续改写，也可直接编辑 LaTeX。原笔迹随笔记保存，移动、缩放、旋转后仍可恢复。
- 图片剪切使用 **剪刀** 图标，原截图入口隐藏。直接在图片上拖出虚线，两侧显示 **30% alpha 的主题色蒙版**；原位确认后成为两个可独立移动、继续切分的图片块。
- 偏好设置可调整快速/均衡/精细模式、停笔等待、CPU 线程和十个学科包的辅助排序。新落笔取消旧任务，识别失败保留手写。
- [v8 操作与验证说明](docs/INLINE_FORMULA_CUT.md) · [v8 测试报告](docs/benchmarks/v8-inline/TEST-REPORT.json) · [离线模型及学科包](https://github.com/Feist-totoo/xnotes-latex/releases/tag/v0.9.8-latex.5-full)。
- **2523 项 JVM 测试、19 项 Android 断网回归通过**，包含真实手写回放、同色渲染、保存重开还原和原位切图。尚无三星/S Pen 真机验证。
- 本次没有训练或替换模型；v7 的 160 条真实高数/线代手写结构匹配为 **95/160（59.4%）**，精细模式热运行引擎中位数约 **1.51 秒**，默认停笔等待另加 550 ms。该历史结果来自 Android 模拟器，不能代替平板实测，仍需逐式校对。[历史基准](docs/benchmarks/v7-offline-sidebar/)。
- 代码 AGPL-3.0，保留原 xnotes MIT 署名。模型/运行库许可证随应用附带；高数、线代统计数据为 CC BY-NC-SA 4.0。测试笔迹与用户截图不随 APK 分发。
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
