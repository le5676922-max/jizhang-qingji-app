# 屏幕记账连接排查 · 2.0.1 诊断测试版

本文的版本号、APK 与验证数字专属已发布的 2.0.1。2.1 已接入 [DeepSeek 助手](deepseek-agent.md)，另有 [2.1 验证记录](verification-deepseek-v2.1.md)；模型不能代替系统连接，本页结果不冒充 2.1 验证。

用户在三星 S23 Ultra 上反馈微信转账、红包、扫码付款均不显示，轻记一直显示「已授权，等待系统连接」，关闭并重新开启系统服务后仍然如此。**目前尚未确定这台真机的根因，也未验证本版能恢复其连接。** 在收到有效的服务连接回调之前，屏幕识别通道不能读取交易页面；继续换付款方式或重复支付不能帮助定位这个连接问题。

本版先把授权、系统状态与轻记实际收到的回调分开，提供可复制的诊断，再依据真机结果继续排查。付款解析算法、交易页筛选、敏感页面保护、金额规则和待确认条件没有放宽。

## 安装与反馈

本版为 `versionName 2.0.1`、`versionCode 4`，交付入口为 [v2.0.1-test 发布页](https://github.com/le5676922-max/jizhang-qingji-app/releases/tag/v2.0.1-test)，安装包名为 `qingji-v2.0.1.apk`，源码与本版标签对应。

1. 先在旧版导出完整加密备份并保留密码，再用同签名的新包覆盖安装。**保留原应用，不要卸载或清除数据。** 同签名校验结果见文末本版验证表。
2. 打开轻记「自动记账 → 屏幕记账」，点「重新检查连接」，再展开「连接排查」。检查只读取状态，不会修改系统授权，也不会模拟付款。
3. 若仍未连接，点「复制连接诊断」，把文字粘回当前反馈对话即可。无需重新转账、发红包或扫码付款，也无需发送交易截图、金额、联系人或单号。
4. 排查页提供公开的无障碍设置、轻记应用信息和后台运行设置入口。可查看系统是否提示受限设置、轻记是否被限制后台或放入深度休眠；这些是排查方向，尚未证实为本次三星问题的原因。若已关闭再开启服务仍等待，请保留这个结果，不必重复同一步骤。

诊断文本由用户主动复制；应用不会自行发送。发送前可自行查看内容。

## 三个状态分别代表什么

| 状态 | 读取方式 | 含义与边界 |
| --- | --- | --- |
| 系统授权 | 只读系统 Secure 中的无障碍总开关与轻记组件授权匹配 | 代表用户授权状态；不代表系统已经绑定或已经能识别。读取异常显示未知。 |
| 系统服务列表 | `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)`，只保留是否包含轻记自身组件的布尔值 | 供连接排查参考；不保存或展示其他服务的名称、列表或配置，不把列表匹配当成轻记收到连接回调。查询异常显示未知。 |
| 轻记连接回调 | 当前进程自身的服务生命周期回调 | 当前授权有效且确实收到连接回调，才显示已连接。历史连接记录不会冒充当前连接。 |

界面因此可区分未授权、已授权但系统尚未列出、系统已列出但尚未收到回调、真实已连接，以及系统查询不可用。若只有系统列表查询失败，已有真实连接回调不因此变成「未连接」；无法查询授权时则不能宣称已获得采集能力。

检查发生在进入／返回应用前台、连接回调变化以及用户明确点「重新检查连接」时，没有新增后台轮询计时器。状态查询只读，不替用户改变系统设置。服务的生命周期由系统管理，应用不能用一个自行启动普通服务的动作替代无障碍绑定。[Android 官方生命周期说明](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#service-lifecycle)、[官方服务列表 API](https://developer.android.com/reference/android/view/accessibility/AccessibilityManager#getEnabledAccessibilityServiceList(int))。

## 本版的兼容调整

无障碍服务声明调整为 `android:exported="true"`，同时保留 `android.permission.BIND_ACCESSIBILITY_SERVICE` 系统签名权限保护，与官方声明示例一致。这是声明兼容修正，**不是已经查明的三星根因或已经验证的修复**。旧 2.0 在 API 24 模拟器上使用原声明也曾成功绑定，不能据此把所有等待连接都归因于导出声明。[Android 官方声明方式](https://developer.android.com/guide/topics/ui/accessibility/service#declare-in-the-manifest)。

实际收到 `onServiceConnected()` 后，运行时 `serviceInfo` 的精炼配置若为空或发生异常，不再因该步骤破坏连接回调，而记录固定的配置兼容提示。静态 XML 的支付应用名单、事件入口和根窗口的包名检查仍然保留，只处理用户所选的微信／支付宝结果页；本版没有扩大采集来源。

没有新增「强制重连」按钮或虚构 `AccessibilityService.requestRebind` API，没有申请 `WRITE_SECURE_SETTINGS`、写入 Secure、引入 ROOT 或厂商私有接口。公开设置入口由用户操作。已知 AOSP 强制停止处理可能移除授权组件；本轮模拟器观察到的「强停后名单被移除」与用户的「授权保留但等待连接」不同，**尚未复现用户的同一故障**。[AOSP AccessibilityManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java)。

## 诊断数据与隐私

应用私有目录只持久化自身固定生命周期阶段、阶段时间、上次连接时间和配置兼容警告，用于区分没有启动、已经创建、收到连接、中断反馈、解绑或销毁；读取失败会明确标记。它不是屏幕、账单或支付日志，也不能证明当前服务仍然连接。

用户主动复制的报告包含轻记版本、设备厂商／型号、Android API、上述三个连接状态、自己的生命周期时间／阶段、配置提示与检查时间。不包含账单、金额、通知正文、图片、页面转储、其他服务列表、其他应用安装列表或设备唯一标识符。原有自动采集开关和用户授权边界继续有效。

## 本版验证记录

以下是 **2.0.1** 的验证状态，不能拿 2.0 的模拟器结果当作本版或三星真机验证。2.0 的 199 项 JVM、API 24／37 各 124 项及 API 35／16 KB 长进程限制继续保留于 [2.0 验证报告](verification.md)。

| 检查 | 2.0.1 结果 |
| --- | --- |
| 产品与测试 APK 构建 | 最终构建成功，2 分 9 秒；assembleDebug、assembleDebugAndroidTest、testDebugUnitTest、lintDebug 均完成。Lint 0 错误、28 警告（含 SharedPreferences KTX 建议），未隐藏警告。 |
| JVM 单元测试 | 205 项通过，0 失败、0 错误、0 跳过；新增连接状态组合测试。 |
| API 24 设备测试 | 完整 135 项通过，110.774 秒；包括原识别、账本、迁移、备份及新增只读查询、生命周期和 10 项屏幕设置 UI。原始记录 v2.0.1-api24-full-instrumentation.txt。 |
| API 37 设备测试 | 连接相关 16 项通过，30.082 秒；只读查询 3、生命周期／组件声明 3、屏幕设置 UI 10。未将它称为本版 API 37 全量回归。记录 v2.0.1-api37-connection-instrumentation.txt。 |
| 实际系统绑定 | 专用 API 24 和 API 37 均在系统绑定后记录 CONNECTED，configuration_warning=false；API 37 用途同意后界面实际显示已连接。未用历史记录冒充当前回调，未把 UIAutomator 的临时服务替代当作真实断线。 |
| APK 文件大小／SHA-256 | qingji-v2.0.1.apk：57,868,874 字节；785109AC407989260CC97A003F1C31085A2187CC29E8C0938F407CFA585D2A99。minSdk 24／targetSdk 37，四 ABI；ZIP 16KB 对齐通过。 |
| 与旧版签名一致性 | APK v2 签名通过；证书 SHA-256 为 64e32611bd105a2b68a15e44b2c785433274ac3c1f416525f1056c2fc2de0498，与 2.0／1.1 发布者证书一致。 |
| 真实三星 S23 Ultra 授权后仍等待连接 | 尚未定位根因，尚未验证修复；等待用户主动提供连接诊断。 |
| 真实微信转账、红包、扫码付款 | 本轮尚无真机识别成功证据；连接恢复之后再用已有交易详情复核。 |

模拟器权限状态与合成支付页面用于验证应用行为；不会操作用户手机或修改其授权。收到真机诊断后，再判断故障发生在授权读取、系统未绑定、回调未收到还是连接后页面采集阶段。

原始测试输出、只含合成数据的系统截图与自身健康记录保存在本机被 Git 忽略的 docs/evidence/ 和 docs/screenshots/，不公开上传。上述测试未读取或操作用户三星手机。
