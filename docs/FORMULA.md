# xnotes LaTeX：Paddle API 轻量版

当前分支是 v6 轻量版，版本 `0.9.8-latex.6-paddle-lite`、versionCode 75、包名 `com.xnotes.latex.debug`。基于 [xnotes Android](https://github.com/shardulvs/xnotes-android) 的白板、PDF 批注、分页笔记和无限画布，新增套索公式转换。历史离线实现见 [v5.1 源码标签](https://github.com/Feist-totoo/xnotes-latex/tree/v0.9.8-latex.5.1-full)。

## 使用

首次启动选择笔记位置（Use App Storage 可使用应用内存储），新建笔记或无限画布。

1. 用套索圈选一个表达式：全部是笔画，或单张公式图片。分页笔记中选区应在同一页。
2. 点 **∑（转换 / 编辑公式）**，打开选区与公式预览。
3. 首次使用点 **Paddle API 设置**，填入自己的 AI Studio Access Token，保留默认地址即可。保存配置不会上传。
4. 点 **上传选区到 Paddle 识别**，联网识别当前选区。返回后点 **使用 Paddle 结果**，检查预览并修改 LaTeX。没有网络时可以直接手动输入 LaTeX。
5. 点 **插入公式块**，替换选中的笔迹；也可勾选保留原内容，把公式放在下方。
6. 使用选择工具拖动、缩放公式块。重新选择公式块后点 ∑ 可再次编辑；转换、移动和编辑均可撤销。

API 结果不会自动覆盖已有编辑。关闭窗口或停止等待会取消客户端任务，无法撤回已经发往 AI Studio 的选区。失败和取消不删除原内容。令牌在本机用 Android Keystore AES-GCM 加密，不进入笔记、导出设置或系统备份。

公式源码与透明 PNG 缓存保存在 `.xnote` / `.xcanvas`，重新打开无需联网识别。公式块可移动、缩放、旋转和再次编辑；禁止裁剪、镜像，避免显示与源码含义不一致。PDF 导出使用缓存图片。

十个学科包均已内置，提供符号、模板和校对提示，可独立启用、卸载、恢复、导入与通过 GitHub 更新。推荐依据当前 LaTeX；这些包不改变 Paddle API 的识别结果。

## 构建

需要 JDK 21 或 22（本次使用 22）、Android SDK 36、NDK 27.0.12077973、CMake 3.22.1，以及仓库所需的原生 PDF/语法库源码。不需要 OCR 模型、ONNX Runtime 或 llama.cpp。

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Windows 使用 `gradlew.bat`。标准输出为 `app/build/outputs/apk/debug/app-debug.apk`。增量打包可能留下旧 ZIP 空洞；发布前使用 `tools/compact_apk.py` 复制有效条目到新文件，再运行 Android `zipalign -P 16` 和 `apksigner`，沿用先前签名。

```sh
python tools/compact_apk.py app/build/outputs/apk/debug/app-debug.apk build/lite-unsigned.apk
# zipalign 和 apksigner 生成签名 APK 后：
python tools/verify_paddle_lite.py path/to/signed.apk
```

Gradle 会生成不含历史 OCR 资源的资产目录，开发机器上的旧模型不会被打包。`verify_paddle_lite.py` 检查模型、推理库、旧识别器类和私有测试数据均不存在，十个学科包与白板原生库仍在。

`./gradlew :app:connectedDebugAndroidTest` 可执行设备测试；`ReleasePackTest` 需要网络，其他当前测试可在飞行模式运行。Paddle 协议和交互测试使用模拟响应，不需要真实令牌，不代表云端识别准确率。

## 升级、验证与许可

[轻量版完整说明](FORMULA_PADDLE_LITE.md)记录体积、测试、覆盖升级和旧模型缓存清理范围。APK 可覆盖先前本 fork 调试版本；不要先卸载，以免删除应用内部笔记。

本 fork 代码 AGPL-3.0，原 xnotes MIT 署名保留于 `LICENSE-XNOTES`。高数、线代包内历史统计数据来自 Google MathWriting HUMAN TRAIN，遵循 CC BY-NC-SA 4.0；轻量版不使用其候选评分。其他资源保留各自许可，测试笔迹、图片与用户截图不随应用分发。
