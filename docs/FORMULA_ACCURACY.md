# 离线手写公式 v3：实现与验证

2026-11-12。`0.9.8-latex.3` 已把旧 Texo ONNX 引擎替换为 GLM-OCR Q8 / llama.cpp JNI，加入可独立下载的学科包、离线自动选包和模型短时复用。**本次有可测的提升，但还没有达到“大部分任意数学、物理手写公式都可靠”的目标。**

## 实际识别结果

指标为保守的 **MathML 结构匹配**：忽略无意义空格、单层包装、分隔符大小等排版差异，保留符号、大小写、上下标、分式顺序、矩阵结构、向量/重音和数学字体。不使用 CAS 求解，不把“能渲染”算作正确。它仍会低估某些等价写法，不能直接当作人工审核的准确率。

| 未用于本次模型选择的样本 | 旧 Texo | 新 GLM-OCR |
|---|---:|---:|
| MathWriting 官方 excerpt/test，100 条真实人类笔迹 | 8/100 | **33/100** |
| UniMER-Test HWE，第 100–199 条真实手写图片 | 46/100 | **56/100** |
| 两组总计 | 54/200 | **89/200** |

逐条预测（包括失败）和数据指纹在 [benchmarks/v3](benchmarks/v3/README.md)。没有挑选“成功图片”来计算这些成绩。两组数据不是十门学科的分层课堂测试集，也不能证明样本没有进入上游模型的预训练。

模型选择使用另一个 100 条 MathWriting `valid` 笔迹：旧引擎 11/100，测试的 UniMERNet-small 社区 Q8 ONNX 11/100，新 GLM-OCR 37/100。额外加粗输入只达到 38/100，收益不足以默认改变笔迹，因此最终保留原输入和模型原生预处理。社区导出没有完成所有上游实现的数值对齐，这些数字不是官方模型排行榜。

用户提供的失败截图只在本地裁出原笔迹，旧引擎错认的 `avc tan` 在新版实际 Android JNI 上输出为 `e^{\arctan(1+x)}`。图片未提交到仓库、未上传识别服务。截图与原始 S Pen 轨迹不同，单个回归通过不代表广泛准确性。

仍可见的错误包括 `v/ν`、`u/v`、大小写、希腊字母、漏下标以及长结构。要进一步达到高整式准确率，需要针对课堂原始笔迹做训练/适配和按书写者、学科分层的保留测试；本次没有假称完成这部分训练。

## 真实笔迹与离线验证

- MathWriting 输入来自原始 InkML 的真实 `human` 轨迹，不是字体渲染或人工伪造笔迹。固定以 128 px 高度、1.5 px 笔宽、4 倍抗锯齿渲染；生成脚本与使用的 PNG 逐像素对齐。
- Google MathWriting 的 CC-BY-NC-SA 4.0 数据仅用于验证，没有训练模型，也没有放入应用 APK。
- Android 14 x86_64 模拟器，2 vCPU、4 GiB RAM。Wi-Fi 和移动数据关闭后运行 JNI。12 条公共笔迹加用户回归共 13 次实际识别，模型复用前后 **13/13 输出相同**，原生输出与桌面基线去除排版空白后对齐。该固定公共子集只有 1/12 与参考结构完全匹配，用于运行时对齐，不能作为高准确率证明。
- 10 个包的模板解析、版本化安装、启停、卸载、持久化以及套索、预览、插入、移动、编辑、撤销分别测试。JVM 共 **2505 项通过，0 失败**。UI 用的 `X=1` 坐标 fixture 是合成的交互测试，不进入真实手写准确率统计。
- 可选联网测试从真实 GitHub Release 下载量子物理包，校验后安装，在本机进行选包与启停、卸载，独立于 OCR 运行。普通离线回归不依赖联网。
- 最终签名 APK 覆盖安装后，5 项 UI 回归再次通过；另外 3 项原生/存储测试、1 项真实 Release 下载测试通过。[发布校验记录](benchmarks/v3/release-verification.json) 包含 APK 哈希。最终复测前关闭了模拟器启动阶段遗留的 System UI 无响应弹窗；复测期间没有新增 ANR 事件。这不等于低内存真机认证。

## 速度与效率

