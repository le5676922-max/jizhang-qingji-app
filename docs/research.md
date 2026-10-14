# Android 自动记账：开源项目调研与静态证据

> 原始第三方 APK 和 REA 工件保存在开发工作区，未随公开仓库重新分发。文中的 `docs/evidence/` 路径是本地归档路径，见 [首次公开上传说明](publication.md)。

调研日期：2026-10-13。目标是在现有 `com.example.jizhang` Android 工程上独立实现自动收支记录。现有工程采用 Kotlin、Jetpack Compose、Material 3，`minSdk=24`。以下区分已观察的源码、APK 静态证据和本项目设计建议；没有运行第三方 App，也没有读取用户金融账户。

## 1. WhereIsMyMoney

- 官方仓库：[FridayKoi/WhereIsMyMoney](https://github.com/FridayKoi/WhereIsMyMoney)
- 固定源码版本：`e29ae0315fd66605057cd86c29466bd0b048b8e0`
- 已读取：[README](https://github.com/FridayKoi/WhereIsMyMoney/blob/e29ae0315fd66605057cd86c29466bd0b048b8e0/README.md)、[LICENSE](https://github.com/FridayKoi/WhereIsMyMoney/blob/e29ae0315fd66605057cd86c29466bd0b048b8e0/LICENSE)
- 许可证：Apache-2.0，许可证文件标注 Copyright 2025 Friday。
- 相关源码：[PaymentNotificationListener.kt](https://github.com/FridayKoi/WhereIsMyMoney/blob/e29ae0315fd66605057cd86c29466bd0b048b8e0/app/src/main/java/com/friday/wimm/service/PaymentNotificationListener.kt)、[TransactionRepository.kt](https://github.com/FridayKoi/WhereIsMyMoney/blob/e29ae0315fd66605057cd86c29466bd0b048b8e0/app/src/main/java/com/friday/wimm/data/repository/TransactionRepository.kt)

源码观察：监听服务首先过滤微信和支付宝包名，再组合通知标题、正文、大文本等字段，解析金额、方向和商户；仓库将数据库调用放到 IO 线程。缺金额记录可后续补录，误识别可撤销。这提供了“系统通知 → 解析 → 本地仓库 → 界面/确认”的清晰链路。

代码审阅发现：交易判定允许单凭“微信”“支付宝”标题通过，且将待收款/待领取等通知也列为交易线索。因此普通消息和未完成交易存在误记风险。设计建议是只自动入账明确成功、方向与金额完整的通知；其余支付线索放入待确认区。

## 2. Seamless Bookkeeping

- 官方仓库：[DykiSensei/seamless-bookkeeping](https://github.com/DykiSensei/seamless-bookkeeping)
- 固定源码版本：`af717f9faa5658b2e67dcc61e200867951806734`
- 已读取：[README](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/README.md)、[LICENSE](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/LICENSE)
- 许可证：MIT。
- 相关源码：[NotificationParser.kt](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/app/src/main/java/com/bookkeeping/app/notification/NotificationParser.kt)、[PaymentNotificationListenerService.kt](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/app/src/main/java/com/bookkeeping/app/notification/PaymentNotificationListenerService.kt)、[TransactionDao.kt](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/app/src/main/java/com/bookkeeping/app/data/local/dao/TransactionDao.kt)

源码观察：纯解析器与 Android 服务分开，金额以分保存，数据库使用 Room/Flow；服务使用可取消的后台协程，并以通知的 `postTime` 作为时间。解析器区分“向你付款”与“向商户付款”。

代码审阅发现：任何受支持来源的带金额通知，方向无法识别时默认支出；同来源同金额的短时间去重可能吞掉两笔真实同价交易；浮点元转整数分也可能有精度误差。建议方向未知时等待确认，用十进制精确换算，优先以通知身份与内容版本去重。README 的规则覆盖率、微信扫码通知变化和特定 vivo 行为属于作者陈述，本次没有设备实测验证。

## 3. Ivy Wallet

- 官方仓库：[Ivy-Apps/ivy-wallet](https://github.com/Ivy-Apps/ivy-wallet)
- 固定源码版本：`710733dbe4729a437d573cdfb8429c50e617ded1`
- 已读取：[README](https://github.com/Ivy-Apps/ivy-wallet/blob/710733dbe4729a437d573cdfb8429c50e617ded1/README.md)、[LICENSE](https://github.com/Ivy-Apps/ivy-wallet/blob/710733dbe4729a437d573cdfb8429c50e617ded1/LICENSE)
- 许可证：GPL-3.0；本项目仅学习结构，没有复制该仓库代码或素材。
- 相关源码：[TransactionEntity.kt](https://github.com/Ivy-Apps/ivy-wallet/blob/710733dbe4729a437d573cdfb8429c50e617ded1/shared/data/core/src/main/java/com/ivy/data/db/entity/TransactionEntity.kt)、[TransactionRepository.kt](https://github.com/Ivy-Apps/ivy-wallet/blob/710733dbe4729a437d573cdfb8429c50e617ded1/shared/data/core/src/main/java/com/ivy/data/repository/TransactionRepository.kt)

源码观察：交易实体关联账户、分类和发生时间；仓库与读写 DAO 分开，并提供按账户/类别/时间范围查询。它适合作为成熟账本数据组织方式的学习材料。README 明确原作者自 2024-11-05 起停止维护。对初版采用精简分层即可，无需复制完整模块体系。

## 4. APK 逆向证据及覆盖边界

下载来源：[WhereIsMyMoney v1.0.1 Release](https://github.com/FridayKoi/WhereIsMyMoney/releases/tag/1.0.1)。APK 为 15,068,597 字节，保存到 `docs/evidence/WhereIsMyMoney-v1.0.1.apk`。本地 SHA-256 与 GitHub Release 公布的 digest 一致：

`93ff70aebe81612e3ae0358190626b4c99bf685f2b5a5e6d5fa9ec274a574727`

已使用 REA `open_binary` 和 `inspect_artifact` 做离线档案检查，并导出 `docs/evidence/whereismymoney-rea.json`。分析完成后已关闭 REA 会话。

| 证据 | 已观察结果 | 边界 |
| --- | --- | --- |
| `ev_f1c944857ddbeb686e7809435fb69235cf719bf911ae8627a338a861b119abba` | APK 身份与档案检查 | 静态检查，没有执行应用 |
| `ev_02182eb7349f66ad0ad11d05012f8c5f08a627925172110b8a2331143bac2963` | 2,460 个子成员；含 AndroidManifest.xml、5 个 DEX、Compose/Material3 版本资源 | 中央目录成员清单，没有解码 DEX 方法或二进制 Manifest；没有验证 APK 签名 |

`inspect_android_package` 返回 `capability_unavailable`：当前未配置所需的 `jadx-headless-mcp 0.7.1` JAR（`REA_JADX_MCP_JAR`）。因此 APK 反编译未完成，不能把上文源码结论宣称为已验证的 APK 运行行为。没有为此安装额外引擎。完整源码可用时按所调用的 reverse-engineer-anything skill 使用普通源码分析，不需要反编译才能指导本次实现。源码 commit 与 Release APK 也未做逐方法对应验证。

## 5. Android 官方边界

1. [NotificationListenerService 官方文档](https://developer.android.com/reference/android/service/notification/NotificationListenerService)规定 Manifest 服务需声明 `BIND_NOTIFICATION_LISTENER_SERVICE` 和对应 intent-filter；官方示例采用 `android:exported="false"`。读取活动通知等操作应等 `onListenerConnected`，断开后可请求重新绑定。Android N 起回调在主线程，数据库操作应放后台。
2. [NotificationManager 官方文档](https://developer.android.com/reference/android/app/NotificationManager#isNotificationListenerAccessGranted(android.content.ComponentName))说明用户通过 `ACTION_NOTIFICATION_LISTENER_SETTINGS` 授权。`isNotificationListenerAccessGranted` 从 API 27 可用；本项目 API 24–26 应采用兼容状态判断。已授权与当前已连接应分别显示。
3. 官方文档还说明 Android Q 及以下的低内存设备、工作资料环境存在限制。服务只能收到系统提供的通知；支付 App 未发系统通知、正文隐藏或关闭通知时，无法据此完整恢复全部账单。
4. [Android 15 行为变更](https://developer.android.com/about/versions/15/behavior-changes-all#otp-redaction)说明部分敏感 OTP 通知会对不受信任的监听应用隐藏内容。应容忍空正文/受限正文，不能绕过系统限制。
5. [Android 官方受限设置说明](https://support.google.com/android/answer/12623953)介绍 Android 13+ 部分安装来源的受限设置。若设备显示限制，引导用户在应用信息检查系统允许方式，具体入口可能随 ROM 变化。

## 6. 本项目实现建议与验证清单

- 首版保留 Kotlin + Compose + Material 3；使用 Android 通知服务、独立纯 Kotlin 解析器、本地仓库和响应式界面。以本机保存为默认行为，支持手工补录与 CSV 导出。
- 来源先做包名白名单；微信还需支付专用标题/可信支付文本线索，避免普通聊天中的“付款成功 20 元”。成功、失败、取消、待领取应分别判定。
- 金额用 `Long` 分、十进制字符串精确换算；关联支付动词附近金额，避免识别余额、订单号、优惠额。多金额或方向冲突进入待确认。
- 去重优先使用通知 key 与相同正文的持久化指纹；不要简单合并相邻同价消费。编辑/撤销应保留已处理指纹，避免通知重发将用户删除的误识别重新入账。
- 设置页展示通知授权、监听连接状态、每个来源开关与数据保存说明。初版不需要短信、无障碍或悬浮窗权限。
- 用可复现通知样本测试：支出/收入/退款、支付失败、待收款、聊天金额、余额与交易金额同时存在、小数精度、通知更新重发、相邻两笔同价交易。测试样本属于模拟输入；实际微信/支付宝与各厂商手机匹配情况需装机验证。

以上是本项目独立实现的建议，不构成对第三方 App 实际可靠性或真实账单完整性的保证。
