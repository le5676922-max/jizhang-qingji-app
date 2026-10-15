# 轻记 2.0 测试版验证记录

更新日期：2026-10-15（Asia/Shanghai）。本报告记录 **2.0 发布源码与对应本机 APK** 的实际验证，不是后续 2.0.1 或 2.1 的验证报告；[通知版 1.0](verification-notification-v1.md) 与 [屏幕版 1.1](verification-screen.md) 的历史数量也不能替代本次结果。2.1 DeepSeek 开发边界另见 [接入指南](deepseek-agent.md)。原始日志、数据库快照、系统截图与测试备份保存在本机被 Git 忽略的 `docs/evidence/`、`docs/screenshots/`，未作为公开仓库附件上传；下文保留文件名便于复核。

## 当前结果

| 检查／环境 | 已发生的结果 | 尚未完成或不能推断的内容 |
| --- | --- | --- |
| 产品构建 | assembleDebug、assembleDebugAndroidTest、testDebugUnitTest、lintDebug 成功，用时 **3 分 42 秒** | 之后只修改了设备测试的合成字图及 Snackbar 交互，测试 APK 已重新编译，产品 APK 未因此改变 |
| JVM 单元测试 | **199 项通过，0 失败、0 错误、0 跳过** | 不代替 Android 系统和真实支付页面验收 |
| Lint | **0 错误、27 警告** | 警告仍保留，没有标为零警告 |
| API 24／Android 7.0，x86_64，4 KB | **124／124 全量通过，201.18 秒**；`v2-api24-full-instrumentation-rerun.txt`；固定像素后 OCR **3／3 通过，12.894 秒** | 全量对应 96px 动态字图阶段；固定 asset 后复核 3 项 OCR。不能据此推断所有旧手机、Android Go 或厂商后台行为 |
| API 35，x86_64，16 KB | 主界面启动、中文 OCR、实际图形 JNI 已运行；OCR 与设备卡片 5 项通过；禁用 GMS、断网后 OCR 3 项通过；账本 UI 独立 **10／10 通过，87.408 秒**；识别中心／屏幕设置 **10／10 通过，48.448 秒** | 全量首轮 123／124；修复后 124 项各有通过记录，但另一次全量在 ART/JIT 原生崩溃处中断，**没有单进程 124 项整套通过结果** |
| API 37 专用模拟器 | 固定像素后最终 **124／124 全量通过，145.048 秒**；`v2-api37-fixed-fixture-full-124.txt` | 首次动态字图 OCR 断言失败与修复保留记录；未改产品源码或 APK |
| API 37 通知／节点／OCR 端到端 | 严格只读 SQL 校验通过：**10 笔正式账目、28 条来源事件，净支出 ¥106.00** | 来自合成夹具，不是真实微信／支付宝或 S23 Ultra 的识别成功率 |
| SAF 系统文件选择器 | 导出 **36,813 字节**加密备份；预览、取消、错误密码与最终确认恢复 UI 闭环完成；七表逐行全部字段与原数据相同 | 预算及来源选择保留，通知／屏幕／OCR 三开关关闭；`v2-saf-roundtrip-result.json` 为 `verified: true` |
| 本机 APK | 已复制为 `deliverables/qingji-v2.0.apk`，签名与 ZIP 对齐检查通过 | 匹配源码标签 `v2.0-test`；安装包与源码入口为下方发布页 |

## 构建、安装包与签名

工程继续使用 Kotlin 2.2.10、Jetpack Compose、Material 3 和 SQLite；AGP 9.4.1、Compose BOM 2025.12.00、compile／target SDK 37、min SDK 24，数据库版本 3。产品 `versionName 2.0`、`versionCode 3`，包名 `com.example.jizhang`。中文识别依赖官方 bundled `com.google.mlkit:text-recognition-chinese:16.0.1`。

