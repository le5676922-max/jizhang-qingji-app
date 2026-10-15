# 轻记通知与屏幕测试夹具（V2）

这是独立 Android Gradle 工程，使用 Java Activity，没有第三方依赖。它发送固定合成通知，并显示原生节点、Canvas 收据、受保护窗口及聊天界面，用于验证轻记的采集、来源关联和经济用途链路；没有真实支付功能。

**只安装到专用、可丢弃的测试模拟器。不得安装到已有微信／支付宝的真实手机或个人设备，也不要卸载真实支付应用来安装夹具。** 两个 flavor 为匹配白名单使用 `com.tencent.mm` 与 `com.eg.android.AlipayGphone`，会与真实支付应用产生包名／签名冲突。名称明确显示“轻记通知与屏幕测试夹具”；主产品不包含此工程，也不申请 `POST_NOTIFICATIONS`。

本文对应 V2 的经济类型与正式账本／来源事件分离规则，更新于 2026-10-15。历史 1.0 曾把退款计入收入，1.1 曾把转账按资金转出统计；这些历史断言不适用于 V2，旧结果见 [1.0 验证](../../docs/verification-notification-v1.md) 和 [1.1 验证](../../docs/verification-screen.md)。本次 API 37 专用模拟器端到端已执行，并经只读 SQL 核验通过；准确范围见 [V2 验证报告](../../docs/verification.md)。

主工程的最终设备集合为 124 项：API 24 全量通过 201.18 秒；将实际合成字图固定为测试 asset 后，API 37 全量通过 145.048 秒，API 24／API 35 的 3 项 OCR 也分别复核通过。固定测试输入没有改变产品源码或 APK。这些是合成测试结果，不能代表真实支付应用页面覆盖率；API 35 长进程限制见主验证报告。

## 构建与隔离环境

需要 Android SDK 37 和适用于 AGP 9.4.1 的 JDK；Wrapper 从主工程复制，主工程 settings 不包含此工程。

从仓库根目录执行：

```powershell
.\tools\notification-fixture\gradlew.bat --project-dir .\tools\notification-fixture :app:assembleWechatDebug :app:assembleAlipayDebug
```

本机 `local.properties` 被忽略；换机器后配置 SDK 路径或 `ANDROID_HOME`。输出：

- `tools/notification-fixture/app/build/outputs/apk/wechat/debug/app-wechat-debug.apk`
- `tools/notification-fixture/app/build/outputs/apk/alipay/debug/app-alipay-debug.apk`

以下 `$fixtureSerial` 必须替换为本次新建的专用测试模拟器。ADB 端口应与自己的专用服务器一致；示例使用 5038，并不要求模拟器固定名为 emulator-5554。先核对序列号、模拟器属性和空白环境，再执行安装：

```powershell
$fixtureSerial = '<TEST_EMULATOR_SERIAL>'
$fixtureAdbPort = 5038
adb -P $fixtureAdbPort devices -l
adb -P $fixtureAdbPort -s $fixtureSerial shell getprop ro.kernel.qemu
```

`ro.kernel.qemu` 应为 `1`，但这不能代替确认模拟器是本项目可丢弃环境、没有真实支付数据。若不确定，停止安装。

```powershell
adb -P $fixtureAdbPort -s $fixtureSerial install -r .\tools\notification-fixture\app\build\outputs\apk\wechat\debug\app-wechat-debug.apk
adb -P $fixtureAdbPort -s $fixtureSerial install -r .\tools\notification-fixture\app\build\outputs\apk\alipay\debug\app-alipay-debug.apk
adb -P $fixtureAdbPort -s $fixtureSerial shell pm grant com.tencent.mm android.permission.POST_NOTIFICATIONS
adb -P $fixtureAdbPort -s $fixtureSerial shell pm grant com.eg.android.AlipayGphone android.permission.POST_NOTIFICATIONS
```

两条 `POST_NOTIFICATIONS` 授权只用于 API 33 及以上，旧系统跳过。先安装并启动轻记，设置来源开关、授予通知／无障碍相应权限并确认服务“已连接”。通知自动记录、屏幕识别、自动 OCR 分别开启；导入图片可以独立测试。首次接收通知必须晚于通知开关启用与监听连接时间。

数量断言使用干净的测试账本与夹具数据。固定订单号重复触发仍表示同一笔合成交易，不会成为新交易。`payment1` 的首次时间在夹具偏好中持久保存，`install -r` 不会清掉它；旧首次时间可能被产品启用时间门禁忽略。重新跑整套时应使用新建的干净测试环境，不操作个人账本。

## 通知场景

导出的 `FixtureActivity` 接收 String Extra `scenario`，发送通知后结束。将组件包名前缀换为 `com.eg.android.AlipayGphone` 可运行支付宝 flavor。

```powershell
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario payment-order
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario payment-order2
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario refund-order
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario refund-order2
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario refund-over
```

