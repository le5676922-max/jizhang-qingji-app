# 轻记通知测试夹具

> 下文场景数量、转账归类与退款收入的预期对应历史 1.0 / 1.1 验证。当前主工程是 V2 开发快照，候选与正式账目开始分离；尚未将这些历史端到端断言更新为 V2 全流程验证。详情见 [开发状态](../../docs/development-status.md)。

此目录是独立 Android Gradle 工程，使用 Java Activity，没有第三方依赖。它发送固定合成通知，并显示固定的原生节点、Canvas 收据、受保护窗口及聊天界面，用于验证轻记的通知和屏幕记账链路。不是微信或支付宝，也不包含支付功能。

**只安装到专用、可丢弃的测试模拟器。绝不安装到已有微信或支付宝的真实手机或个人设备。** 为通过被测应用的包名白名单，两个 flavor 分别使用 `com.tencent.mm` 与 `com.eg.android.AlipayGphone`，因此会与真实支付应用发生包名和签名冲突；不要卸载真实应用来安装夹具。应用名称明确显示“轻记通知与屏幕测试夹具”。主产品不包含此夹具，也不申请 `POST_NOTIFICATIONS`。

## 构建

需要 Android SDK 37 和适用于 AGP 9.4.1 的 JDK。Gradle Wrapper 从主工程原样复制。主工程的 `settings.gradle.kts` 不包含此工程。

从仓库根目录执行：

```powershell
.\tools\notification-fixture\gradlew.bat --project-dir .\tools\notification-fixture :app:assembleWechatDebug :app:assembleAlipayDebug
```

本机 `local.properties` 已从主工程复制且被忽略；换机器后自行配置 Android SDK 路径，或设置 `ANDROID_HOME`。

APK 输出：

- `tools/notification-fixture/app/build/outputs/apk/wechat/debug/app-wechat-debug.apk`
- `tools/notification-fixture/app/build/outputs/apk/alipay/debug/app-alipay-debug.apk`

## 安装与授权

下列命令的 `<TEST_EMULATOR_SERIAL>` 必须替换为专用测试模拟器序列号。先安装并启动轻记，授予轻记通知访问权限，在应用内开启自动记账，并确认显示已连接。

```powershell
adb -s <TEST_EMULATOR_SERIAL> install -r .\tools\notification-fixture\app\build\outputs\apk\wechat\debug\app-wechat-debug.apk
adb -s <TEST_EMULATOR_SERIAL> install -r .\tools\notification-fixture\app\build\outputs\apk\alipay\debug\app-alipay-debug.apk
adb -s <TEST_EMULATOR_SERIAL> shell pm grant com.tencent.mm android.permission.POST_NOTIFICATIONS
adb -s <TEST_EMULATOR_SERIAL> shell pm grant com.eg.android.AlipayGphone android.permission.POST_NOTIFICATIONS
```

`POST_NOTIFICATIONS` 仅在 Android 13 / API 33 及以上授权；旧版本跳过这两条授权命令。夹具不会弹出授权请求。

## 触发场景

使用导出的 `FixtureActivity`，Intent String Extra 名称为 `scenario`。Activity 发送通知后立即结束，没有支付界面。可替换包名以测试支付宝 flavor。

```powershell
adb -s <TEST_EMULATOR_SERIAL> shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario payment1
adb -s <TEST_EMULATOR_SERIAL> shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario update
adb -s <TEST_EMULATOR_SERIAL> shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario payment2
adb -s <TEST_EMULATOR_SERIAL> shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario income
adb -s <TEST_EMULATOR_SERIAL> shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario refund
adb -s <TEST_EMULATOR_SERIAL> shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario failed
adb -s <TEST_EMULATOR_SERIAL> shell am start -W -n com.tencent.mm/com.example.ledger.fixture.FixtureActivity --es scenario chat
```

| scenario | notification id | 内容 | 预期 |
| --- | ---: | --- | --- |
| payment1 | 100 | 支付成功 12.30 元 | 新增一笔支出 |
| update | 100 | 同笔支付补充商户：麦当劳 | 不新增账单 |
| payment2 | 101 | 第二笔支付成功 12.30 元 | 同额不同通知，新增支出 |
| income | 102 | 收款到账 100 元 | 新增收入 |
| refund | 103 | 退款成功 5 元 | 新增收入 |
| failed | 104 | 支付失败 20 元 | 忽略 |
| chat | 105 | 张三发来“支付成功 10 元” | 忽略普通聊天 |

每个 flavor 的 `payment1` 与 `update` 均使用 tag `fixture`、id `100`、SharedPreferences 保存的首次事件时间。更新会改变 `postTime` 和商户文本，保留 `notification.when`，用于回归稳定交易身份去重。其余场景使用独立 id；不会设置 group summary。两种 flavor 运行全部场景，各应产生 2 笔支出和 2 笔收入，合计 4 笔账单。

