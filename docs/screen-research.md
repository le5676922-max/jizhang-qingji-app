# 屏幕识别自动记账调研

调研日期：2026-10-13（Asia/Shanghai）。本报告读取 GitHub 固定提交的完整源码及 Android / Google 官方文档；未复制参考项目实现，未对用户手机上的微信、支付宝进行实机验证。

用户已确认微信、支付宝的转账和红包操作没有发出系统通知。通知监听无法补出不存在的通知，应增加独立的屏幕识别通道。可行方案为用户主动启用的无障碍服务读取支付结果页节点，节点不足时使用正常截图与设备端中文 OCR；不依赖 Root、Xposed 或修改支付应用。

## GitHub 源码证据

### AutoAccountingOrg/AutoAccounting

- 仓库：[AutoAccounting](https://github.com/AutoAccountingOrg/AutoAccounting)。固定提交：[34b38f82ca9ad6d7905c715c66891977f8809c88](https://github.com/AutoAccountingOrg/AutoAccounting/commit/34b38f82ca9ad6d7905c715c66891977f8809c88)，提交时间 2026-09-30。
- 根目录 [LICENSE](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/LICENSE) 为 GNU GPL version 3。部分文件头写有“Apache License, Version 3.0”，与根许可证不一致；本项目只研究行为、独立实现，不导入其代码或资产。
- [SelectToSpeakService.kt](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/app/src/main/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt#L169)：169–223 行处理窗口状态/内容变化；内容变化防抖后匹配页面结构签名、生成内容指纹、检查规则白名单，再启动 OCR。338–376 行从无障碍节点读取 text/contentDescription，含 WebView 子节点遍历。
- [PageSignatureManager.kt](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/app/src/main/java/net/ankio/auto/service/ocr/PageSignatureManager.kt#L58)：按包名、Activity、结构指纹匹配已记住的页面。应以实际 `matches` 方法的等值判断为依据，不能把注释中“模糊匹配”当作已经实现的行为。
- [OcrTools.kt](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/app/src/main/java/net/ankio/auto/service/ocr/OcrTools.kt#L47)：47–83 行通过无障碍 `takeScreenshot(Display.DEFAULT_DISPLAY, ...)` 截图，API 30 以下返回失败，成功后转换 Bitmap、保存文件并关闭 HardwareBuffer。该提交的这一方法实际使用无障碍截图；不能仅根据其他文件的注释声称它同时走 Root/Shizuku 分支。
- [OcrProcessor.kt](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/ocr/src/main/java/net/ankio/ocr/OcrProcessor.kt#L58)：58–78 行从 assets 加载 PP-OCRv5 / ncnn 模型，实际传入 `Device.CPU`；136 行起执行设备端图片识别。调试模式可保存图片，应避免在本项目默认启用。
- [OcrService.kt](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/app/src/main/java/net/ankio/auto/service/OcrService.kt#L294)：294 行起先写 `externalCacheDir/screen.png`，再解码、裁剪、缩小和 OCR；439 行起交给规则/AI分析接口。说明“截图获取 → OCR → 规则处理”的工程路线确有源码，不证明任意支付版本都可捕获，也不等于所有 AI 配置都离线。

可参考的是页面匹配、事件防抖、捕获与解析分层。轻记独立实现时应限制到微信/支付宝、在内存处理截图、避免保存原始页文本；不继承其全应用采集范围或原图缓存策略。

### Skybaby-T/AutoBook

- 仓库：[AutoBook](https://github.com/Skybaby-T/AutoBook)。固定提交：[84a8e1a92bff17ef09a6ffbf32ada7857e96b729](https://github.com/Skybaby-T/AutoBook/commit/84a8e1a92bff17ef09a6ffbf32ada7857e96b729)，提交时间 2026-10-11。
- 此提交的递归文件树未发现 LICENSE / COPYING 等授权文件。公开可读不代表可复制再发布；这里只记录观察，不复用代码。
- [PaymentAccessibilityService.kt](https://github.com/Skybaby-T/AutoBook/blob/84a8e1a92bff17ef09a6ffbf32ada7857e96b729/app/src/main/java/com/tao/autobook/service/PaymentAccessibilityService.kt#L47)：47–50 行 `onAccessibilityEvent` 直接返回，主动自动识别在这个提交已被禁用，注释说明改为用户截图后处理，以避免浏览商品误触发。文件中的节点提取、支付页判断、截图分析辅助方法仍存在，不能因此声称事件通道正在运行。
- [payment_accessibility_service.xml](https://github.com/Skybaby-T/AutoBook/blob/84a8e1a92bff17ef09a6ffbf32ada7857e96b729/app/src/main/res/xml/payment_accessibility_service.xml)：声明窗口内容访问和截图能力、包名白名单。
- [OcrRecognizer.kt](https://github.com/Skybaby-T/AutoBook/blob/84a8e1a92bff17ef09a6ffbf32ada7857e96b729/app/src/main/java/com/tao/autobook/ocr/OcrRecognizer.kt)：使用 ML Kit 中文识别器和拉丁识别器处理 Bitmap。
- [app/build.gradle.kts](https://github.com/Skybaby-T/AutoBook/blob/84a8e1a92bff17ef09a6ffbf32ada7857e96b729/app/build.gradle.kts#L104)：104–105 行引用捆绑版 `text-recognition:16.0.1` 和 `text-recognition-chinese:16.0.1`。

这个项目提供了失败模式证据：普通页面的“支付/金额”字样会误触发。不能只扫描所有微信/支付宝页面并发现金额就记账，必须识别支付结果页、完成状态和方向。

## Android 官方能力与边界

| 能力 | 官方结论 | 本项目设计 |
| --- | --- | --- |
| 窗口节点 | `getRootInActiveWindow()` 自 API 16 可用，需要 `canRetrieveWindowContent`；当前活动窗口可能是输入焦点或用户触摸的窗口 | 每次核对根节点包名和窗口 ID；仅处理微信、支付宝的已识别结果页 |
| 无障碍截图 | `takeScreenshot` 自 API 30 可用，需要 `canTakeScreenshot` | Android 11+ 可做 OCR 降级，低版本保留节点识别/手动录入 |
| 指定窗口截图 | `takeScreenshotOfWindow` 自 API 34 可用，可避免无障碍悬浮层覆盖结果 | Android 14+ 优先截取目标应用窗口；回调后再次核对窗口未切换 |
| 截图失败 | 权限缺失、间隔太短、无效窗口等有明确错误码 | 限流、取消陈旧请求、显示可理解的状态；不重复高频截图 |

上述能力依据 [AccessibilityService 官方 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)，资源释放依据 [ScreenshotResult](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.ScreenshotResult)。截图回调的 HardwareBuffer 必须关闭，Bitmap 只在 OCR 请求完成后释放；服务中断或关闭时取消排队任务。

[`FLAG_SECURE`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE) 阻止安全窗口出现在截图中。API 34+ 有 `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`；遇到它、空白图或系统拒绝时停止识别，提示手动补录，不尝试绕过支付应用的屏幕保护。能拿到节点与能截图是不同能力，不能承诺安全页面总能被 OCR 识别。

Android 34+ 可将标记为敏感的节点对非助残服务隐藏，见 [AccessibilityNodeInfoCompat.setAccessibilityDataSensitive](https://developer.android.com/reference/androidx/core/view/accessibility/AccessibilityNodeInfoCompat#setAccessibilityDataSensitive(boolean))。[`isAccessibilityTool`](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#isAccessibilityTool()) 用于表明助残用途，默认 false；记账服务保持 false，不通过虚假声明扩大可见数据。微信/支付宝是否把某个结果页标记为敏感，必须以具体安装版本和设备实际测试为准。

## 设备端中文 OCR

截至调研日，[ML Kit Android 官方接入文档](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) 给出的捆绑中文依赖为 `com.google.mlkit:text-recognition-chinese:16.0.1`，最低 API 23，使用 `ChineseTextRecognizerOptions`。模型随 APK 分发，首次识别不等待 Play Services 下载；本项目 minSdk 24 满足库要求。输出包含文字块、行、元素与边框，可据位置把“金额/收款方/付款方”标签关联到对应值，不能把整张图中的最大数值默认当交易金额。

[ML Kit 官方隐私说明](https://developers.google.com/ml-kit/terms#privacy) 明确图像、文字和识别结果在设备端处理，不传给 Google 服务器；同时 SDK 可能发送性能/使用指标、请求更新与兼容性信息。因此对用户说明“账单截图与识别结果不上传”，不要承诺第三方 SDK 绝无网络通讯。轻记自己的账单通道不接入云端识别、AI接口或远程日志。

## 独立实现建议

1. 用户在 App 中开启“屏幕自动记账”，阅读用途说明后自行在系统设置启用服务；关闭开关后立即停止读取和 OCR。通知识别保留为另一通道。
2. 服务 XML 与运行时均限制 `com.tencent.mm`、`com.eg.android.AlipayGphone`，监听窗口状态/内容变化，使用短防抖且单次只运行一个识别任务。
3. 首先读取可见、非密码、非编辑字段的节点，保留行位置用于判断页面模板；拒绝聊天、账单列表、历史交易、输入支付密码、收银台、待付款及营销页面。限制树深度、节点数量和文字长度。
4. 只在已有支付结果页证据、已识别页面模板或用户主动触发时尝试截图 OCR。节点为空且页面身份未知时不能自动截图所有微信聊天；提供用户主动“识别当前页/导入截图”的补充入口。
5. 金额、完成状态与转入/转出方向必须属于同一交易区域。付款、成功转出、红包已成功发出可作为支出；收到款项、已入账和退款属于收入；方向不清晰、金额冲突或仅有“转账成功”时进入待确认或跳过，不能默认支出。
6. 未完成的收款、待到账、处理中、失败、取消等不自动生成已完成收入；未领取的红包只有在页面明确证明已发送并扣款时才能记录支出。红包详情要区分发送金额和已领取金额，防止把发出的红包认作收入。
7. 节点解析和 OCR 共用候选账单模型、分类和入账规则。无唯一订单标识时保守去重，不能仅按“金额 + 分钟”永久合并两笔真实相同金额交易。通知与屏幕的重复记录也须互相校验。
8. 默认只落盘金额、方向、来源、时间、可选交易对方和分类；截图与完整页面文字只在内存短暂处理后释放，不输出原文到日志。不采集密码、验证码、银行卡号、手机号、聊天内容或通讯录，不执行点击、付款、收款等操作。

以上是设计建议，最终状态应以实现及验证报告为准。真实微信/支付宝页面文案、无障碍可见性和 S23 Ultra 系统策略尚未在用户手机验证；开源项目存在这条路线不构成对该设备的覆盖承诺。

## 有意义的验证

- 解析夹具覆盖付款、成功转出、转入、发送/收到红包、退款，以及待收款/未领取/失败、多个金额、聊天里引用付款语句、历史账单和重复回调。夹具是测试样本，不应描述为实机观察证据。
- 模拟器用受控付款结果 Activity 验证无障碍节点事件、节点不可见时 OCR、同窗重复事件和切窗取消；用 `FLAG_SECURE` 的测试窗口验证正常失败和补录提示。
- OCR 使用含中文、人民币符号、小数金额与结构化标签的离线图片测试，确认截图和原文不落盘。
- 最后在 S23 Ultra 上逐一验证真实微信/支付宝付款、转账、红包结果页。无法读取或截图的页面应显示明确原因与手动入口，不能显示“已自动记录”但没有账目。

## 补充：MIT 节点读取项目

### DykiSensei/seamless-bookkeeping

- 仓库：[Seamless Bookkeeping](https://github.com/DykiSensei/seamless-bookkeeping)。固定提交：[af717f9faa5658b2e67dcc61e200867951806734](https://github.com/DykiSensei/seamless-bookkeeping/commit/af717f9faa5658b2e67dcc61e200867951806734)，提交时间 2026-06-21。
- [LICENSE](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/LICENSE) 已核对为 MIT，版权 Dyki 2026。本次继续独立实现，没有复制这套源码。
- [AccessibilityCaptureService.kt](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/app/src/main/java/com/bookkeeping/app/notification/accessibility/AccessibilityCaptureService.kt#L53)：53 行起根据事件包名选择提取器，读取 `rootInActiveWindow`，刷新节点后尝试解析，再通过 Repository 入库；以来源、金额、方向的指纹抑制 30 秒内重复事件。
- [AccessibilityNodeUtils.kt](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/app/src/main/java/com/bookkeeping/app/notification/accessibility/AccessibilityNodeUtils.kt#L7)：7 行起遍历整棵节点树，拼接 text/contentDescription；63 行起提取首个带币种金额。源码未按密码、编辑状态或可见性过滤全部节点，调试 `dumpTree` 会输出页原文。轻记须独立实现受限读取及原文不落盘。
- [服务 XML](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/app/src/main/res/xml/accessibility_service_config.xml)：仅监听微信/支付宝、声明窗口内容访问；没有 `canTakeScreenshot`，该通道是 UI 节点读取，不是截图 OCR。其 `isAccessibilityTool=true` 及“Android 13+ 必填”的注释不应沿用；用途声明以 Android 官方定义为准。
- [WechatTransferExtractor.kt](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/app/src/main/java/com/bookkeeping/app/notification/accessibility/WechatTransferExtractor.kt#L24)、[WechatRedPacketExtractor.kt](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/app/src/main/java/com/bookkeeping/app/notification/accessibility/WechatRedPacketExtractor.kt#L19)、[AlipayTransferExtractor.kt](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/app/src/main/java/com/bookkeeping/app/notification/accessibility/AlipayTransferExtractor.kt#L24)：三个提取器都生成 `INCOME`，目标是部分收款补漏；没有覆盖用户转出/发红包的支出。微信转账收入提示含“对方已收钱”“转账成功”“待确认收款”“请收钱”，有误判方向/未完成交易风险；红包收入提示含“微信红包”，范围也过宽。只可借鉴拆分提取器的组织方式，不能照搬关键词。

[README](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/README.md) 将该通道标为实验性，并记录作者在 vivo 上未拿到事件的观察。该作者记录不是三星官方结论，也不证明 S23 Ultra 可用或不可用。三个项目的证据共同支持“节点读取/正常截图可构成独立采集通道”，同时说明需要方向判断、完成状态与设备实测。

## 补充：三星生活助手“我的账本/我的账单”

用户补充：S23 Ultra 的三星生活助手中存在记账功能，怀疑其使用屏幕识别。这是值得参考的使用行为线索；当前没有拿到用户手机上此功能的版本、权限说明或实际捕获结果，不能把推测当作已知实现。

| 证据层次 | 本次调查结果 |
| --- | --- |
| 三星官方产品页 | [三星生活助手](https://www.samsung.com.cn/apps/samsung-assistant/) 确认了该应用以及场景服务、迷你助手等功能；公开正文没有解释“我的账本/我的账单”的采集机制 |
| 三星官方中文社区 | 官方产品页链接到 [盖乐世社区](https://www.samsungmembers.cn/)；本次打开返回 `Code: 110`，未能读取相关帖子，因此没有据此引用论坛结论 |
| 三星公开系统介绍 | [One UI 官方页](https://www.samsung.com.cn/one-ui/) 与本次读取的 [SM-S9260 官方更新记录](https://doc.samsungmobile.com/SM-S9260/029235240224/zho-cn.html) 未提供该账本底层机制。此记录属于另一型号，不能用于推断用户 S23 Ultra 的实际版本 |
| 源码/运行时 | 未获得三星生活助手源码或该设备授权的运行时证据；没有下载第三方 APK、猜测包名或要求安装任何 provider |

**已知**：三星官方确有生活助手产品，用户报告其具备账本功能。**未知**：具体版本是否使用短信、系统通知、无障碍节点、截图 OCR、系统接口或多个通道；本次官方资料无法证实“自动记账完全基于屏幕识别”。没有检索到官方说明不代表功能不存在。

即使用户观察到“打开付款页就能记账”或“点快捷面板识别当前页”，那也只能确认触发行为，尚不能区分读取界面文字与图像 OCR，更不能证明普通第三方应用能使用三星相同接口。轻记继续采用已公开且可验证的 Android 无障碍节点与正常截图 API；三星功能可作为交互参考，能力边界仍由 Android 和支付页实际保护决定。