采用单 worker 串行推理，复用已加载的视觉编码器与解码器；每个表达式清空 KV 状态，防止上一题污染下一题。空闲 30 秒、进入后台或收到内存紧张通知时释放缓存；取消和错误会安全释放当前工作。学科选择复用已有 OCR 输出，不增加一次大模型调用，切换包也不重新识图。

同一回归图片，在同一模拟器中测得：

| 次序 | 总耗时 | 加载/清缓存 | 图像编码及提示处理 | 公式解码 |
|---|---:|---:|---:|---:|
| 冷启动 | 9.320 s | 1.491 s | 7.111 s | 0.637 s |
| 复用模型 | **7.623 s** | 0.011 s | 6.995 s | 0.611 s |
| 显式释放后重载 | 9.239 s | 1.526 s | 7.023 s | 0.611 s |

复用时约快 18%，三次识别结果一致。结果来自一次冷/热/冷小样本测量，不是三星平板的速度承诺。初次从 APK 解包模型另需时间。[原始计时](benchmarks/v3/android-speed.json)

当前主要瓶颈是图像编码。后续应优先在目标平板验证视觉编码器的 Vulkan/NNAPI 等可用后端，以及保留细小上下标的自适应输入尺寸；每个优化都必须重新跑固定笔迹测试。Q4 权重、更低分辨率和复杂候选裁剪可能降低精度，本版没有为了速度盲目启用。离线短时缓存也增加了驻留内存，低内存设备会主动退回冷加载。

## 学科包与自动选择

10 个独立 `.xformula` 包通过 [GitHub Releases](https://github.com/Feist-totoo/xnotes-latex/releases/tag/formula-packs-v1.0.0) 托管：高中数学、高等数学、线性代数、概率统计、离散数学、力学、电磁学与电路、热学与统计物理、振动波动与光学、近代与量子物理。

包带版本、大小、SHA-256、适用应用版本。可单独下载、安装、启停、更新、卸载和本地导入。基础识别及随 APK 提供的高中数学包不需要下载；其余包下载一次后离线使用。网络访问仅用于用户主动获取 Release 目录和包文件，不传送笔迹。

这些是**词表、编辑、校对与路由辅助包**，不是十个训练好的专用识别模型。自动路由依据本机 OCR 的 TeX token 特征，最多推荐三个包；例如 `\hbar`、`\psi` 指向量子物理，`\nabla`、电磁场向量指向电磁学。UI 显示匹配依据，不把分数当正确率。判据不足时沿用手动通用辅助，也可以关闭自动选包。选择包不改写原公式。

格式、扩展方法和边界见 [formula-packs/README.md](../formula-packs/README.md)。包是有界 JSON 数据，不执行第三方代码，不动态下载或调用网络识别模型。

## 复现

```sh
python -m pip install -r tools/requirements-formula-eval.txt
python tools/prepare_glm_formula.py
python tools/package_formula_mods.py
python tools/handwriting_benchmark.py fetch --destination build/handwriting
python tools/prepare_formula_model.py  # 仅历史 Texo 基线
python tools/evaluate_formula_ocr.py evaluate --data build/handwriting/mathwriting-test --output build/texo-test.json
```

桌面 GLM 对照使用与 APK 相同的 llama.cpp b9870、Q8 权重和 `Formula Recognition:` 提示。仅在开发机器启动回环地址测试进程，**APK 使用 JNI，不需要这台电脑或该进程**：

```sh
llama-server -m app/src/main/assets/formula-glm/GLM-OCR-Q8_0.gguf --mmproj app/src/main/assets/formula-glm/mmproj-GLM-OCR-Q8_0.gguf -ngl 0 --no-mmproj-offload -t 4 -c 4096 -np 1 --host 127.0.0.1 --port 18086 --no-warmup
python tools/handwriting_benchmark.py evaluate --data build/handwriting/mathwriting-test --output build/glm-test.json
npm ci --prefix tools/formula_metrics
python tools/score_formula_structure.py build/texo-test.json build/glm-test.json
python tools/test_formula_metric.py
python tools/handwriting_benchmark.py fixtures --data build/handwriting/mathwriting-test
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

模型哈希与来源见 `app/src/main/assets/formula-glm/manifest.json`；数据哈希见报告目录。测试图片、私有回归图片、模型权重和测试 APK 不提交 Git。没有三星 S Pen 实机测量，不声明耗电或全学科准确率达标。
