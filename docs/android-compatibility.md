# Android 设备兼容性

本页保留 **2.0 的兼容基线、实际运行记录和静态审计**；2.0.1 的连接诊断、实际绑定与设备复核见 [连接排查与验证](connection-troubleshooting.md)。2.1 另在 API 24 和 API 37 各完整通过 193 项设备测试，详见 [2.1 验证记录](verification-deepseek-v2.1.md)；这些结果均不能当作全部安卓机型认证。

记录更新日期：2026-10-15；初始静态审计于 2026-10-14 完成。目标是覆盖运行 Android 的常见手机型号，按系统能力提供降级；当前证据不能证明“全部型号全部功能均可用”。Android 7.0（API 24）以下不能安装本版本；只支持 Android APK 的系统，不覆盖不能运行 Android APK 的其他系统。

## 2.1 DeepSeek 接入的新增条件

2.1 测试版增加 `INTERNET` 与用户主动调用的 DeepSeek 助手，最低系统版本和自动识别通道分级保持原设计。需要手机能够访问官方 API、有可用的个人账户／Key，以及系统 AndroidKeyStore 支持安全保存凭据；没有模型 Key 时仍可用固定的本地查询。模型接入不解除厂商后台限制、不授权无障碍，也不让等待系统连接的服务自动变为已连接。

以下 APK 哈希、无网络权限检查、设备通过数量及 16 KB 分析仍属于原 2.0 版本，不是 2.1 的重新验证。2.1 的 SDK 可能利用网络发送诊断指标，不能把 2.0 的离线运行证据扩展为整个新版本无网络通信。配置、独立请求与数据边界见 [DeepSeek 指南](deepseek-agent.md)和[隐私说明](privacy.md)。

## 支持范围与降级矩阵

| 系统 / 环境 | 手动记账、账本、加密备份 | 付款通知 | 页面文字 | 自动截图 OCR | 手动导入截图 |
| --- | --- | --- | --- | --- | --- |
| API 24–29，普通设备 | 源码支持 | 需系统授权并实际连接 | 需无障碍授权及可读取节点 | 系统不提供此接口；禁用开关 | 内置中文模型 |
| API 24–29，low-RAM / Android Go | 源码支持 | 系统不支持此类监听器授权 / 绑定 | 根据系统可读节点降级 | 不可用 | 内置中文模型 |
| API 30–33 | 源码支持 | 需授权、连接及付款通知 | 同上 | 系统整屏截图，裁剪目标窗口；浮窗、键盘或其他应用窗口存在时拒绝 | 内置中文模型 |
| API 34–37 | 源码支持 | 需授权、连接及付款通知 | 系统可隐藏敏感节点 | 目标交易窗口截图；系统禁止截屏时停止 | 内置中文模型 |
| 工作资料夹 / 企业管控 | 以管理员策略为准 | 工作资料夹内监听器被系统忽略，跨资料夹通知可能受策略限制 | 以管理员与系统策略为准 | 同左 | 以文件访问策略为准 |
| 无 Google Play services 的 Android | 不依赖 GMS 下载模型；其他功能按系统接口与权限 | Android 系统接口 | Android 系统接口 | API 35 x86_64 模拟器禁用 GMS、断网后的本地 OCR 已通过；真实手机待验证 | 同左 |

