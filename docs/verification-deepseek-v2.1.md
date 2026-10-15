# 2.1 DeepSeek 助手验证记录

验证日期：2026-10-15（Asia/Shanghai）。产品为 `versionName 2.1`、`versionCode 5`、数据库版本 3。2.0／2.0.1 安装包不含本轮助手。新版本使用 [v2.1-test](https://github.com/le5676922-max/jizhang-qingji-app/releases/tag/v2.1-test) 对应源码与资产，不复用旧版测试数量。

## 本轮变更

底部增加「助手」，接入固定官方 HTTPS 接口，提供四个受限本地工具：生成一笔未保存草稿、按日／月计算收支、解释待确认计数、检查连接。查询复用正式账本的退款与往来统计。模型不能直接保存、删除、确认账单、修改权限或操作付款应用；草稿需要原编辑器人工保存。

API Key 由 AndroidKeyStore AES-GCM 加密保存在独立偏好中，不进入财务备份。默认只发送本次输入及固定规则；一次单独勾选后，可续轮发送固定分类的收支或待确认聚合结果，未提供明细、商户、备注、单号、截图及连接数据的工具序列化通道。用户主动写进输入框的内容仍会发送。每次请求独立，不携带历史聊天。

本轮还将高频识别诊断改为只读诊断表；故障注入测试验证，不需要重读金融表，也不清除已有存储错误。没有据此声称某个未经测量的提速比例。

## 实际执行

构建命令：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8" --console=plain
```

| 检查 | 结果与证据 |
| --- | --- |
| 冻结源码后的完整构建 | BUILD SUCCESSFUL，1 分 43 秒；`docs/evidence/v2.1-build-final-frozen.txt` |
| JVM | 229 项通过，0 失败、0 错误、0 跳过；Gradle `app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| Lint | 0 错误、29 警告；`app/build/reports/lint-results-debug.xml` |
| API 24／Android 7.0，x86_64，4 KB | 完整 193／193 通过，155.658 秒；`docs/evidence/v2.1-api24-full-final.txt` |
| API 37，x86_64，4 KB，只读模拟器 | 完整 193／193 通过，213.358 秒；`docs/evidence/v2.1-api37-full-final.txt` |
| 最终产品手动冒烟 | API 37 打开真实 MainActivity → 助手 → 本月收支 → 本地查询；未配置 Key 时显示本地核算、0 笔正式记录及 0.00 元，与空账本一致，页面与四个导航入口可见；截图仅保留在本地 `docs/evidence/v2.1-api37-agent.png`、`v2.1-api37-local-query.png` |
| APK 元数据 | 包名 `com.example.jizhang`，最低 API 24、目标 API 37；包含 arm64-v8a、armeabi-v7a、x86、x86_64 |
| 权限 | 产品新增 INTERNET；不声明 ACCESS_NETWORK_STATE、短信、通讯录或外部存储读取权限；禁用明文流量 |
| 签名及 ZIP 对齐 | APK v2 验证通过，发布者证书与此前版本一致；`zipalign -c -P 16 4` 通过。ZIP 对齐不等于所有 ARM64／16 KB 真机已验证 |

新增 24 项 JVM 工具测试；新增 58 项设备测试：工具计算线程与取消 2、Agent 会话与参数校验 14、HTTPS 客户端 10、协议 8、系统密钥库 7、助手 UI 13、编辑器集成 2、诊断故障注入 2。其余测试覆盖已有账本、识别、迁移、退款、OCR、图形 JNI、完整备份及界面回归。

DeepSeek 相关测试使用合成 Key、内存 HTTPS 连接和模拟模型回复，**没有使用真实付费 Key 或请求正式 DeepSeek API**。验证了格式、边界、状态和本地流程，不等于有效账户、真实网络及实际模型输出已验收。

API 24 首轮 193 项中有两项测试失败：集成测试未等待新请求真正开始，保存提示也会遮住滚到底部的发送按钮；清除 Key 测试误用完整文本匹配。修正测试为关闭真实 Snackbar、确认请求计数增长及等待结果，并保留最新账本和金额断言；密钥状态改为匹配完整文字。首次复跑仍暴露未发起第二次请求，继续保留失败日志。最终编辑器单项通过（7.47 秒），随后完整 193 项通过。没有放宽生产金额规则、账本断言或通过率标准。

失败记录分别为 `v2.1-api24-full-instrumentation.txt`、`v2.1-api24-full-instrumentation-rerun.txt`、`v2.1-api24-agent-editor-focused.txt`；成功单项为 `v2.1-api24-agent-click-diagnostic.txt`。这些文件与最终日志都留在本地忽略目录，公开仓库提供测试源码和此摘要，不把本地路径写成公开下载链接。

## 安装包

资产名 `qingji-v2.1.apk`，58,036,399 字节，SHA-256：

```text
F35C3025F17EAF6E67289CBC9F5EFBC1430F2A9D62085EC17006DB5283F0676F
```

发布者 Debug 签名证书 SHA-256：

```text
64e32611bd105a2b68a15e44b2c785433274ac3c1f416525f1056c2fc2de0498
```

同签名可覆盖此前发布包；请先导出完整备份，保留应用数据。自行构建默认使用自己的 Debug 证书，不能据此保证覆盖发布者包。签名私钥及任何真实 API Key 均不公开上传。

## 验证边界

三星 S23 Ultra 上「已授权，等待系统连接」的根因仍未查明，模型不能替代系统绑定或授权。本轮没有将模拟器结果当作真实微信、支付宝或各品牌手机的付款覆盖率。

自动截图至少 API 30，安全窗口、隐藏节点、无通知／可读结果页时仍不能采集。主账单编辑器旋转后会丢失未保存草稿；已保存账目不受影响。API 35／16 KB 历史长进程 ART/JIT 崩溃及 ARM64 真机覆盖限制保留在 [兼容说明](android-compatibility.md)，本轮没有重新认证该环境。

2.1 声明网络权限，OCR 仍在设备端执行，但 ML Kit 可能发送 SDK 使用、性能和设备诊断指标。关闭 DeepSeek 不会从安装包撤销网络权限。详见 [DeepSeek 配置与数据流](deepseek-agent.md) 和 [隐私说明](privacy.md)。
