# v5.1：LaTeX 空格修复与可选 Paddle AI Studio

`0.9.8-latex.5.1-full`，versionCode 74。完整 APK 保留 v5 的四份离线模型与十个学科包。模型权重和候选评分参数没有变化；这次不宣称离线 OCR 准确率提高。

## 修复空格

识别模型按 token 输出的空格，曾被原生解析器当作可见的 Space 节点。现在同时修正源码清理和原生解析：例如 `1 2 . 3 4 x` 输出为 `12.34x`；`e ^ { \arctan ( 1 + x ) }` 去除无意义空格。

保留 `\sin x` 等命令必需的分隔符，但不绘制为可见空格。保留 `\text{for all}` 的正文空格、注释换行以及 `\,`、`\quad`、`\ `、`~` 显式间距。按数学字符解析紧凑文本，避免 `ab^2` 被错误解释成整串 ab 的上标。旧公式的 PNG 缓存不会批量重写；重新选择旧公式，进入编辑后保存即可重新排版。

## Paddle 使用方法

1. 圈选笔画或单张公式图片，打开公式转换。
2. 点 **Paddle API 设置**，通过链接在 [AI Studio 令牌页面](https://aistudio.baidu.com/account/accessToken) 获取自己的 Access Token，并在应用内填写。令牌不需要发送给开发者。
3. 默认 API 地址和 PaddleOCR-VL-1.6 可直接保留，也可选 1.5。若使用官网 API 面板提供的专属地址，粘贴以 `/layout-parsing` 结尾的 HTTPS 地址；这种地址使用该服务本身的模型，忽略模型选项。
4. 保存配置不会上传。主动点 **上传选区到 Paddle 识别** 才发送当前选区，随后点击 **使用 Paddle 结果** 才替换编辑框。若离线初识别仍在运行，点 API 会取消该次本地任务。
5. 检查预览并编辑，按原流程插入可移动公式块。无有效预览不能插入。删除 API 配置后仍可完整离线使用。

这是可选云端方案，需要网络和用户自己的服务权限 / 额度，不属于离线识别。默认路径仍完全在平板本地运行。令牌用 Android Keystore AES-GCM 加密，存于不参与系统备份的目录，不写入笔记、导出设置或日志。云端只接收当前选区的 PNG，长边最多 2048 像素；不上传整页、原始笔画坐标、笔记名称或学科包数据。

请求只提交一次；异步轮询不重复上传，最多等候 180 秒。停止等待、关闭窗口会取消客户端任务，但不能撤回已发往 AI Studio 的内容。API 不会自动启用、作为本地失败后的后台回退，也不会覆盖手工编辑。API 的返回结果暂不参与本地学科包候选重排。

## 协议依据

按 2026-11-12 查阅的官方资料实现：

- [PaddleOCR 官方 API 概览](https://www.paddleocr.ai/latest/en/version3.x/inference_deployment/serving/paddleocr_official_api/overview.html)。
- [官方 HTTP 客户端](https://github.com/PaddlePaddle/PaddleOCR/blob/main/paddleocr/_api_client/_http.py)：默认 `https://paddleocr.aistudio-app.com/api/v2/ocr/jobs`，Bearer 认证、multipart 文件提交、状态轮询与 JSONL 结果；预签名结果下载不携带令牌。
- [官方轮询 / 结果解析](https://github.com/PaddlePaddle/PaddleOCR/blob/main/paddleocr/_api_client/_poller.py)：`data.jobId`、`data.state`、`resultUrl.jsonUrl`、`result.layoutParsingResults[].markdown.text`。
- [AI Studio PaddleOCR-VL 接口文档](https://ai.baidu.com/ai-doc/AISTUDIO/Cmkz2m0ma)：专属服务 URL 使用 `Authorization: token …`、base64 `file`、`fileType: 1`。设置 `useLayoutDetection: false`、`promptLabel: formula`，不请求可视化图片或方向 / 去畸变处理。

仅接受官方 AI Studio HTTPS 主机，不跟随重定向；结果下载只接受官方 AI Studio / BCE BOS HTTPS 主机且不携带认证头。响应有大小限制，多页 / 多公式结果拒绝静默拼接。结果统一经过空格清理和现有原生预览校验。

## 本地验证

- 2516 项 JVM 测试通过，包含官方异步 / 专属同步协议、认证错误、额度限制、取消、凭据边界及结果解析。协议使用模拟传输，不调用真实云端服务。
- 1525 条已有真实手写评测 LaTeX 去重回归：1510 条原本可解析的公式清理前后 MathML 结构全部一致；15 条原本不可解析仍不可解析，不计作成功。实际调用编译后的 Kotlin 清理器。[结果](benchmarks/v5.1/spacing-corpus.json)。
- 13 项 Android 检查通过：两项 Paddle 配置 / 交互、六项公式工作流、四项离线模型 / 学科包和一项图片裁剪冻结。最终签名 APK 已覆盖安装并验证。键盘弹出后测试先按返回收起键盘，再滚动检查完整预览。
- Android 原生逐像素检查九组公式的空格等价性，另验证显式间距、正文空格和上标的基字符。
- 断网运行原有 12 条公开手写图片回归，整式结构匹配仍为 **1/12**，不能将运行成功视为识别正确。[逐条结果](benchmarks/v5.1/android-public-regressions.json)。本次不调整模型，失败保留；没有把 Paddle 模拟结果计入准确率。

## 验证边界

没有用户 AI Studio 令牌，**未实测云端服务的手写准确率、端到端耗时或账户额度**。协议和交互测试使用固定模拟响应，不能当作 Paddle 实际 OCR 测试。后续若需要比较准确率，应使用同一组有明确标签且允许上传的真实手写样本，单独记录服务模型版本与结果。

本次修复不改变 [v5 已公开的准确率与限制](FORMULA_ACCURACY_V5.md)。继续作为预览版发布。
