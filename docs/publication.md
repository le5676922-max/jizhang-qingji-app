# 首次公开上传说明

上传日期：2026-10-14（Asia/Shanghai）。项目名称「记账轻记app」，GitHub 仓库路径 `le5676922-max/jizhang-qingji-app`。

## 源码与安装包

首次上传时，`main` 保存 V2.0 开发快照，应用配置为 `versionName=2.0`、`versionCode=3`。该首次快照不代表当时已完成 V2 发行版本；后续开发与实际验证见 [开发状态](development-status.md)。

首次公开的预发布 `initial-public-snapshot` 附带此前已交付的 `qingji-v1.1.apk`，其版本为 1.1、versionCode 2。该 APK 不是从本次 V2 源码快照构建的安装包；没有保留可精确复现 1.1 APK 的历史源码提交，不将这个标签称为 1.1 源码版本。

APK 大小为 57,162,073 字节，SHA-256：

```text
CD7FD5662B425D73F306E61A7E78441D3174E05A29885528BA70F66661DECE4D
```

上传前重新检查了包名 `com.example.jizhang`、最低 API 24、目标 API 37，以及 APK Signature Scheme v2 验证结果。安装包采用 Android Debug 签名，供测试使用。开发者自行构建默认生成自己的 Debug 密钥，不能据此保证覆盖安装此预编译 APK；发布者的签名私钥没有上传。

## 本次验证

在上传前实际执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8" --console=plain
```

结果：BUILD SUCCESSFUL，183 项 JVM 单元测试通过，0 失败、0 错误、0 跳过；Lint 0 错误、25 警告。应用及设备测试 APK 均构建成功。

此前 V2 Phase 3 在专用 API 37 模拟器通过 58 项设备测试。首次上传时新增的设备测试仅完成编译，没有将该旧结果当作最新全部设备测试通过的证据。真实微信、支付宝和三星手机上的支付覆盖率仍未完成系统验证。1.0 与 1.1 的历史验证分别见 [通知版验证](verification-notification-v1.md) 和 [屏幕版验证](verification-screen.md)。

## 公开文件范围

仓库包含产品源码、资源、测试源码、Gradle Wrapper、独立合成测试夹具、调研和验证说明。`.gitignore` 排除本机 SDK 路径、IDE/Gradle 缓存、构建产物、日志、数据库、签名密钥、本地代理状态及原始证据目录。

`docs/evidence/` 与 `docs/screenshots/` 只保留在开发工作区，没有公开上传。历史报告提到这些路径时指本地归档，不是仓库内可下载文件。第三方调研 APK 不重新分发；参考项目来源与许可信息保留在调研文档中。

产品不保存通知全文、OCR 全文或屏幕图片；公开仓库不包含用户真实账单或手机截图。测试夹具全部使用固定合成场景，不能安装到已有微信/支付宝的个人手机。

仓库尚未选择项目源码许可证，公开可见性不代表已添加 MIT、Apache 或 GPL 许可证；第三方依赖仍适用各自许可证。

## 2.0 测试版后续交付

2026-10-15 的交付文件为 `qingji-v2.0.apk`，57,543,659 字节，SHA-256 为 `4681BA82C750AA838B51DAA7CFE9D16AFD2FAC1DB80DF1AD43C268797BE2741D`。它由本次 V2 源码构建，使用与此前 1.1 相同的发布者 Debug 证书；对应标签及资产入口为 [v2.0-test](https://github.com/le5676922-max/jizhang-qingji-app/releases/tag/v2.0-test)。

产品无网络权限，四种 ABI、API 24 起分级能力、旧账迁移、统一识别、退款去重与完整加密恢复已实现。199 项 JVM 通过，API 24 与 API 37 均有 124 项全量通过记录；各系统实际设备测试、端到端、SAF 恢复与 16KB 原生运行的已知限制以 [本次验证报告](verification.md) 和 [兼容说明](android-compatibility.md) 为准。不将测试覆盖等同所有品牌真机认证。


## 2.0.1 连接诊断测试版

本版对应 v2.0.1-test、versionName 2.0.1／versionCode 4，安装包 qingji-v2.0.1.apk 为 57,868,874 字节，SHA-256：785109AC407989260CC97A003F1C31085A2187CC29E8C0938F407CFA585D2A99。与此前发布者签名一致；匹配源码、安装说明和 APK 入口为 [2.0.1 测试版](https://github.com/le5676922-max/jizhang-qingji-app/releases/tag/v2.0.1-test)。

针对三星真机一直等待连接新增只读状态、生命周期诊断及初始化兼容处理，尚未查明真机根因，也未声称识别或连接已在该手机恢复。205 项 JVM、API 24 完整 135 项设备及 API 37 的 16 项连接相关测试通过，具体范围与原始文件名见 [本版报告](connection-troubleshooting.md)。历史 2.0 资产保留。