表中“源码支持”表示 API 使用与最低版本配置相符。已运行验证的具体环境和项目见下方记录，不表示整个版本区间或所有机型已经通过。官方规定，通知监听器在 Android Q（API 29）及以下的 low-RAM 设备无法授权或绑定，工作资料夹也有明确限制。应用的本机兼容卡片对此类低内存设备提供提示与手动降级入口。[NotificationListenerService 官方说明](https://developer.android.google.cn/reference/android/service/notification/NotificationListenerService)

自动截屏接口始于 API 30；指定窗口截图始于 API 34。实现依据 `Build.VERSION.SDK_INT` 与服务实际截图能力选择节点、整屏或窗口路径。[AccessibilityService 官方接口](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)

付款通知、页面文字、截图均依赖微信 / 支付宝实际提供的数据。无付款通知、隐藏节点、`FLAG_SECURE`、支付密码页、缺少完成状态或多金额歧义均不能承诺自动入账。未知或含糊证据进入待确认；不会绕过系统安全限制。

## 可复用的打包证据

最终交付包与初始 REA 截面分别记录，不能混用哈希或覆盖范围。

| 最终交付包 | 核验结果 |
| --- | --- |
| 文件 | `deliverables/qingji-v2.0.apk`，57,543,659 字节 |
| SHA-256 | `4681BA82C750AA838B51DAA7CFE9D16AFD2FAC1DB80DF1AD43C268797BE2741D` |
| 签名 | APK v2 签名核验通过；证书 SHA-256 为 `64e32611bd105a2b68a15e44b2c785433274ac3c1f416525f1056c2fc2de0498` |
| ZIP 对齐 | 16 KB ZIP 对齐检查通过；此项不能替代 ELF 或实际运行验证 |

完整交付核验见 [验证记录](verification.md)。本轮兼容审计没有为最终包的新哈希重新生成完整 REA 清单；以下 REA 与 ELF 数值是原始构建的历史截面，不声称与最终整包哈希相同。

初始静态审计输入为当时的 `app/build/outputs/apk/debug/app-debug.apk`：

- SHA-256：`a181896b81a92d7685dd0db0107243353921b43e97d9ec220cc68b9d9bc073dd`。
- `aapt dump badging`：包名 `com.example.jizhang`，versionCode 3 / versionName 2.0，minSdk 24 / targetSdk 37；支持 small / normal / large / xlarge 屏幕。
- 该截面的 APK 实含 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 四个 ABI，每个 ABI 两个 .so。没有限定三星处理器或机型；最终工程仍采用这些 ABI，未引入机型白名单。
- 该截面的合并 APK 无 `INTERNET`、`ACCESS_NETWORK_STATE`、短信或外部存储运行时权限；badging 仅列应用自身的动态接收器权限。
- 中文 OCR 模型实际位于 `assets/mlkit-google-ocr-models/`，包括 `Hani_ctc/optical/lstm_model.fb` 与中文 / Latin engine。

REA 命令序列：

```text
open_binary(path=<workspace>/app/build/outputs/apk/debug/app-debug.apk)
inspect_artifact(integrity_policy=fail)
close_binary()
```

REA `inspect_artifact` 完整 Evidence：`ev_c2fd36777d10b30b2371a96e599d33ca3d5983a30038f8d956f269c085b32344`；嵌套 `inventory_artifact`：`ev_5c66ed50a3691bd9b8105e64ea3897c6f183add3c3b1946e814b030034a30871`。图内 495 个内容节点 / 565 个 occurrence，完整性检查无矛盾。完整工具返回记录保存在本地 `docs/evidence/v2-android-compatibility-rea-record.json`，原始证据目录被 Git 忽略。归档分析是静态证据，不执行 APK，也不验证签名。ZIP 清单不覆盖 ZIP 尾部 / APK Signing Block。签名须另用 `apksigner verify` 验证。

本次归档 provider 没有 concrete analysis profile，因此 `close_binary(snapshot_path=...)` 不能写可重放分析快照，返回 `evidence_integrity_mismatch`。保留的是完整原始 Evidence JSON；正常 `close_binary()` 已成功，无需重做清单分析。

## 无 GMS 的 OCR 设计

依赖是 `com.google.mlkit:text-recognition-chinese:16.0.1`，不是 `play-services-mlkit-text-recognition-chinese`。Google 官方说明：bundled 模型在构建时随应用打包，安装后可直接使用；unbundled 模型通过 Play services 下载。产品代码使用本地 `InputImage.fromBitmap`，未调用模型下载、GMS 可用性检查或云端识别接口。[Google 中文识别集成文档](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)、[模型安装方式](https://developers.google.com/ml-kit/tips/installation-paths)

API 35 x86_64 的官方 16 KB 模拟器已在禁用 Google Play services 并关闭网络后通过三项本地 OCR 测试，原始日志为本地 `docs/evidence/v2-16k-without-gms-ocr.txt`，结果 `OK (3 tests)`。这是该模拟器环境的运行证据，不等于华为 / 荣耀或其他无 GMS 实体手机已经通过。SDK 的传递依赖仍包含 Google 的通用 Java 库；存在这些打包依赖不代表必须安装或启用 GMS 服务。机型结论仍应依据实际 Android 版本、系统能力及支付应用版本。

## 16 KB 内存页：已通过与待验证项

使用已安装的 Android Build-Tools 36.0.0 对上述实际 APK 执行：

```powershell
zipalign.exe -c -P 16 4 app/build/outputs/apk/debug/app-debug.apk
```

返回码为 0。另直接解析每个 ELF 的 program header；两个 64-bit ABI 的全部 PT_LOAD 段 `p_align=16384`，`p_offset % 16384 == p_vaddr % 16384`。完整八个库的 LOAD / GNU_RELRO 数值保存在本地 `docs/evidence/v2-android-compatibility-native.json`，其输入 SHA-256 与 REA 相同。ZIP 对齐与 ELF LOAD 对齐是两项不同检查。[Android 16 KB 官方说明](https://developer.android.com/guide/practices/page-sizes)、[zipalign 官方说明](https://developer.android.com/tools/zipalign)

| ABI / 库 | PT_LOAD 对齐 | GNU_RELRO 末端对 16384 取余 |
| --- | --- | --- |
| arm64-v8a / libandroidx.graphics.path.so | 16384 | 8192 |
| arm64-v8a / libmlkit_google_ocr_pipeline.so | 16384 | 12288 |
| x86_64 / libandroidx.graphics.path.so | 16384 | 12288 |
| x86_64 / libmlkit_google_ocr_pipeline.so | 16384 | 8192 |

官方当前文档还列出 `(VirtAddr + MemSiz) % 0x4000 == 0` 的 GNU_RELRO 检查；上述第三方库没有满足这项静态条件。这项发现与运行结果分别保留：官方 API 35 x86_64 模拟器的 `getconf PAGE_SIZE` 返回 16384，轻记 2.0 主界面、本地 OCR，以及实际图形 JNI 的 conic → quadratic 转换已经运行通过。静态条件未满足并没有在已测试的这个 x86_64 环境产生加载失败；尚未定位其加载器处理机制，不能据此宣称静态条件合格。[GNU_RELRO 检查依据](https://developer.android.com/guide/practices/page-sizes#check-the-relro-security-flag)

该结果证明已执行的 x86_64 单项路径可运行，不证明完整长进程稳定：后续全量复跑发生 ART/JIT 原生崩溃，详见运行记录。也不证明 ARM64 手机或全部内存页配置均兼容。还需要在 16 KB arm64 真机验证冷启动、中文 OCR、图形路径与窗口截屏。本次没有因为静态检查单独禁用 16 KB 设备的 OCR，也没有修改第三方 .so 或引入另一套平台 View 界面。

32-bit `armeabi-v7a` / `x86` 的 ML Kit 库 PT_LOAD 仍为 4096，最后一个 LOAD 不满足 16 KB offset / vaddr 同余。当前官方 16 KB 检查重点是 arm64-v8a / x86_64；32-bit ABI 的存在用于普通 4 KB 设备兼容，不能声明这八个库全部满足 16 KB 条件。

## 窄屏、大字体与窗口变化

主页面使用 dp / sp、滚动列表、最大 780dp 内容宽度与系统 inset；Manifest 没有锁定竖屏或固定机型。但这些布局选择不能代替横屏、折叠与大字体测试。target 37 的大屏会忽略方向 / 尺寸限制，因此必须允许窗口变化。[Android 自适应规则](https://developer.android.com/develop/adaptive-apps/guides/app-orientation-aspect-ratio-resizability)

编辑页、候选核对页的业务 / 分类 / 金额选项与账单筛选已由固定列数 Row 改为 `FlowRow`，允许窄屏与大字体时换行。候选核对页的表单使用按来源事件 ID 保存的 `rememberSaveable`。这些修改改善布局与重建状态，但不替代 320dp、fontScale 2.0、横屏与折叠实体设备验收。

主账单编辑器的输入及打开状态仍使用 `remember`：旋转或折叠导致 Activity 重建时会关闭编辑器，并丢失未保存草稿。请先保存后旋转；这是当前限制。已保存账单在数据库中，不依赖界面状态。备份密码与解密后的临时数据不写入可保存界面状态。

API 30–33 使用默认 display 截图，不能据此声明三星 DeX 或其他外接副屏已适配；API 34+ 的窗口截图与多窗口几何校验仍需要折叠 / 平板 / 副屏运行测试。

## 厂商后台策略与安装授权

应用依赖系统绑定的通知与无障碍服务，不使用 Root、Xposed、私有接口或厂商安装豁免。厂商省电策略、用户强制停止、服务关闭、企业策略均可能阻止后台识别。界面区分“已授权”和“已连接”，提供系统应用信息、后台 / 电池设置入口和手动导入降级；用户自行决定是否调整系统授权。

Doze / App Standby 和系统电池优化由 Android 控制。用户可在系统设置调整，但豁免也不等于永久运行保证。[Android 省电机制](https://developer.android.com/training/monitoring-device-state/doze-standby)

旁加载 APK 在 Android 13+ 可能触发“受限设置”；需要用户从应用信息允许后再次授予无障碍 / 通知访问。这不是三星独有权限，具体入口随系统变化。[Google Android 受限设置说明](https://support.google.com/android/answer/12623953)

## 运行证据与发布前验收

| 实际环境 | 运行内容 | 当前结果 / 证据 |
| --- | --- | --- |
| 官方 API 35 x86_64，系统 `getconf PAGE_SIZE=16384` | 轻记 2.0 主界面启动；三项本地 OCR 与两项设备兼容卡片测试 | 五项通过，日志 `docs/evidence/v2-16k-ocr-and-device.txt`：`OK (5 tests)`，36.673 秒 |
| 同一 API 35 x86_64 16 KB 模拟器，禁用 GMS 且关闭网络 | 三项本地 OCR | 三项通过，日志 `docs/evidence/v2-16k-without-gms-ocr.txt`：`OK (3 tests)`，19.678 秒 |
| API 35 x86_64 16 KB，全量与分组设备测试 | 实际图形 JNI、数据库、备份与 UI | 全量首轮 123／124，另一轮全量在 UI 阶段原生崩溃中断；修复测试等待后账本 UI 单独 10／10 通过，87.408 秒；识别中心与屏幕设置单独 10／10 通过，48.448 秒。124 项各有通过记录，但没有单进程全套通过结果 |
| API 37 专用模拟器，全量设备测试 | 账本、迁移、识别、图形 JNI、备份与 UI | 固定测试像素后的最终 124／124 通过，145.048 秒；`docs/evidence/v2-api37-fixed-fixture-full-124.txt` |
| API 37 专用模拟器 | 合成付款通知 → 页面节点 / OCR → 来源关联 → 正式账本闭环 | 独立 SQL 校验通过：10 笔正式账目、28 条来源事件、合计净支出 ¥106.00；记录 `docs/evidence/v2-e2e-final.json` |
| API 24 x86_64，4 KB 模拟器 | 最终 124 项设备测试，含本地 OCR、图形 JNI、数据库、备份与 UI | 124／124 全量通过，201.18 秒；`docs/evidence/v2-api24-full-instrumentation-rerun.txt`。缺少 `getconf` 时改查进程 `/proc/self/smaps` 的 `KernelPageSize` / `MMUPageSize`，两者均为 4 kB |
| SAF 系统文件选择器 | 实际导出、预览、取消、错误密码与最终覆盖恢复 | 导出 36,813 字节加密备份；七表逐行全部字段与原数据相同，预算和支付来源选择保留，通知 / 屏幕 / OCR 三个自动开关关闭；`docs/evidence/v2-saf-roundtrip-result.json` 为 `verified: true` |

API 24 首轮有两项测试失败：48px 合成字体被识别成“转账戍功”，严格解析按预期拒绝错误完成状态；另有 Snackbar 等待超时。测试图放大为 96px 并加大行距，Snackbar 改用公开语义 Dismiss 动作关闭，保留原账务与识别断言，生产解析没有接受错字状态；复跑才取得上述 124／124 结果。API 37 曾有一项动态绘制字体被 OCR 错读后遭保守拒绝；随后将已验证的 88.66 元测试像素固定为资产，保留真实 OCR 及金额／支出／待确认断言。固定资源后的 API 24 OCR 3／3 通过（12.894 秒），API 35／16KB OCR 3／3 通过（8.137 秒）；固定资源后的 API 37 完整 124／124 通过（145.048 秒），日志 `docs/evidence/v2-api37-fixed-fixture-full-124.txt`。

API 35 的原始全量日志为 `docs/evidence/v2-api35-16k-full-instrumentation.txt`，崩溃复跑日志为 `docs/evidence/v2-api35-16k-full-instrumentation-rerun.txt`。分组通过日志分别为 `docs/evidence/v2-api35-16k-ui-isolated.txt` 和 `docs/evidence/v2-api35-16k-remaining-ui.txt`。这些结果不能相加表述为同一进程 124 项连续通过。

长进程失败必须保留：镜像指纹 `google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925:userdebug/dev-keys`，产品进程在 14:57:11 出现 RIP=0 的 SIGSEGV，回溯涉及 JIT 编译的 `CompositionImpl.recordModificationsOf`、`Recomposer` 与 Compose 测试 continuation interceptor。原始记录 `docs/evidence/v2-api35-16k-crash.txt` 中，同一镜像还出现 SystemUI 的 ART MarkCompact 崩溃，以及 Google AS / GMS 的 Profile Saver 类元数据断言失败；Google AS 也曾出现 RIP=0 的协程 JIT 回溯。

上述系统进程属于不同进程，说明测试环境同时存在独立的不稳定现象，但不足以证明产品崩溃仅由镜像导致。产品回溯没有直接列出 OCR / 图形 JNI，也不足以排除较早的原生内存污染。当前根因未确定，没有找到可直接对应这一镜像与回溯的官方已确认 JIT 缺陷；不依据这些日志盲目改依赖，也不将失败隐藏为“全套通过”。

`NativeGraphicsInstrumentedTest` 在上述 API 35 16 KB 全套记录中通过：先用实际椭圆验证 conic 存在，再经 Compose → AndroidX 原生转换验证 quadratic、坐标有限与结果非空。这是触发真实 JNI 的运行测试，不是仅检查 APK 文件或模拟算法。它不证明所有八个原生库完全符合 16 KB 静态规则。

API 37 的收支金额来自专用测试 APK 的合成交易，两家支付来源各 5 笔正式账目、14 条来源事件，各净支出 ¥53.00。没有进行真实转账或支付，不能换算为真实微信 / 支付宝识别成功率。SQL 检查未出现聊天金额或指定受保护订单身份；仅凭数据库未入账不能证明所有受保护页面的截图拒绝路径都已运行覆盖。

API 24 已取得单个 x86_64、4 KB 模拟器的完整运行结果；API 25–33 降级分支仍以源码检查与纯逻辑测试为主，不能把 API 24 的通过扩展成整个旧版本区间通过。本次没有三星 S23 Ultra、其他品牌、Android Go、无 GMS 实体手机、真实微信 / 支付宝交易、16 KB arm64 手机或折叠设备的实测证据。完整自动测试结果以主验证记录为准，不能将模拟器测试换算为机型覆盖率。

每个新增设备至少记录 Android API、ABI、内存页大小、系统版本、微信 / 支付宝版本、服务实际连接状态，并验收：安装与升级保留账本、付款通知、转账 / 红包结果页、原交易与两次退款、重复来源去重、关闭来源时停止采集、屏幕锁定 / 隐私页不采集、旋转 / 大字体 / 小窗口、备份恢复与服务重连。无法自动识别的系统应保留手动补录 / 用户截图导入，且不展示“全机型已通过”。

## 16 KB 上游更新与初始化边界补充

本次实际读取 Google Maven 小型 metadata：`text-recognition-chinese` 最新 16.0.1，`text-recognition-bundled-common` 最新 17.0.0，均为当前依赖；未发现可用于替换 OCR 原生库的更新。官方 2024-08-07 发布说明宣称这些 SDK 支持 16 KB，但实际库的 GNU_RELRO 检查仍需核对，不能用发布说明覆盖实际数值。[ML Kit 发布说明](https://developers.google.com/ml-kit/release-notes)、[Chinese metadata](https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-chinese/maven-metadata.xml)、[Bundled metadata](https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-bundled-common/maven-metadata.xml)

`graphics-path` 官方最新稳定版为 1.1.0。只读下载了官方 49,094 字节 AAR 与 16,280 字节源码 JAR，未加入工程。AAR SHA-256 为 `d031370d45ba4129a175fe9de913328a512b01a3c267e0eaf6b9b759f35842ae`。其 arm64 / x86_64 库 LOAD 仍为 16384，GNU_RELRO 末端余数均为 8192，故升级此版本不能证明解决已发现的条件。[Graphics 官方发布说明](https://developer.android.com/jetpack/androidx/releases/graphics#graphics-path)、[Graphics metadata](https://dl.google.com/dl/android/maven2/androidx/graphics/graphics-path/maven-metadata.xml)

实际依赖源码 / bytecode 检查结果：

- 当前 `graphics-path:1.0.1` 的 `PathIteratorImpl` 在静态初始化时直接 `System.loadLibrary("androidx.graphics.path")`。API 34+ 选择平台 iterator 也不能避开父类初始化，因为 conic 转换仍用原生代码。
- `graphics-path:1.1.0` 仅增加 Dalvik VM 条件，Android 上仍会加载同一原生库。
- 当前 Compose `ui-graphics-android:1.10.0` 的 `AndroidPathIterator` 调用 AndroidX iterator，因此不能仅关闭 OCR 就声明整个 Compose 界面不会加载图形 JNI。
- 当前 `MlKitInitProvider.onCreate` 只调用 `MlKitContext.zza`，构建组件容器并初始化标记为 eager 的组件。合并 Manifest 中实际注册的 Common、VisionCommon、Text 三个 registrar 未标记 eager；这些实际 JAR 没有直接 `loadLibrary`。按此静态调用链，provider 初始化不加载 OCR JNI。
- OCR JNI 位于 `bundled.common.zba.zbc()` 与 `NativePipelineImpl` 的带字符串构造器；前者显式调用 `System.loadLibrary("mlkit_google_ocr_pipeline")`。本次 API 35 x86_64 16 KB 环境实际执行了该模型路径且通过，不能将其描述为只验证 Java 代码。

本地只读材料保存于被忽略的 `docs/evidence/compat-jars/`，包括实际缓存 AAR 中提取的 classes.jar、对应 `javap -p -c` 返回、Compose path iterator 源码，以及新 graphics 版本取样。没有修改第三方 .so 或强制升级 SDK。

当前版本保持现有 Compose 与 bundled OCR 集成，以实际运行测试评估兼容性；没有盲目升级 SDK、修改第三方库、移除初始化 provider，或仅因内存页为 16 KB 而关闭已经验证的功能。静态 GNU_RELRO 数值仍保留供后续 ARM64 测试与上游反馈核对。新增系统 / ABI 应分别验收实际模型与图形 JNI 路径；未验证环境保留明确说明和手动补录入口。