| scenario | notification id | 固定证据 | V2 预期 |
| --- | ---: | --- | --- |
| payment-order | 106 | 合成便利店付款 28.00 元，provider 单号 `SYN_PAY_0001` | 已完成、稳定身份且无冲突时生成一笔消费；后续同单页面证据关联它 |
| payment-order2 | 107 | 同商户同金额，单号 `SYN_PAY_0002` | 另一笔消费；不能因金额相等合并 |
| refund-order | 108 | 退款 5.00 元，退款单 `SYN_REFUND_0001`，原 provider 单 `SYN_PAY_0001` | 原消费唯一有效且额度足够时生成关联退款，抵扣净支出；不计收入 |
| refund-order2 | 109 | 同原消费再退 3.00 元，独立退款单 `SYN_REFUND_0002` | 独立部分退款，累计 8.00 元；不与前笔合并 |
| refund-over | 110 | 退款 50.00 元，单号 `SYN_REFUND_0003`，同原消费 | 超过原消费剩余额度，不能正式入账；需要核对，不改变净支出 |

微信 provider 字段为“微信支付交易单号”，支付宝为“支付宝交易号”。退款自身单号与原支付单号承担不同角色，不能把两笔退款或退款与原付款当成同一笔。

兼容通知场景仍保留，可验证旧模板和更新：

| scenario | id | 固定内容 | V2 预期 |
| --- | ---: | --- | --- |
| payment1 | 100 | 支付成功 12.30 元 | 新的可靠付款通知可计为消费 |
| update | 100 | 同笔 12.30 元补商户“麦当劳”，共享首次 when | 更新同一来源，不新增账单；先运行 payment1 |
| payment2 | 101 | 独立付款 12.30 元、新 when | 不同事件，不按同金额永久合并 |
| income | 102 | 收款到账 100 元，无商户／工资或原单事实 | 资金转入不等于新增收入；经济用途不明时 REVIEW，人工核对 |
| refund | 103 | 退款成功 5 元，无原消费标识 | 未关联唯一原消费时 REVIEW；不能作为新增收入自动入账 |
| failed | 104 | 支付失败 20 元 | 不入正式账本 |
| chat | 105 | 标题“张三”，正文“支付成功10元” | 聊天标题拒绝，不采集付款正文 |

兼容场景示例：

```powershell
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario payment1
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario update
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario payment2
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario refund
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario failed
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario chat
```

各通知用 tag `fixture`，不设置 group summary；两 flavor 不能沿用历史“每个 2 支出 + 2 收入”数量断言。夹具只记录固定权限／场景错误，不输出 Intent 参数、通知全文或真实数据。

## 屏幕节点、OCR 与安全场景

导出的 `TransferResultActivity` 接收相同 `scenario` Extra，保留前台界面；`singleTop` 更新会发送窗口内容事件。它不会发通知或执行转账。开启屏幕识别并确认无障碍已连接；OCR 场景还需开启“结果页截图识别”。

```powershell
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.tencent.mm/com.example.ledger.fixture.TransferResultActivity --es scenario screen-payment
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.tencent.mm/com.example.ledger.fixture.TransferResultActivity --es scenario screen-refund
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.tencent.mm/com.example.ledger.fixture.TransferResultActivity --es scenario screen-transfer
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.tencent.mm/com.example.ledger.fixture.TransferResultActivity --es scenario screen-ocr
```

每个原生页面等待至少 3 秒，为窗口内容稳定、去抖与写入留出时间；上述命令应逐条运行并等待实际状态。自动 OCR 至少 8 秒限频，两个 OCR／安全截图场景间等待至少 9 秒并核对诊断；首次冷模型或 APK 更新后需要更长稳定停留，实际本次曾等待 16–25 秒。窗口变化会使旧结果失效，不能因没有截图结果就断言安全窗口保护生效。