实际构建入口：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8" --console=plain
```

| APK 项目 | 已核验值 |
| --- | --- |
| 文件 | `deliverables/qingji-v2.0.apk`，57,543,659 字节 |
| SHA-256 | `4681BA82C750AA838B51DAA7CFE9D16AFD2FAC1DB80DF1AD43C268797BE2741D` |
| 签名证书 SHA-256 | `64e32611bd105a2b68a15e44b2c785433274ac3c1f416525f1056c2fc2de0498` |
| 签名核验 | APK v2 签名通过 |
| 版本／系统 | versionCode 3、versionName 2.0、minSdk 24、targetSdk 37 |
| CPU ABI | `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` |
| ZIP 对齐 | Build-Tools 36.0.0：`zipalign -c -P 16 4` 通过 |
| 网络与模型 | 产品无 INTERNET／ACCESS_NETWORK_STATE 权限；中文模型实际捆绑在 APK |

ZIP 对齐通过不等于第三方 ELF 的所有 16 KB 条件均合格。REA 归档清单、原生库 LOAD／GNU_RELRO 数值与 x86_64 运行结果分开记录在 [Android 兼容性](android-compatibility.md)。静态审计时的较早整包哈希不是上表最终交付包哈希；实际最终哈希以上表为准。安装包与匹配源码入口为 [v2.0-test](https://github.com/le5676922-max/jizhang-qingji-app/releases/tag/v2.0-test)，APK 资产名为 `qingji-v2.0.apk`。

## 工程与功能变更

| 文件／函数范围 | 实现与验证重点 |
| --- | --- |
| `domain/ReceiptSemantics.kt`、`ScreenPaymentParser.kt`、`LedgerStatistics.kt` | 经济用途、完成状态、订单与退款原单命名空间；整数分金额；退款额度与跨月抵扣。 |
| `recognition/CandidateFactory.kt`、`DecisionEngine.kt`、`TransactionMatcher.kt`、`EventStateMachine.kt` | 统一候选、AUTO／REVIEW／IGNORE，多证据强身份关联、弱证据建议、人工忽略与撤销关联保持。 |
| `recognition/ocr/OcrDocument.kt`、`OcrReceiptLayoutParser.kt` | OCR 行／词坐标、金额标签与几何邻近关系，多金额人工选择；已知金额标签的规范化只匹配完整边界，不改写数字。 |
| `automation/ScreenCaptureService.kt`、`ScreenCapturePolicy.kt`、`CaptureSnapshot.kt` | 先做来源／页面／敏感输入门禁，再读字段；有界节点和图片；会话、窗口修订与配置版本提交前复核，安全窗口拒绝。 |
| `automation/LocalTextRecognizer.kt`、图片导入 | 原生 OCR 串行；取消后等待任务结束再释放 Bitmap；IO 解码、复制、缩放与硬件缓冲所有权。 |
| `automation/PaymentNotificationListener.kt` | 先核对来源／服务标题／连接与启用时间，再复制有界正文；不回扫历史，关闭来源或恢复时停止采集。 |
| `data/LedgerRepository.kt` | 来源、别名、账单、操作日志统一事务；REVIEW 不建正式账单；确认／关联操作核验来源指纹，保留人工修改与删除身份。 |
| `data/RecognitionSchema.kt`、备份模块 | 数据库 1／2 增量迁移；七表快照、严格关系和退款额度校验；AES-GCM 认证预览、检查点与失败回滚。 |
| `ui/RecognitionCenter.kt`、`LedgerApp.kt`、`ScreenControls.kt`、`BackupControls.kt` | 用途／方向／金额核对、原消费选择、分类与回收站、诊断、受限设置、SAF 密码／预览／二次恢复。 |
| `ui/DeviceCompatibilityCard.kt` 与 FlowRow 布局 | 系统能力、低内存通知限制、通用后台设置入口；筛选及核对选项随窄屏／大字体换行。 |

转账与还款不作为新增消费或收入。退款只抵扣有效关联的原消费，累计不超过原消费金额，按退款月份与原消费分类统计；跨月退款可能使该月净支出为负。CSV 在原八列后增加“经济类型”和“关联原消费ID”，仍是明文导出，不能替代完整备份。

## 最终设备测试集合：124 项

下表是当前源码的测试数量。**API 24 与 API 37 分别全量通过全部 124 项**；API 24 全量在动态字图阶段完成，固定像素后的 3 项 OCR 另行复核通过。API 35 的分段通过和长进程崩溃必须按前表分别判断，不能把类目数量写成每个系统都已单进程全量通过。

| 测试类 | 项数 | 主要覆盖 |
| --- | ---: | --- |
| ExampleInstrumentedTest | 1 | 应用上下文 |
| LocalOcrInstrumentedTest | 3 | 捆绑中文 OCR、实际合成 Canvas PNG、服务同等缩放与图片解码 |
| NativeGraphicsInstrumentedTest | 1 | 实际 conic → quadratic 的图形 JNI 路径，坐标有限且结果非空 |
| LedgerRepositoryInstrumentedTest | 9 | 精确金额、持久化、幂等、编辑来源、CSV 与设置 |
| LegacyLedgerMigrationInstrumentedTest | 2 | 数据库 1／2 升级保留账目与身份 |
| RecognitionCoreInstrumentedTest | 24 | 强／弱关联、迟到状态、冲突原位确认、删除／忽略、退款额度、并发、旧指纹与完整备份 |
| ScreenRepositoryInstrumentedTest | 18 | 节点／OCR 候选、同额不同单、历史／缺身份、来源开关与删除抑制 |
| UnifiedRecognitionInstrumentedTest | 5 | 统一入口、正式账单与 REVIEW 分离、别名和启用门禁 |
| BackupDocumentValidatorInstrumentedTest | 15 | 数值、枚举、主外键、身份与退款关系 |
| BackupStorageInstrumentedTest | 5 | 七表替换、事务、外键与自增编号 |
| LedgerBackupCoordinatorInstrumentedTest | 9 | 加密／预览／确认、设置、故障回滚与阻止状态 |
| RestoreJournalInstrumentedTest | 7 | 私有检查点、完整性、重试及损坏处理 |
| BackupControlsInstrumentedTest | 3 | 弱密码、不一致密码、取消清密码且不生成文件 |
| DeviceCompatibilityCardInstrumentedTest | 2 | API 能力／低内存／未知厂商提示，320dp／fontScale 2.0 按钮可见及回调 |
| LedgerUiInstrumentedTest | 10 | 手动录入、精度、月份、识别预览、截图确认、往来统计、退款、回收与规则删除 |
| RecognitionCenterInstrumentedTest | 5 | 方向／多金额选择、退款额度、撤销、关联冲突编辑与旧 ID |
| ScreenControlsInstrumentedTest | 5 | 受限设置、已授权待连接、API 24 能力、恢复期间禁用、旧系统 OCR 披露 |
| **合计** | **124** | 隔离数据与专用模拟器 |

数据库测试使用独立随机数据库及偏好文件，结束后清理；组件使用合成事件。两个测试 PNG 仅在测试 APK 的 assets 中：一个为专用模拟器的合成 Canvas 页面，另一个为 API 24 生成的固定 88.66 元测试字图。均无真实用户凭据，不进入产品 APK。

## 失败、修复与复跑记录

- **119 项初次运行有 2 项 UI 失败**：回收站与分类规则管理按钮被底部 Snackbar 遮挡。补充反馈状态与弹窗断言后，10 项 UI 独立复跑通过（70.522 秒），119 项全量复跑通过（166.564 秒）。原始文件为 `v2-final-instrumentation.txt`、`v2-ui-rerun.txt`、`v2-final-instrumentation-rerun.txt`。
- **旧系统截图边界补强后 120 项通过**：API 30–33 在截图、OCR 和提交前再次检查其他应用、输入法与交叠浮窗；界面明确显示级截图的边界。API 37 全量 120 项通过，159.309 秒。
- **122 项阶段一次文案断言失败**：测试用完整文本匹配去检查句中的词，实际语义已包含目标。改为显式 substring 检查；没有改产品文案掩盖缺失。
- **Canvas 端到端发现真实标签解析错误**：已知 OCR 标签“转账全額”的长度与取边界常量不符，导致实际 28.56 元候选缺失。改为完整标签长度与边界匹配，新增 JVM 回归及实际合成 PNG 的设备测试；不取全页最大金额，不放宽金额或完成状态。
- **API 24 首轮 124 项中 2 项失败**：48px 合成字体被旧平台 OCR 读成“转账戍功”，严格解析正确拒绝错误完成状态；另有 Snackbar 15 秒墙钟等待超时。只把测试图改为 96px、加大行距，并通过 Snackbar 公开 Semantics Dismiss 动作关闭反馈，不执行“撤销”。保留原金额／关系／恢复断言，生产解析器没有接受错字状态。复跑 **124／124 通过，201.18 秒**。
- **API 37 最终 124 项首次运行的动态字图失败**：96px 字图在该平台字体下将“转账金额”识别为“转账金领”，严格解析拒绝，未把它写成识别成功。随后将已在 API 24 通过的实际合成像素固定为测试 asset，消除不同平台字体生成输入的差异；金额 88.66 元、支出方向、待确认与完成状态断言均保留。固定资源后 API 24 的三项 OCR 通过（12.894 秒），API 35／16KB 的三项 OCR 通过（8.137 秒）；最终 API 37 全量结果见前表。
- **API 35／16 KB 的全量首轮 123／124**：失败同为 Snackbar 等待超时。修复后账本 UI 独立 **10／10 通过，87.408 秒**，记录 `v2-api35-16k-ui-isolated.txt`；识别中心与屏幕设置另 **10／10 通过，48.448 秒**，记录 `v2-api35-16k-remaining-ui.txt`。至此各项都有通过记录。另一次全量长进程运行到 UI 阶段时 ART/JIT 出现 RIP=0 的 SIGSEGV，`v2-api35-16k-full-instrumentation-rerun.txt` 以 Process crashed 中断；原生崩溃日志 `v2-api35-16k-crash.txt` 未出现 OCR／图形 JNI 栈。同环境还观察到系统 Google 服务及 SystemUI 的独立 ART 崩溃，但这**不足以证明仅为模拟器问题，也不构成单进程整套通过**；此项保留待定位。

API 24 首轮输出为 `v2-api24-full-instrumentation.txt`，成功复跑为 `v2-api24-full-instrumentation-rerun.txt`。API 35 首轮为 `v2-api35-16k-full-instrumentation.txt`。失败日志未被成功摘要覆盖或改写。

## 通知、页面文字与 OCR 的实际端到端

专用 API 37 模拟器中，两个测试 flavor 分别提供微信、支付宝白名单下的**合成**通知与交易页面。真实系统通知服务、无障碍服务与截图 OCR 均参与链路；不是直接调用仓库后伪称屏幕成功。

对只含这组数据的数据库快照运行 [只读核验脚本](../tools/verify_v2_db.py)，输出 `verified: true`，本机结果为 `v2-e2e-final.json`／`v2-e2e-final.sqlite`：

| 实际账本指标 | 微信合成来源 | 支付宝合成来源 | 合计 |
| --- | ---: | ---: | ---: |
| 正式账目 | 5 | 5 | 10 |
| 其中消费／退款 | 3／2 | 3／2 | 6／4 |
| 消费毛额 | ¥61.00 | ¥61.00 | ¥122.00 |
| 有效退款 | ¥8.00 | ¥8.00 | ¥16.00 |
| 净支出 | **¥53.00** | **¥53.00** | **¥106.00** |
| 新增收入 | ¥0.00 | ¥0.00 | ¥0.00 |
| 来源事件 | 14 | 14 | 28 |
| AUTO／REVIEW | 9／5 | 9／5 | 18／10 |

每来源的两笔不同单号 28.00 元付款分别保持独立；两笔付款及两笔部分退款的通知／页面证据各关联到唯一正式账单。5.00 元红包在明确已发出证据下记录；对原消费的 5.00 与 3.00 元退款有效抵扣。

两笔 12.30 元转账、28.56 元实际 Canvas OCR、两通道的 50.00 元超额退款保持 REVIEW 且未入正式账本。聊天 66.66 元未采集。不同订单的 FLAG_SECURE 页面未形成候选，并实际观察到诊断“支付窗口受保护，系统拒绝截图”；聊天诊断为“页面包含敏感或非交易内容”。数据库缺失某条记录本身不能证明截图拒绝，故将实际拒绝诊断与 SQL 阴性检查分别记录。

自动 OCR 至少间隔 8 秒，更新 APK／窗口变化会使旧截图结果失效；首次过短停留没有生成候选，稳定停留并等待冷模型后才生成。未取消有效性门禁来换取成功。具体场景和安全安装约束见 [夹具说明](../tools/notification-fixture/README.md)。

## 备份、迁移与隐私

数据库 1／2 到 3 增量升级，不清空旧账。保留整数分、方向、商户、分类、备注、时间、指纹与墓碑；旧自动账生成 LEGACY 来源，旧待确认对应 REVIEW。未保存过的单号、转账用途和退款原消费不从旧备注猜测；旧“退款”分类收入不自动改为关联退款。

完整备份包含七表、六项设置，采用 PBKDF2 与 AES-256-GCM；认证和结构校验通过才显示预览。确认覆盖使用事务与私有检查点，失败回滚或阻止后续修改；成功后关闭通知、屏幕、自动 OCR，保留预算与支付来源选择，系统权限不从备份恢复。设备测试覆盖错密码、篡改、回滚、检查点损坏、设置失败、重启恢复和自增编号。

实际 SAF UI 已导出 36,813 字节文件，检查到 QJBACKUP 头且未包含固定合成明文；正确密码进入真实预览，显示 10 笔账目／28 条事件。预览取消及错误密码后分别核对七张表，均与导出前 E2E 数据库逐行相同。

随后通过系统文件选择器、正确密码、预览和最终确认完成覆盖恢复。只读核验 `v2-saf-roundtrip-result.json` 为 `verified: true`：账目 10、已处理身份 38、来源事件 28、别名 38、商户规则 0、关联操作 8、诊断 3，七张表的每行全部字段与原 E2E 快照相同，仅不比较物理 rowid 排列。预算 300,000 分和微信／支付宝来源开关保留；通知、屏幕、自动 OCR 三开关均为 false，符合恢复后暂停采集规则。该验证使用固定合成数据，没有恢复到个人手机或覆盖真实账本。

主 App 不声明网络、短信或通讯录权限；只持久化提取字段、固定解释、金额候选及哈希身份，不保存完整通知、页面全文、单号原文或原图。原文和截图只在有界内存中使用。SQLite 未额外加密；私有恢复检查点为覆盖前提取字段快照，处理后删除，不是对外加密备份。详见 [隐私说明](privacy.md) 和 [数据架构](architecture.md)。

## 兼容性与性能读数

API 24 的 `/proc/self/smaps` 给出 4 KB 页面；该系统没有 getconf，未用缺失命令假定页大小。API 35 使用官方 x86_64 16 KB 镜像，getconf PAGE_SIZE 实际为 16384。禁用 Google Play services 且断网后 OCR **3／3 通过，19.678 秒**；OCR 与设备卡片 **5／5 通过，36.673 秒**。真实图形 JNI 的 conic 转换也已运行，不能把这些描述为仅检查 APK 或仅 Java 单元测试。

四种 ABI 与最低 API 声明不等于“所有手机均已适配”。第三方库 GNU_RELRO 静态疑点仍保留；没有 ARM64 16 KB、无 GMS 实体手机、Android Go、厂商后台、折叠或外接副屏的运行验收。API 30–33 的显示级截图与窗口截图隔离能力不同，已做阶段复核但不承诺原子窗口隔离。详情见 [Android 兼容性](android-compatibility.md)。

| 专用模拟器采样 | PSS | RSS | 备注 |
| --- | ---: | ---: | --- |
| OCR 前空闲 | 93,129 KiB | 230,820 KiB | top 采样 CPU 为 0.0% |
| OCR 后 | 107,030 KiB | 246,652 KiB | swap 为 0；当次 OCR 耗时诊断 1,900 ms |

本机文件为 `v2-idle-memory.txt`、`v2-idle-cpu.txt`、`v2-after-ocr-memory.txt`。这些是调试版模拟器短时读数，不是内存峰值、长时回落、真实手机延迟、识别准确率或电池续航数据，不能推算 S23 Ultra 耗电。

## 仍需验收的边界

- API 35 全量长进程 ART 崩溃仍待定位；各项分段通过不能代替长进程稳定性验收。
- 没有真实三星 S23 Ultra、当前真实微信／支付宝版本或真实支付交易的证据，也未验证所有 Android 系统／品牌组合。
- 主账单编辑器打开状态与输入使用 remember；旋转或折叠触发 Activity 重建会关闭编辑器并丢失未保存草稿，已保存账目仍在数据库中。
- 无通知、无可读单笔结果页或安全窗口不能截屏时，无法自动补流水；转账用途与含糊凭据需要人工核对。
- 测试夹具与真实支付应用同包名，只能安装到专用可丢弃模拟器；不安装到个人手机，不执行真实交易，不绕过安全限制。
- 未逆向确认三星生活助手“我的账本”的内部采集机制；现有研究与 REA 静态证据分别说明来源和限制。
