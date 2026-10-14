# V2.0 阶段审计与实施记录

## Phase 1：实际基线

当前工作区已是轻记1.1，而不是仅通知版本1.0。现有Kotlin、Compose、Material3、SQLiteOpenHelper、AGP9.4.1、Kotlin2.2.10、Compose BOM2025.12.00、API24最低版本保留，不重建工程。

本次改造前实际执行 assembleDebug、testDebugUnitTest、lintDebug，BUILD SUCCESSFUL（23秒）。未变更的105项单元结果由Gradle复用缓存；已有1.1的37项设备测试证据保留在 verification-screen.md，本次尚未据此声称V2设备测试已通过。

读取及复核了源代码、Manifest、Gradle、SQLite schema、测试、验证文档。依赖关系：通知监听和屏幕服务读取用户授权来源→独立严格解析器→LedgerRepository持久化→StateFlow→Compose。无网络权限；通知身份primary/stable alias与支付订单身份是不同概念。

当前正确能力可复用：通知服务仅处理开启后新事件；通知更新幂等及删除墓碑；屏幕白名单、650ms防抖、8秒截图限频；API30/34截图分支、FLAG_SECURE失败降级；离线中文模型、截图/HardwareBuffer释放；手动账单、整数分、预算、CSV公式防护、服务授权与连接检查。

关键缺口：entries混放正式/待确认账目，只有收支方向没有资金用途；转账与退款可能误入消费/收入；多来源事件没有多对一关联与撤销；OCR丢失位置；未知页面门禁和全局OCR并发需要加强；缺少完整加密备份恢复及商户纠正规则。

## 已确定的增量方案

- 保留entries及原ID/字段，增加business_type、related_entry_id、edited_by_user、deleted_at；1→2→3有序迁移，不删除旧数据，不根据可编辑备注自动重解释历史资金用途。
- source_events与event_aliases保存提取事实、渠道、规则版本、脱敏依据及关联；原seen_events继续保存包括已删除账目在内的身份墓碑。
- 候选与正式交易分离；新待确认事件不计入正式账本。稳定提供方订单身份命名空间可强关联，金额/时间/商户只提供人工核对建议。
- 资金方向与EXPENSE/INCOME/TRANSFER/REFUND/REPAYMENT/UNKNOWN用途分离；转账/还款默认核对，退款需关联原消费并检查累计退款上限；内部转移和还款不重复算消费。
- 删除采用可撤销软删除；保留来源关联与去重身份。人工商户分类仅影响分类建议，不自动改变资金用途。
- 用户主动创建AES-GCM加密备份；完整包含账单、待确认来源事件、别名/墓碑、关联记录、分类规则、诊断与设置。认证和结构验证通过后才在事务中恢复。

后续阶段的实际构建、设备测试、性能与实机限制以最终verification.md为准；尚未执行的检查不能当作已通过。