请先触发 `payment1` 再触发 `update`，两条之间允许有任意延迟。重复运行整套场景时，独立场景的新 `when` 通常意味着新交易；需要从干净测试账本进行数量断言。夹具只在本地测试系统日志中写固定的权限或场景错误，不写 Intent 参数、通知全文或真实付款数据。

此测试证明被测应用的通知处理链路；它不证明真实微信、支付宝会使用相同模板或在所有付款场景发送可识别通知。若支付提供方复用相同包名、key、when、金额和方向来表示另一笔交易，通知身份无法区分两笔，需要手动核对。

## 屏幕识别场景（仅 emulator-5554）

屏幕场景使用导出的 `TransferResultActivity`，不会发通知，也不会执行转账。界面持续停留，可在相同 Activity 上切换场景。下列命令只允许用于本项目专用测试模拟器 `emulator-5554`；执行前先确认该序列号属于可丢弃的测试环境。

在轻记中开启“屏幕识别”，授权无障碍服务并确认已连接。原生节点场景不需要开启通知自动记录或 OCR；`screen-ocr` 与 `screen-secure` 需要额外开启“本地截图识别”。两个 flavor 都使用相同场景；替换组件包名前缀即可验证支付宝。

```powershell
adb -s emulator-5554 shell am start -W -n com.tencent.mm/com.example.ledger.fixture.TransferResultActivity --es scenario screen-transfer
Start-Sleep -Seconds 3
adb -s emulator-5554 shell am start -W -n com.tencent.mm/com.example.ledger.fixture.TransferResultActivity --es scenario screen-transfer2
Start-Sleep -Seconds 3
adb -s emulator-5554 shell am start -W -n com.tencent.mm/com.example.ledger.fixture.TransferResultActivity --es scenario screen-redpacket
Start-Sleep -Seconds 3
adb -s emulator-5554 shell am start -W -n com.tencent.mm/com.example.ledger.fixture.TransferResultActivity --es scenario screen-ocr
Start-Sleep -Seconds 9
adb -s emulator-5554 shell am start -W -n com.tencent.mm/com.example.ledger.fixture.TransferResultActivity --es scenario screen-secure
Start-Sleep -Seconds 3
adb -s emulator-5554 shell am start -W -n com.tencent.mm/com.example.ledger.fixture.TransferResultActivity --es scenario screen-chat
Start-Sleep -Seconds 3
```

每次开启场景后等待至少 3 秒，留出窗口去抖、识别和写入时间。任何两个 OCR / 安全截图场景之间至少等待 9 秒，以免 8 秒截图限频掩盖实际截屏请求结果。

| scenario | 显示内容 / 单号 | 可访问节点 | 预期 |
| --- | --- | --- | --- |
| screen-transfer | 转账成功 12.30 元，`SCREEN_TRANSFER_001` | 原生标题、成功状态、金额、收款人、单号 | 新增 12.30 元支出；重复打开或停留不新增 |
| screen-transfer2 | 转账成功 12.30 元，`SCREEN_TRANSFER_002` | 原生收据节点 | 同金额不同单号，新增另一笔支出 |
| screen-redpacket | 红包已发出 5.00 元、待领取，`SCREEN_RED_PACKET_01` | 原生红包详情节点 | 记录已经发出的 5.00 元支出；待领取不被误作收入 |
| screen-ocr | 转账成功 28.56 元，`SCREEN_OCR_001` | 原生标题“转账详情”；结果、金额和单号只有 Canvas 像素 | 节点识别无法得到金额；OCR 关闭时不入库，OCR 开启后新增待确认支出 |
| screen-secure | 与 OCR 相同布局，单号 `DIFFERENT_SECURE_001`，设置 `FLAG_SECURE` | 仅原生“转账详情”标题 | 安全窗口拒绝截图；不新增记录，状态提示保护窗口，不回退绕过保护 |
| screen-chat | 聊天信息、转账成功、66.66 元、可编辑聊天输入框 | 包含 editable 节点 | 跳过节点和 OCR 识别，不新增记录 |

Canvas View 标记 `IMPORTANT_FOR_ACCESSIBILITY_NO`、无 `contentDescription`；它没有可访问的成功状态、金额或单号。`screen-secure` 使用不同单号，因此“不入库”不能用已见交易去重来解释。聊天场景的输入框保持可见但不主动弹出键盘。

支付宝示例：

```powershell
adb -s emulator-5554 shell am start -W -n com.eg.android.AlipayGphone/com.example.ledger.fixture.TransferResultActivity --es scenario screen-transfer
```

从干净账本完成六个场景，每个 flavor 预期共 4 个支出候选：两笔 12.30 元、一笔 5.00 元及一笔 OCR 待确认的 28.56 元；待确认项是否计入统计按主产品的确认规则处理。安全窗口及聊天场景应保持账本数量不变。

这些合成界面验证节点、离线 OCR、单号去重及保护窗口的处理链路，不代表真实微信或支付宝在某个版本公开同样的节点、布局或截图权限。
