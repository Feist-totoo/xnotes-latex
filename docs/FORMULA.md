# xnotes LaTeX：套索手写转公式

基于 [xnotes Android](https://github.com/shardulvs/xnotes-android) 的独立 fork，面向 Android 平板教学白板。保留原有原生笔迹、PDF 批注、分页笔记和无限画布，在套索选择菜单中增加公式转换。

## 使用

首次启动先选择笔记存储位置（**Use App Storage** 可使用应用内部存储），再新建笔记或无限画布。

1. 写完一个数学公式，用 **套索（Lasso）** 自由画线圈住它。圈选一个表达式：全部是笔画，或选择单张公式图片；分页笔记内应位于同一页。
2. 点选择工具条中的 **∑（转换 / 编辑公式）**。
3. 在本机识别后，对照原笔迹与排版预览。可以直接修改 LaTeX 源码；原始笔画可另点“离线精细复核”，检查第二模型结果后点“使用精细结果”。复核不会覆盖手动输入。没有有效预览时不能插入。
4. 点 **插入公式块**，选中的原内容会替换为一个公式块。也可以勾选保留原内容，把公式放在下方。
5. 使用选择工具拖动公式块或拖拽缩放手柄。重新选中公式块，再点 ∑ 可编辑源码。
6. 转换、移动、修改分别进入撤销栈。取消或识别失败不会删除笔画。

公式源代码和透明高分辨率 PNG 缓存一起写入 `.xnote` / `.xcanvas`。重新打开不需要再次识别；复制、粘贴、拖动、旋转、缩放沿用原生图片对象的操作。公式块禁用裁剪和镜像，避免显示缓存与源码含义不一致。PDF 导出使用缓存图片，因此公式显示正常，但不是 PDF 中可选取的矢量数学文本。

## 本地 OCR 与准确性

`0.9.8-latex.5-full` 内置两个共享调度的本地 CPU 引擎：原始笔画用 [GryphOne](https://github.com/JG1VPP/GryphOne) Q8 ONNX，图片与手动精细复核用 [GLM-OCR](https://huggingface.co/ggml-org/GLM-OCR-GGUF) Q4_K_M / Q8_0 GGUF，通过 JNI 直接运行。识别不依赖 HTTP、云服务或局域网主机。网络只用于主动刷新 / 下载 GitHub 学科包。

四份权重共 940,331,421 字节。首次使用从 APK 校验、解包到不参与云备份的私有目录，因此首次使用比模型复用慢，安装及首次运行也需要额外可用存储。完整 APK 为 1,013,056,713 字节。支持 Android 8.0+、arm64-v8a / x86_64。后台串行推理，两种引擎切换前释放另一种，空闲 30 秒或内存紧张时释放。取消不提交公式。

套索原始笔画保留擦除后分段，并按训练方式绘制为 224×224 单像素中心线，不受笔色、笔压、粗细和纸张网格干扰。图片输入冻结当前裁剪、旋转和镜像；替换后的公式正常排版。一次选择一个表达式，快速模型最多 150 位置，不适合整页或很长的推导。精细模型也可能误认大小写、括号或结构；第二结果不是自动正确答案。

十个包全部随完整 APK 提供：高中数学、高数、线性代数、概率统计、离散数学、力学、电磁学、热学、波动光学、量子物理。它们独立安装、启停、更新、卸载和导入，卸载后可从 APK 本地恢复；选择会记忆，升级保留已禁用 / 已主动卸载的包。只有包更新需要网络。

高数、线代 v3 包使用 HUMAN TRAIN 的符号序列统计，只重排视觉评分接近的现有候选，不生成教材公式或重新识图。首次安装默认开启，已有明确关闭设置保留。可以在学科面板关闭候选辅助。自动推荐复用识别文本特征，不额外加载识图模型；用户编辑和明确选择的候选不会被包切换覆盖。其余八个包仍只有符号、模板和校对辅助。

[完整 v5 验证报告](FORMULA_ACCURACY_V5.md) 保留失败与消融结果：开发机原混合集 104/200，Android 新增 160 条 95/160。旧高数、线代专项集 116→117/200，新集没有包收益。v5 图片集 56/100，与 v3 的 56/100 相同。发布为完整离线预览，不承诺一般本科公式准确率；必须对照原内容核对。

## 构建

需要 JDK 17、Python 3.11+、Git、Android SDK 36、NDK 27.0.12077973、CMake 3.22.1。

```sh
python tools/prepare_full_formula.py
python tools/package_formula_mods.py
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows 使用 `gradlew.bat`。准备脚本在开发机器下载固定 Release 的 ONNX / GGUF 模型并校验 SHA-256，准备固定版本的 llama.cpp。应用本身不下载模型，只在用户操作时下载独立学科包。旧 Texo 模型不再进入 APK，旧评测脚本仅为复现基线保留。

输出 `app/build/outputs/apk/debug/app-debug.apk`，包名 `com.xnotes.latex.debug`，versionCode 73。沿用旧版调试签名，可覆盖此前本 fork 的调试安装。不要先卸载旧版，以免删除应用内存储的笔记。

真实笔迹测试准备（只进入 androidTest APK，不进入应用 APK）：

```sh
python tools/handwriting_benchmark.py fetch --destination build/handwriting
python tools/handwriting_benchmark.py fixtures --data build/handwriting/mathwriting-test
# 另将 build/releases/*.xformula 复制到 app/src/androidTest/assets/packs/ 供包测试使用
./gradlew :app:connectedDebugAndroidTest
```

私有截图回归为本地可选 fixture，不能公开分发。通用测试不依赖这张截图。

## 实现与来源

- `FormulaDraft` 冻结套索笔画或单张图片的可见几何，提交前核对选区是否变化。
- `FormulaInkRaster` 保留原始中心线几何；桌面与 Android 逐样本核对输入像素哈希。
- `LocalFormulaRecognizer` 校验本地模型并串行调度可取消的 ONNX 工作。编码器一次计算并缓存跨注意力特征，多个候选共用图像特征。
- `PrecisionFormulaRecognizer` 和 JNI 桥接调用固定 llama.cpp；共同推理锁避免两套权重同时驻留。
- `FormulaRecognitionPrior` 只使用兼容模型词表的有界统计数据；不执行下载包中的代码。
- `FormulaDialog` 提供原笔迹/预览、候选选择、光标编辑、保留笔迹、学科包选择与确认插入。
- `.xnote` / `.xcanvas` 保留公式源码；公式块可拖动、缩放、旋转、编辑、撤销。PDF 导出使用栅格缓存。

本 fork 代码继续按 AGPL-3.0 发布，原 xnotes MIT 署名保留在 `LICENSE-XNOTES`。GryphOne 上游为 MIT，DINO 为 Apache-2.0，ONNX Runtime 为 MIT；固定版本、哈希和署名在 `app/src/main/assets/formula-gryph/`。GLM-OCR 与 llama.cpp 为 MIT，固定版本、转换方式与署名在 `formula-precision/`。本次量化及移除未参与普通解码的 NextN 层，没有训练或微调神经网络权重。

**高数、线代的统计数据资源**从 Google LLC 的 MathWriting 2024 HUMAN TRAIN 标签派生，遵循 **CC BY-NC-SA 4.0**（署名、非商业、相同方式共享），不是 MIT。验证/测试样本未参与统计训练；原始轨迹、测试图片和用户截图不随应用发布。其余编辑资源与应用代码遵循各自现有许可。

没有三星平板 / S Pen 真机测试数据。模拟器性能不作为目标平板的性能承诺。