| scenario | 页面证据／单号 | V2 预期 |
| --- | --- | --- |
| screen-payment | 付款详情、支付成功、实付 28.00 元、合成便利店、provider 单 `SYN_PAY_0001` | 原生节点消费；与 payment-order 同单可信通知关联到同一账单，不新增第二笔 |
| screen-payment2 | 同金额、单号 `SYN_PAY_0002` | 与第二笔订单关联；不同单号保持独立 |
| screen-refund | 退款 5.00 元、`SYN_REFUND_0001`、原 provider 单 `SYN_PAY_0001` | 关联退款；与同单退款通知只对应一笔正式退款，不当收入 |
| screen-refund2 | 同原单再退 3.00 元、`SYN_REFUND_0002` | 独立部分退款，累计额度校验 |
| screen-refund-over | 同原单退 50.00 元、`SYN_REFUND_0003` | 超额不得正式入账，不改变净支出 |
| screen-transfer | 转账成功 12.30 元，`SCREEN_TRANSFER_001` | TRANSFER 的 REVIEW 来源；未确认不建正式账单，转账本身不自动计为消费 |
| screen-transfer2 | 同金额独立 `SCREEN_TRANSFER_002` | 另一条转账候选；不同 ID 不合并，仍不自动增加消费 |
| screen-redpacket | 红包已发出 5.00 元、待领取、`SCREEN_RED_PACKET_01` | 已发出／扣款证据构成资金转出，按当前红包用途规则识别消费；只有待领取而无发出证据不能成立 |
| screen-ocr | 原生“转账详情”标题；Canvas 转账 28.56 元、`SCREEN_OCR_001` | 节点无法得到金额；OCR 关闭时无完整候选，开启后本地 OCR 形成 REVIEW 转账来源，不自动进入消费 |
| screen-secure | 同 Canvas 布局、不同 `DIFFERENT_SECURE_001`，`FLAG_SECURE` | 系统拒绝截图或不提供交易像素，不能形成候选；不同单号保证结果不能以旧事件去重解释，不绕过保护 |
| screen-chat | 聊天标题、转账成功 66.66 元、可编辑聊天框 | 输入／聊天门禁拒绝节点与 OCR，不形成交易账目 |

Canvas 标记 `IMPORTANT_FOR_ACCESSIBILITY_NO`，没有 contentDescription；成功状态、金额和单号只有像素。聊天输入框可见，但不主动弹出键盘。API 24–29 不支持自动截屏，可用允许截图后的系统选择器导入；导入依然是待确认来源。API 30–33 使用显示级截图和内存裁剪，并在各阶段复核浮窗；API 34+ 使用指定窗口截图。所有模式都不能绕过安全页面。

支付宝示例：

```powershell
adb -P $fixtureAdbPort -s $fixtureSerial shell am start -W -n com.eg.android.AlipayGphone/com.example.ledger.fixture.TransferResultActivity --es scenario screen-payment
```

## V2 已执行的端到端断言

API 37 专用模拟器使用干净合成账本，两来源分别执行了以下场景：

1. 两笔 28.00 元付款订单，分别补充同单页面证据，验证一笔账关联多来源。
2. 一个已发出 5.00 元红包。
3. 对第一笔 28.00 元消费做 5.00 元和 3.00 元部分退款，并补充相同退款页面证据。
4. 触发超额退款、两笔转账、OCR、受保护窗口和聊天，核对它们不直接增加消费净额。失败通知另有解析与设备测试覆盖，不把未记录的夹具动作写成此次端到端已运行。
5. 查看识别中心，核对转账／OCR REVIEW 与正式账目分离，再检查数据库关系、事件数量、净额和分类。

本次只读数据库核验结果：

| 实际指标 | 每个合成支付来源 | 两来源合计 |
| --- | ---: | ---: |
| 正式账目 | 5（3 消费、2 退款） | 10 |
| 消费毛额 | ¥61.00 | ¥122.00 |
| 有效退款 | ¥8.00 | ¥16.00 |
| 净支出 | **¥53.00** | **¥106.00** |
| 新增收入 | ¥0.00 | ¥0.00 |
| 来源事件 | 14（9 AUTO、5 REVIEW） | 28 |

两笔同金额不同单号的付款保持独立；两笔付款及两笔退款的通知／节点分别关联到同一正式账单。两笔 12.30 元转账、28.56 元 Canvas OCR，以及通知／节点的 50.00 元超额退款保持未入账 REVIEW。聊天 66.66 元和指定受保护订单无候选；还实际观察到「支付窗口受保护，系统拒绝截图」诊断，独立验证了系统拒绝路径。仅数据库里没有订单不足以证明截图拒绝，需同时保存运行诊断。

这些金额不包含兼容 payment1／payment2，混跑会改变净额。来源事件数量是本次运行观察值，重放或页面重绘可能产生不同数量，核验重点是正式账目、身份／关联和保守入账规则。

在只含本夹具数据的完整 SQLite 快照上运行仓库中的严格只读核验器：

```powershell
python .\tools\verify_v2_db.py '<SYNTHETIC_V2_SQLITE_SNAPSHOT>' --output '<LOCAL_RESULT_JSON>'
```

脚本使用 SQLite `mode=ro`，逐项检查订单命名空间／哈希、可靠双通道配对、退款原消费与额度、REVIEW 未入账、不同单号独立和净额，失败返回非零状态。本机本次 `v2-e2e-final.json` 为 `verified: true`；原始快照、结果、截图被 Git 忽略，未发布真实或合成账本文件。脚本用于这组固定合成场景，不能拿任意个人账本套用预期金额。

纯 fixture 不证明真实微信／支付宝版本采用相同模板、开放相同节点或允许截图，也不证明 S23 Ultra 后台与受限设置行为。被测产品通过系统显示“已授权”仍需确认服务实际连接；失败时保留诊断与合成证据，不对个人设备或真实账户执行修复操作。
