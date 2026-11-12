# xnotes LaTeX：套索手写转公式

基于 [xnotes Android](https://github.com/shardulvs/xnotes-android) 的独立 fork，面向 Android 平板教学白板。保留原有原生笔迹、PDF 批注、分页笔记和无限画布，在套索选择菜单中增加公式转换。

## 使用

首次启动先选择笔记存储位置（**Use App Storage** 可使用应用内部存储），再新建笔记或无限画布。

1. 写完一个数学公式，用 **套索（Lasso）** 自由画线圈住它。圈选内容应全部是笔画，分页笔记内应位于同一页。
2. 点选择工具条中的 **∑（转换 / 编辑公式）**。
3. 在本机识别后，对照原笔迹与排版预览。可以直接修改 LaTeX 源码；没有有效预览时不能插入。
4. 点 **插入公式块**，选中的笔画会替换为一个公式块。也可以勾选保留笔迹，把公式放在下方。
5. 使用选择工具拖动公式块或拖拽缩放手柄。重新选中公式块，再点 ∑ 可编辑源码。
6. 转换、移动、修改分别进入撤销栈。取消或识别失败不会删除笔画。

公式源代码和透明高分辨率 PNG 缓存一起写入 `.xnote` / `.xcanvas`。重新打开不需要再次识别；复制、粘贴、拖动、旋转、缩放沿用原生图片对象的操作。公式块禁用裁剪和镜像，避免显示缓存与源码含义不一致。PDF 导出使用缓存图片，因此公式显示正常，但不是 PDF 中可选取的矢量数学文本。

## 本地 OCR 与准确性

`0.9.8-latex.4-preview` 的原始笔画路径使用 [GryphOne](https://github.com/JG1VPP/GryphOne) 的 Q8 ONNX 权重，ONNX Runtime 直接在设备 CPU 上运行。模型随 APK 安装，识别全程在平板上完成。网络权限只用于用户主动从 GitHub Releases 下载独立学科包；不使用云端 OCR 或局域网服务。

两份模型权重合计 32,226,621 字节，首次使用校验并解包到不参与云备份的应用私有目录。支持 Android 8.0+ 的 arm64-v8a、x86_64。后台串行推理，短时复用模型，空闲 30 秒或内存紧张时释放。升级后首次成功识别会移除旧版可重新获取的 GLM 模型缓存，不涉及笔记文件。

套索输入直接取冻结的可见笔画坐标，保留擦除后的分段，按模型训练方式绘制为 224×224 的单像素中心线；不受笔色、笔压、粗细、纸张网格和页面缩放影响。一次圈选一个表达式。模型不会求解或判断等式是否成立；识别结果必须对照预览。未完成的输出不能插入。用户可选择候选并编辑源码。

10 个学科包可以独立下载、安装、启停、更新、卸载和导入，选择会记忆。高中数学、高数、线代随 APK 提供，首次使用即可断网工作；其他包可从 GitHub Releases 下载。高数、线代 v2 包含从真实训练标签统计得到的识别约束，可对模型已产生的候选重新评分；确认测试未证明稳定净增益，默认关闭，需主动开启实验选项；不增加图像编码，不编造新表达式。用户手改的文字或明确选择的候选不会被切换包覆盖。其余八个包当前只提供符号、模板和校对辅助。

真实手写对照、失败结果、学科包消融、Android 验证和数据限制见 [v4 验证报告](FORMULA_ACCURACY_V4.md)。[v3 历史报告](FORMULA_ACCURACY.md) 保留原始 89/200 的样本及评估口径。数字笔画与相机图片的表现不能混为一谈；不能把排版成功率算作手写整式正确率。

## 构建

需要 JDK 17、Python 3.11+、Git、Android SDK 36、NDK 27.0.12077973、CMake 3.22.1。

```sh
python tools/prepare_handwriting_model.py
python tools/package_formula_mods.py
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows 使用 `gradlew.bat`。准备脚本在开发机器下载固定 Release 的 ONNX 模型，并校验模型 SHA-256。应用本身不下载模型，只在用户操作时下载独立学科包。旧 Texo 模型不再进入 APK，旧评测脚本仅为复现基线保留。

输出 `app/build/outputs/apk/debug/app-debug.apk`，包名 `com.xnotes.latex.debug`，versionCode 72。沿用旧版调试签名，可覆盖此前本 fork 的调试安装。不要先卸载旧版，以免删除应用内存储的笔记。

真实笔迹测试准备（只进入 androidTest APK，不进入应用 APK）：

```sh
python tools/handwriting_benchmark.py fetch --destination build/handwriting
python tools/handwriting_benchmark.py fixtures --data build/handwriting/mathwriting-test
# 另将 build/releases/*.xformula 复制到 app/src/androidTest/assets/packs/ 供包测试使用
./gradlew :app:connectedDebugAndroidTest
```

私有截图回归为本地可选 fixture，不能公开分发。通用测试不依赖这张截图。

## 实现与来源

- `FormulaDraft` 冻结套索笔画，提交前核对选区是否变化。
- `FormulaInkRaster` 保留原始中心线几何；桌面与 Android 逐样本核对输入像素哈希。
- `LocalFormulaRecognizer` 校验本地模型并串行调度可取消的 ONNX 工作。编码器一次计算并缓存跨注意力特征，多个候选共用图像特征。
- `FormulaRecognitionPrior` 只使用兼容模型词表的有界统计数据；不执行下载包中的代码。
- `FormulaDialog` 提供原笔迹/预览、候选选择、光标编辑、保留笔迹、学科包选择与确认插入。
- `.xnote` / `.xcanvas` 保留公式源码；公式块可拖动、缩放、旋转、编辑、撤销。PDF 导出使用栅格缓存。

本 fork 代码继续按 AGPL-3.0 发布，原 xnotes MIT 署名保留在 `LICENSE-XNOTES`。GryphOne 上游为 MIT，DINO 为 Apache-2.0，ONNX Runtime 为 MIT；固定版本、哈希和署名在 `app/src/main/assets/formula-gryph/`。本次没有训练或微调神经网络权重。

**高数、线代的统计数据资源**从 Google LLC 的 MathWriting 2024 HUMAN TRAIN 标签派生，遵循 **CC BY-NC-SA 4.0**（署名、非商业、相同方式共享），不是 MIT。验证/测试样本未参与统计训练；原始轨迹、测试图片和用户截图不随应用发布。其余编辑资源与应用代码遵循各自现有许可。

没有三星平板 / S Pen 真机测试数据。模拟器性能不作为目标平板的性能承诺。
