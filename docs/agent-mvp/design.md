# 普通聊天与研究 Agent MVP 设计草案

更新时间：2026-10-07。状态：独立聊天项目已运行并完成真实模型联调；研究功能因尚无搜索 API 暂不启用。运行结果见 `../../../agent-service/docs/validation.md`。

## 已确认范围

- Java 保留认证、账户、模型权限、钱包和充值；新增同级独立 Python 项目 `../agent-service`，普通聊天和研究 Agent 一起迁入。
- 用户在网页提交研究主题，后台自主搜索、阅读网页，生成带官方来源引用的报告。
- 一个会话支持普通聊天与手动开启研究模式；同一会话只允许一个活动任务，执行期间可查看进度和停止，结束后继续发消息。
- 网页关闭不取消任务。服务崩溃后任务标记中断，保留已保存内容；用户手动重试，新建执行记录，不自动断点续跑。
- 接入现有钱包，按模型调用记录用量和结算，汇总至任务；任务预算不足时停止，保留阶段性结果并释放可确认未消费的预扣。
- 服务端保存历史消息；上下文压缩保留完整原始历史，模型使用摘要及最近消息。
- 首个验收题：调研 Hermes 与 OpenHands 的工具执行及任务恢复设计，比较网页端研究 Agent 的适用性，给出本项目 MVP 建议，并附官方来源。

## 现状与拆分边界

当前 AbstractChatService 同时处理模型选择、账户/权限检查、预扣和 ResponseBodyEmitter。ChatStreamListener 的完成/取消事件参与结算。前端 chat-store 持久化消息，后端没有对应会话实体。

现有 ChatProvider 是一次模型调用的抽象；其协议处理和模型别名行为可作为 Python 移植的兼容依据，但 Java 实现不能直接当作 Python 运行库。现有余额发放接口不是消费预扣接口，需要新增内部计费协议。

建议采用以下职责：

| 部分 | 职责与数据所有权 |
|---|---|
| 网页 | 会话列表、数据库历史、模式选择、任务进度、停止、报告 |
| Java | 唯一公开业务入口；验证登录和账户归属；模型权限；权威价格和钱包账本；代理 Python API 与事件流 |
| Python API/执行 worker | 会话与消息存储、任务调度、模型和工具执行、压缩、报告与引用 |
| openai 库 | Java 独占写入；保留账户、钱包、充值和计费记录 |
| ai_agent 库（建议） | Python 独占写入；可复用同一 MySQL 实例，通过独立数据库用户限制权限 |

浏览器不直接指定可信 openid。Java 从登录身份得到账户标识，通过受认证的内部请求传递。Python 每次读取会话、任务、报告均校验归属。Python 不直接写钱包表，不向网页暴露模型或搜索密钥。

MVP 建议使用数据库持久任务队列和独立 worker，避免将工作寄托在 HTTP 请求内的临时线程。执行权通过事务认领和租约保护；失效任务标记中断，不重放上游调用。提交接口以用户范围的幂等键返回已创建任务。

## 核心标识与生命周期

- session_id：用户可见的稳定会话 ID，压缩时保持不变。
- run_id：一次普通聊天回复或一次多步研究；关联输入消息、模式、模型、预算及最终状态。
- call_id：一次实际模型调用，包含生成、研究步骤、压缩；映射 Java 新计费账本的 call_id。一次 run 可以有多个 call。
- tool_call_id：某次模型响应中的工具调用标识，不与计费 call_id 混用。

任务状态建议为 queued → running → succeeded / failed / cancelled / budget_exhausted / interrupted。停止请求先记录 cancel_requested，再由 worker 完成取消和资源回收；任务结束与账务已完全结算使用不同字段表示。

创建 run 时锁定 session，检查 active_run_id，追加用户消息并创建任务，在同一事务提交。任务终止时仅由当前执行持有者释放该 session 的活动任务标记。租约与版本检查阻止已失效 worker 继续写入。

## 建议库表

以下为逻辑表设计，字段类型、DDL、索引及迁移脚本在访谈结束后定稿。所有表及重要字段应有中文数据库注释。

### Python 管理的 ai_agent 库

