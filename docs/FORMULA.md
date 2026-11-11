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

- 使用 [Texo / FormulaNet](https://github.com/alephpi/Texo) 的 ONNX encoder + cached decoder，通过 ONNX Runtime Android **在设备 CPU 上**运行。
- 两个模型文件合计约 80 MB（十进制）。模型与词表直接打包进 APK；首次使用只从 APK 解包到应用私有目录，不联网下载。
- 没有新增网络权限、云端 API、账号或订阅。原笔迹不上传。
- 输入只包含被圈选的笔画，统一渲染成黑字白底；不包含网格、PDF 背景或未选中的文字。
- 一次适合圈选一个公式，不是整页中文板书 OCR，也不负责推导、求解或判断数学是否正确。
- 手写 OCR **不能保证每个符号正确**。低 token 置信度只是一项提示，不是校准后的正确率。正负号、上下标、分数结构必须检查。所有结果都先预览，用户确认后才改变笔迹。
- 支持当前排版器能理解的数学命令；未知命令、未配对括号或环境会阻止插入。过长公式、超时或无笔迹会安全失败，并允许手动输入。
- 当前图片缓存上限为边长 3072 像素 / 400 万像素。可自由缩放，但极度放大会看到栅格边缘。

## 构建

需要 JDK 17、Python 3.10+、Android SDK 36、NDK 27.0.12077973、CMake 3.22.1。工具链版本沿用上游。将 `ANDROID_HOME` 或 `local.properties` 指向 SDK。

```sh
python tools/prepare_formula_model.py
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows 使用 `gradlew.bat`。模型下载脚本固定 Hugging Face revision，并校验三个文件的 SHA-256。模型权重不进 Git；缺少资源时构建会明确失败，防止产生无法离线识别的 APK。

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，包名 `com.xnotes.latex.debug`，可以和原版并存。它是开发构建，不是上游或 F-Droid 的官方版本。

Android 真机或模拟器测试（包含真实 OCR 推理，不使用识别 mock）：

```sh
./gradlew :app:connectedDebugAndroidTest
```

## 实现

- `FormulaDraft`：冻结选中的笔迹、生成识别图片、排版预览、构造公式块。
- `LocalFormulaRecognizer`：原生 ONNX 推理、缓存解码、取消与超时处理、模型完整性校验。
- `FormulaDialog`：原笔迹 / 公式对照、LaTeX 编辑、保留笔迹、确认后提交。
- `ImageItem.formula`：保存不可变 `FormulaSource`，包含 LaTeX、颜色和字号。原生对象处理移动与缩放。
- `FormulaSelectionState`：确认前检查文档、对象、笔迹及样式是否改变，防止异步结果覆盖新内容。
- 两种文件格式都增加可选的 `latex`、`latex_rgba`、`latex_size` 字段。普通图片的格式不变。旧版可以显示缓存图片，但经旧版保存后可能丢失编辑公式所需的扩展字段。

## 来源与许可

此 fork（含新增集成和打包模型）按根目录的 **AGPL-3.0** 许可发布。原始 xnotes 代码的 MIT 许可与作者署名保存在 [LICENSE-XNOTES](../LICENSE-XNOTES)。Texo / FormulaNet 模型也采用 AGPL-3.0；许可文本、作者与源码地址一并包含在 APK 中。

- 模型：[alephpi/FormulaNet](https://huggingface.co/alephpi/FormulaNet/tree/b2668efe5112082846fde4d446b9bfaab3989533/onnx)
- 训练与导出源码：[alephpi/Texo](https://github.com/alephpi/Texo)
- 预处理与缓存解码参考：[alephpi/Texo-web](https://github.com/alephpi/Texo-web)
- 完整模型许可：[LICENSE-AGPL-3.0.txt](../app/src/main/assets/formula/LICENSE-AGPL-3.0.txt)
- APK 内也包含 `formula/NOTICE.txt` 和完整模型许可；关于页面提供模型与本 fork 源码链接。
- ONNX Runtime：[Microsoft，MIT](https://github.com/microsoft/onnxruntime)

模型权重未修改。模型大小、评测数据集成绩与作者宣传不能替代目标三星平板上的延迟、耗电、S Pen 和真实教师笔迹验证。

## 已完成的验证

2026-11-12，在 Windows / JDK 17 上构建调试 APK；Android 14（API 34）x86_64 模拟器，1920 × 1200、240 dpi。

- `:app:testDebugUnitTest`：**2501 项通过，0 失败**。包括两个文件格式的公式源码 / 图片缓存往返、复制移动、撤销重做、保留原笔迹、陈旧选区拒绝以及插入空白不切割公式。
- `:app:connectedDebugAndroidTest`：**4 项通过，0 失败**。真实套索手势、真实离线 ONNX 识别 `X = 1`、预览、插入、手势拖动、编辑为分式、连续撤销恢复笔迹；另测取消、未知命令拒绝、分式 / 上下标 / 积分 / 矩阵渲染，以及无限画布提交与撤销。
- 截图来自实际运行的 APK：[识别预览](screenshots/formula-dialog.png)、[可移动公式块](screenshots/formula-block.png)、[再次编辑](screenshots/formula-edit.png)。

这些是功能回归和少量样例验证，不是识别准确率评测。尚未在三星平板 / S Pen 真机上测量延迟、耗电和课堂笔迹表现。
