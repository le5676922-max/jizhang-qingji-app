# 轻记验证记录

> 这是 1.0 的历史验证记录，不代表V2测试结果。当前交付见[2.0验证记录](verification.md)；原始证据及截图未随公开仓库上传，见 [首次公开上传说明](publication.md)。

日期：2026-10-13（Asia/Shanghai）。交付为可安装的 debug 测试版，不含测试账单或通知夹具。

## 验证结果

| 检查 | 实际结果 |
| --- | --- |
| `:app:assembleDebug` | 构建成功 |
| `:app:testDebugUnitTest` | 32 项通过：金额 4、分类 2、解析 21、通知身份 4、原模板 1 |
| `AndroidJUnitRunner`，Android 37 模拟器 | `OK (14 tests)`：存储 9、Compose 用户流程 4、应用上下文 1 |
| `:app:lintDebug` | 0 errors；剩余警告为原模板旧依赖、未使用资源与 KTX 建议等 |
| APK 签名 | `apksigner verify --verbose` 验证通过，APK Signature Scheme v2 |
| APK 权限清单 | 只有 AndroidX 生成的本应用非导出广播接收权限，无网络、短信或无障碍权限 |
| 系统通知端到端验证 | 14 个合成通知场景正确生成 8 笔有效账单 |

安装包：`deliverables/轻记-debug.apk`，12,272,199 字节。SHA-256：

`2DA7BAC09AC5655C2487A9A956E5D9597A6002F890C66ED5716F17F3C76E7B7C`

构建沿用 AGP 9.4.1、Kotlin 2.2.10、Compose BOM 2025.12.00、compile/target SDK 37、min SDK 24。原模板 Espresso 3.5.1 使用的新系统已删除的 InputManager 反射方法导致 UI 测试初始化失败；按 [AndroidX 官方发布说明](https://developer.android.com/jetpack/androidx/releases/test)更新 Espresso 3.7.0 与 AndroidX JUnit 1.3.0 后，所有实际流程测试通过。最后测试直接用专用 ADB 服务运行 AndroidJUnitRunner，原始输出保存在 `docs/evidence/instrumentation-result.txt`。

## 实际设备测试覆盖

- 同一事件并发回调仅入库一次，两笔相同金额、不同事件均保留。
- 同一通知补商户、改变 postTime 后仍去重；删除及重启后不会恢复。
- 编辑保留来源和事件指纹；金额越界拒绝，整数分精确保存。
- CSV 处理中文、引号、换行和公式前缀。
- 自动记账默认关闭，设置与开启时间持久化。
- 漏记提醒在下一笔成功与重启后仍保留，手动确认后清除。
- 手动录入 12.34 元，概览总额及账单正确；12.345 元拒绝入库。
- 工资收入分类与月份切换筛选正确。
- 通知解析预览不写入账本。

每项 instrumentation 测试使用随机独立数据库与偏好文件，测后清理，不操作用户账本。

## 系统通知接收链路

在本次启动的专用 Android 模拟器安装了 `tools/notification-fixture` 两个 flavor。它们明确名为“轻记通知测试夹具”，只生成固定样本；为测试白名单使用微信/支付宝包名，没有真实支付功能。

通过系统授予通知访问，并在产品界面实际打开自动记录开关；界面显示已授权、监听已连接。每个来源依次触发：

| 合成场景 | 预期与实际 |
| --- | --- |
| 第一笔付款 12.30 元 | 新增支出 |
| 同一通知更新商户，postTime 变化 | 不新增 |
| 独立第二笔付款 12.30 元 | 仍新增，保留同价交易 |
| 收款到账 100 元 | 新增收入 |
| 退款成功 5 元 | 新增退款收入 |
| 支付失败 20 元 | 忽略 |
| 张三聊天“支付成功10元” | 忽略 |

两个来源共 8 笔，支出 49.20 元、收入 210.00 元。独立 Python/SQLite 核对来源、类型、金额和数量均符合预期，`pass=true`；数据库快照与结果位于 `docs/evidence/notification-e2e.sqlite` 和 `notification-e2e.json`。这些证据仅包含合成测试数据。通知夹具未包含在交付 App 中。

验证结束后已清除该模拟器中的合成账单、卸载两个测试夹具、撤销测试通知授权，并关闭本次启动的后台模拟器。交付安装包不附带测试数据。

## 覆盖边界

已验证的是模拟器的系统通知回调、本地解析、存储和 UI 流程。未使用真实微信/支付宝账户交易，未测试各厂商手机省电策略，也未实测全部 Android 7–17 版本。支付应用未发送系统通知时无法自动记账，未知通知格式会忽略，需要手动补录。

REA 的第三方 APK 档案分析完成，DEX 反编译因缺少 JADX provider 未完成；源码分析和证据边界见 `docs/research.md`。没有执行第三方支付 App，也没有读取用户金融账户。

截图：`docs/screenshots/overview.png` 为初始空账本，`automation.png` 为真实授权/连接后的设置页，`notification-e2e.png` 仅显示合成通知验证数据。