| 表 | 主要字段 | 约束与用途 |
|---|---|---|
| ai_session | session_id, openid, title, active_run_id, next_seq, active_snapshot_id, context_version, import_key, created_at, updated_at | 会话与归属；按用户及更新时间分页；`import_key` 仅为历史数据兼容列，不属于当前 API |
| ai_message | message_id, session_id, run_id 可空, seq, role, content_json, visibility, status, origin, original_time, import_message_key, created_at | (session_id, seq) 唯一；保存数据库中的原始用户/助手/工具消息及调用关联；`origin`、`original_time`、`import_message_key` 保留旧数据兼容，不开放浏览器导入 |
| ai_run | run_id, session_id, openid, input_message_id, mode, model_id, client_request_id, status, cancel_requested, budget_limit, billing_status, lease_owner, lease_until, lease_version, error_code, started_at, finished_at | (openid, client_request_id) 唯一；session 活动指针保证串行；预算展示值以 Java 为准 |
| ai_model_call | call_id, run_id, sequence_no, purpose, model_id, provider_request_id, status, usage_json, billing_status, settlement_payload_json, started_at, finished_at | call_id 唯一；purpose 区分生成与压缩；保存用量与待重试结算内容，模型调用本身不因 RPC 超时自动重放 |
| ai_run_event | event_id, run_id, seq, type, payload_json, created_at | (run_id, seq) 唯一；重连后可从已读游标继续读取进度；原始 token 流分批持久化，避免每个字符一行 |
| ai_context_snapshot | snapshot_id, session_id, parent_snapshot_id, covered_through_seq, retained_from_seq, summary, source_context_version, compressor_call_id, status, created_at | 记录压缩覆盖范围和摘要链；保留原始消息；发布时校验上下文版本 |
| ai_artifact | artifact_id, run_id, session_id, kind, title, content_text, metadata_json, source_url, content_hash, created_at | 保存最终/部分报告、被阅读的网页证据与来源元数据；报告引用指向稳定证据 ID，可同时展示原始 URL |

大篇网页和报告正文可在后续外置文件存储；MVP 先设抓取字节数及正文长度上限。网页证据保存标题、来源 URL、抓取时间及支持结论的原文片段，不只保存搜索摘要。工具调用参数/结果属于消息或事件；上述两者职责要固定，避免无法确定权威内容。

### Java 管理的 openai 库

| 表 | 处理 |
|---|---|
| auth_account / user_account / account_wallet / balance_grant / openai_order / openai_product | 保留职责。模型权限检查和价格配置仍由 Java 对外提供权威结果 |
| chat_quota_request | 保留旧聊天历史账本及原回收逻辑；新 Python 调用不写此表，避免旧的 15 分钟回收任务错误释放 Agent 预扣 |
| agent_call_charge（新增） | 独立记录 Python 模型调用的 call_id、run_id、价格、预扣、实际用量、封顶收费、退款原因、幂等指纹及超时；Java 为唯一写入方 |
| ai_run_budget（新增建议） | run_id, openid, limit_amount, held_amount, settled_amount, status, created_at, updated_at；对调用预扣和任务总预算做事务控制 |

金额沿用人民币 DECIMAL(20,8)，避免浮点计算。跨库仅用业务 ID 关联，不依赖跨服务外键或分布式事务。时间统一保存 UTC，界面按用户时区显示。

## 内部计费协议建议

Java 提供任务预算初始化、调用预扣、用量结算、确认未消费的释放、按 run/call 查询结果等内部接口。预扣请求包含 call_id、run_id、模型 ID 和用于额度计算的输入/输出限制；Java 验证归属、权限与价格，冻结价格快照。Python 只上报用量，不指定权威收费金额。

每次调用前验证：已结算金额 + 未结算预扣 + 本次预扣不超过任务上限，且钱包可用余额足够。预算计数、账本和钱包扣减在 Java 单库事务内更新；采用一致锁顺序。余额不足或预算不足时不调用上游。

相同幂等键及相同有效载荷重试应返回先前结果，载荷不一致必须拒绝。Java 返回丢失时先按 call_id 查询，不能换新 ID 重扣。Python 在上游调用前持久化调用意图；获取用量后持久化结算载荷，再发送结算请求。只允许重试结算投递，不自动重试状态不明的上游生成。

