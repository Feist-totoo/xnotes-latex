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

`0.9.8-latex.3` 使用 [GLM-OCR](https://huggingface.co/zai-org/GLM-OCR) 的 Q8 权重，通过 [llama.cpp](https://github.com/ggml-org/llama.cpp) C++ / JNI 在设备 CPU 上运行。模型与分词器随 APK 安装，识别全程在平板上完成。网络权限只用于用户主动从 GitHub Releases 下载独立学科包；不使用云端 OCR 或局域网服务。

模型文件合计 1,434,837,056 字节；APK 约 1.49 GB。首次使用将模型校验、解包到不参与云备份的应用私有目录，额外占用约 1.44 GB。安装和首次解包请预留约 5 GB 可用空间。仅支持 64 位 Android（arm64-v8a、x86_64），Android 8.0 及以上。不同平板的速度、内存压力尚需真机验证；4 GB RAM 模拟器可以运行，但有明显内存压力，建议使用 6 GB 或更多内存的设备。

输入仅含选中的笔画，黑字白底，不含纸张网格、PDF 背景或相邻内容。一次圈选一个表达式。模型不会求解或判断等式是否成立；识别结果必须对照预览。超过长度、内存、时间或排版器支持范围时不替换笔迹。取消后保持原笔迹，native worker 在安全点退出并释放模型。

10 个学科包可以独立下载、安装、启停、更新、卸载和导入，选择会记忆：高中数学、高等数学、线性代数、概率统计、离散数学、力学、电磁学与电路、热学与统计物理、振动波动与光学、近代与量子物理。高中数学包随 APK 提供，其余包在“管理 / 下载”中从 GitHub Releases 获取，下载后可断网使用。自动选包复用本机识别结果，可关闭；未知类别保持通用辅助。展开“学科包”后可在编辑框光标处插入符号，也可明确确认后套用模板，并查看匹配学科的校对提示。这些是**离线编辑与词表辅助包**，共用一个识别模型；没有宣称训练了十个专用模型，也不自动把陌生写法改成教科书公式。

真实手写的旧/新模型对照、错误、数据限制和复现命令见 [识别验证报告](FORMULA_ACCURACY.md)。不能把排版成功率或模型厂商的文档 OCR 成绩当作本应用手写整式准确率。

## 构建

需要 JDK 17、Python 3.11+、Git、Android SDK 36、NDK 27.0.12077973、CMake 3.22.1。

```sh
python tools/prepare_glm_formula.py
python tools/package_formula_mods.py
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows 使用 `gradlew.bat`。准备脚本在开发机器下载固定 revision 的模型、llama.cpp 源码，并校验模型 SHA-256。应用本身不下载模型，只在用户操作时下载独立学科包。旧 Texo 模型不再进入 APK，旧评测脚本仅为复现基线保留。

输出 `app/build/outputs/apk/debug/app-debug.apk`，包名 `com.xnotes.latex.debug`，versionCode 71。沿用旧版调试签名，可覆盖此前本 fork 的调试安装。不要先卸载旧版，以免删除应用内存储的笔记。

真实笔迹测试准备（只进入 androidTest APK，不进入应用 APK）：

```sh
python tools/handwriting_benchmark.py fetch --destination build/handwriting
python tools/handwriting_benchmark.py fixtures --data build/handwriting/mathwriting-test
# 另将 build/releases/*.xformula 复制到 app/src/androidTest/assets/packs/ 供包测试使用
./gradlew :app:connectedDebugAndroidTest
```

私有截图回归为本地可选 fixture，不能公开分发。通用测试不依赖这张截图。

## 实现与来源

- `FormulaDraft` 冻结套索笔画，产生黑白输入和透明公式图片，提交前核对选区是否变化。
- `LocalFormulaRecognizer` 校验本地模型、转换 RGB、串行调度可取消 JNI 工作；短时复用模型，空闲 30 秒或内存紧张时释放。
- `formula_jni.cpp` 直接调用 llama / mtmd CPU 库，使用固定公式识别提示、greedy 解码和 512 token 上限。推理超时为 180 秒，不返回截断的公式。
- `FormulaDialog` 提供原笔迹/预览、光标编辑、保留笔迹、学科包选择与确认插入。
- 公式源码随 `.xnote` / `.xcanvas` 保存；可拖动、缩放、旋转、编辑、撤销。PDF 导出使用栅格缓存。未知数学命令会阻止插入。

本 fork 继续按根目录 AGPL-3.0 发布。原 xnotes MIT 署名保留在 `LICENSE-XNOTES`。新模型 GLM-OCR 和 llama.cpp 均采用 MIT；固定版本、哈希和许可在 `app/src/main/assets/formula-glm/`。未训练或微调这些权重。Google MathWriting 仅用于测试，不作为训练数据，不随应用发布。

没有三星平板 / S Pen 真机测试数据。模拟器性能不应作为目标平板的性能承诺。