现有过期预扣回收按创建时间固定释放，需要与调用状态和租约联动；不能直接套用到多步研究。服务中断时已结算调用保持原账，未发送调用可释放，已发送但无可靠用量的调用退还该次预扣，平台承担不确定的上游成本；保存退款原因用于核查。用户可见任务终止状态不等于所有账务立即已完成。

压缩也是模型调用，应纳入任务预算和费用展示。保留输出 token 空间及压缩所需预算；剩余预算不足时停止，不在超出预算后继续调用模型生成所谓收尾报告，改为返回已经持久保存的资料与部分内容。

## 上下文压缩

借鉴 Hermes 的摘要加近期上下文思想，MVP 不复制其内部 session 分裂方式。固定用户会话 ID，独立版本化压缩快照。

调用前估算系统指令、工具定义、摘要、近期消息的输入大小，并预留输出空间。接近配置阈值时压缩较早的完整轮次，保留用户目标、约束、已核实事实、证据 ID、未完成问题。工具调用及其结果必须成对保留，不能在半个工具交互处截断。

压缩成功并保存快照后，以版本比较方式切换会话活动快照；失败不覆盖原上下文、不删除历史。摘要保留稳定来源关联，不能把旧工具结果内的指令当成系统规则。换模型后重新评估上下文容量。MVP 自动压缩，阈值、保留范围和压缩模型配置化。

## 历史消息归属

聊天记录只由服务端会话、运行任务和数据库消息产生，网页刷新或重新打开时从服务端读取。服务端不提供浏览器旧历史上传或导入接口，也不把本地内容当作当前账户的历史。历史兼容列仍保留在数据库中，供既有记录读取和审计使用。

## 研究工具和验收

首版工具限制为联网搜索与网页阅读，报告由 Agent 输出并由服务保存。默认无终端、任意代码执行或对外写操作。网页抓取限制协议、跳转、响应体和超时，屏蔽本机/内网/云元数据地址，避免把多用户服务器变成任意网络访问代理。

研究报告中的关键结论应能追溯到已读取资料；区分来源事实与推断。对验收题优先检索项目官方文档和代码，不以搜索摘要证明实现细节。找不到证据时明确标注未知，不能为凑齐对比项补造功能。

验收覆盖：普通聊天流式兼容；数据库历史可见及账户隔离；研究多步工具调用与引用；关闭网页再回来；停止/预算耗尽/进程中断后保留结果；同会话并发提交；压缩后继续对话且原始历史仍在；预扣/结算 RPC 重试不重复扣款。

## 建议实施顺序

1. 固定内部身份、任务和计费协议，完成库表 DDL、迁移与针对资金一致性的验证。
2. Python 实现单轮普通聊天、会话存储和 Java 计费联调，迁移前端历史入口到服务端数据库。
3. 加入持久任务 worker、搜索/阅读工具、研究循环、事件与报告，完成同会话两种模式。
4. 加入上下文压缩及中断/停止/费用上限处理，运行完整验收题，再切换默认聊天执行路径。

不在首版引入自动断点续跑、实时追加指令、多 Agent 委派、长期跨会话记忆或通用插件市场。

## 仍待确认

- 已确认：无可靠用量的失败调用退款，已有可靠用量的其他调用正常结算。
- 用户确认目前无搜索 API，当前实施里程碑先跑通聊天服务；研究入口明确禁用，不进行研究报告验收。后续再确定搜索供应商及费用。
- 任务默认费用上限、轮数和时长使用可调整配置，具体初始值需在模型与搜索渠道确定后估算。

## 本地依据

- `chatgpt-data-domain/.../openai/service/AbstractChatService.java`：现有校验、预扣及 HTTP 流耦合。
- `chatgpt-data-infrastructure/.../adapter/repository/OpenAiRepository.java`：实际 Token 结算、退款与过期回收。
- `chatgpt-data-app/src/main/resources/mybatis/mapper/chat_quota_mapper.xml`：每次调用账本与钱包事务。
- `chatgpt-web/src/app/store/chat-store.ts`：现有浏览器会话持久化。
- `hermes-agent/agent/conversation_compression.py`、`hermes_state.py`：压缩与 session 关联机制。
