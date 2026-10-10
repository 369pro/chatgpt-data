# AI 对话怎样扣余额：行业口径与本项目实现

核查日期：2026-10-07。本文以当前工作区代码及本地 8091 的模型列表接口为准；不把旧文档或供应商最新报价当成项目实际配置。本文已随 2026-10-07 的钱包统一与上下文修复更新。

## 1. 是不是每轮对话扣一次？

**要区分“结算单位”和“计价单位”：本项目的钱包请求每次结算一笔，但金额按实际 Token 用量计算，不是每轮扣 1 次，也不是每轮固定扣几分钱。**

| 层次 | 计量与收费方式 | 对本项目的含义 |
| --- | --- | --- |
| 模型厂商 API | 文本模型通常分别计算输入、输出和缓存 Token；具体模型可能另有工具、图像等费用 | 请求结束得到 usage，再计算该请求成本 |
| 面向消费者的聊天产品 | 可以选择订阅、限量套餐、积分或按量收费；不能由 UI 的消息次数推断底层 API 成本 | 对用户怎样收费属于产品决策 |
| 本项目所有已认证用户 | CNY 钱包 + 模型费率快照 + 实际 usage 结算 | 一次发送通常对应一次模型调用、一笔账本记录 |

DeepSeek 官方明确按输入与输出 Token 计费，并区分缓存命中；本地估算不是实际 usage。[模型与价格](https://api-docs.deepseek.com/quick_start/pricing/)、[Token 用量说明](https://api-docs.deepseek.com/quick_start/token_usage/)。Anthropic 同样有输入、输出、缓存等计价项；其聊天订阅与 API 费用分开。[API 定价](https://platform.claude.com/docs/en/about-claude/pricing)、[订阅不包含 API 用量](https://support.anthropic.com/en/articles/9876003-i-subscribe-to-a-paid-claude-ai-plan-why-do-i-have-to-pay-separately-for-api-usage-on-console)。这些说明支持计费机制，不代表本项目报价与厂商实时价格相同。

因此，“你好”和“分析一份长文档”即使都算一轮，成本也可能差很多。Agent 一次用户任务若包含多次模型调用，应累计各次实际成本；不能简单把一次任务等同一次 API 调用。这是后续设计建议，当前还没有 Agent 任务账本。

## 2. 当前一笔聊天费用如何产生

登录身份表 `auth_account` 与业务账户表 `user_account` 不同。钱包预授权不再以业务账户是否存在为开关；认证通过后，所有请求都必须通过钱包预授权。

1. 前端发送 `POST /api/v1/chatgpt/chat/completions`，携带 `model`、`messages` 和新生成的 `requestId`。
2. 后端解析模型及别名，做访问、敏感词、账户和模型权限等检查；钱包预授权成功才调用供应商。
3. 生成本次费率快照与预授权金额 H，在短事务中写入 `chat_quota_request` 并扣减钱包可用余额。余额不够 H 就拒绝本次请求，不使用旧次数兜底。
4. 在数据库事务外请求模型，流式返回文字，并读取供应商 usage。不是每个流式片段扣一次钱。
5. 收到终止事件时，使用账本保存的费率计算实际费用 C：退还 H−C，或者补扣 C−H。没有 usage 时释放 H。

这里的“冻结/预授权”在当前实现中是**直接减少 `available_amount`，同时在请求账本记下 H**，不是钱包独立的 `frozen_amount` 字段。因此生成期间看到的可用余额可能暂时下降较多，结算后退差额。

### 实际费用公式

```text
P = prompt_tokens（输入总量，包含缓存命中部分）
K = 缓存命中输入 Token
O = completion_tokens（输出总量）
C = 向上保留 8 位小数 [ ((P−K)×输入单价 + K×缓存单价 + O×输出单价) / 1,000,000 ]
```

金额用 `BigDecimal`，账本金额精度为 `DECIMAL(20,8)`。不能把缓存 Token 再叠加到 P 上；适配器优先读取 `prompt_cache_hit_tokens`，否则读取 `prompt_tokens_details.cached_tokens`，没有缓存字段按 0。输出使用 `completion_tokens`，不另加 reasoning 字段，避免供应商已含推理 Token 时重复扣费。也不能只数页面可见答案的字数。

### 项目当前价格

以下是配置文件与本地 `/api/v1/chatgpt/models` 核对一致的值，单位 **人民币元 / 百万 Token**；不是本次重新采集的厂商报价。

| 模型 ID | 输入未命中 | 输入缓存命中 | 输出 | 最大输出 Token |
| --- | ---: | ---: | ---: | ---: |
| deepseek-flash | 2.00 | 0.04 | 8.00 | 4096 |
| deepseek-v4-pro | 9.00 | 0.30 | 27.00 | 4096 |
| glm-5.3-flash | 0.80 | 0.23 | 2.80 | 8192 |

假设某请求输入 1000 Token（其中 600 缓存命中），输出 500 Token，同样用量对应：

- DeepSeek Flash：`(400×2 + 600×0.04 + 500×8)/1000000 = ¥0.00482400`。
- DeepSeek Pro：`(400×9 + 600×0.30 + 500×27)/1000000 = ¥0.01728000`。
- GLM Flash：`(400×0.8 + 600×0.23 + 500×2.8)/1000000 = ¥0.00185800`。

这只是对照算例。不同模型处理相同文本可能产生不同 Token 数和缓存命中，实际费用不必符合上述用量假设。

### 为什么只剩一点余额也可能发不出去

预授权不使用精确 tokenizer，而用保守估计：

```text
inputBudget = 1024 + 每条非 null 消息 256
              + 2 × 所有消息 role/name/content 的 UTF-8 字节数
H = cost(inputBudget, maxOutputTokens, cached=0)
```

预授权按“输入全部未命中 + 最大输出”计算，不是按最终短答案计算。单看输出预算，Flash 已需 ¥0.032768，Pro 需 ¥0.110592，GLM 需 ¥0.0229376，还要加输入预算。H 是估计而非严格保证的成本上限，实际费用超出时仍需补扣。

## 3. 多轮历史、切模型、重试

**历史只要再次随请求发送，就成为新请求的输入。** 上轮生成答案时算输出；下一轮若再次发送该答案，它算输入。缓存命中通常是折扣，不是天然免费，也不能假设跨模型或跨厂商复用缓存。

2026-10-07 已修复历史截断：前端发送“清除上下文”边界之后的所有有效消息，保留系统提示词和多轮问答，排除 Error、Cancelled、Sending 及空内容。重试仅取失败答案之前的有效历史。此前先截最后 3 条再过滤的逻辑已移除。完整历史会增加输入 Token 和预授权金额；尚未实现摘要压缩或 Token 预算裁剪，超出供应商上下文限制时仍可能请求失败。

切模型只影响新发出的请求：

- 同一个钱包，按本次选中模型费率结算；已有账单不重新定价。
- 预授权时保存模型 ID 和输入、缓存、输出价格，配置变更不应改写该笔快照。
- 已发出的请求使用当时传入的模型；不会因为后续选择器变化而重新计算。
- 重复提交相同用户、相同 requestId 的请求，后端阻止再次预授权/调用；这不是已完成答案的自动重放。
- UI 的“重试”会生成**新的 requestId**，是新调用，可以产生新费用。它与网络层重复同一个 requestId 不是一回事。

## 4. 错误、停止生成及账本状态

| 状态 | 含义 |
| --- | --- |
| 0 RESERVED | 预授权已扣，尚未结算 |
| 1 CONFIRMED | 已按实际 usage 结算 |
| 2 RELEASED | 预授权释放并退款 |
| 3 REVIEW | 实际费用超过预授权且补扣失败，保留原预授权，待人工处理 |

停止、错误、超时不等于必然免费：**结算时已经拿到 usage 就按 usage 结算；没有 usage 就释放预授权**，包括已流出部分文字但没有 usage 的情况。供应商可能已经产生费用，这部分风险由平台承担，不能声称供应商也没有收费。

进程崩溃等遗留 RESERVED 请求由恢复任务处理，超过 15 分钟的待结算记录释放退款。结算数据库异常也可能留到恢复任务。REVIEW 不在自动退款范围内。需要后续补上缺失 usage 对账和人工处理入口，不能把当前策略描述为完整的供应商账单对账系统。

## 5. 余额从哪里来，旧次数还在吗

充值的支付金额、到账金额、聊天消费是三个概念。例如支付 ¥9.90，按当前 20% 充值手续费到账 ¥7.92；之后聊天从 ¥7.92 内按 Token 扣款，**聊天结算不会再收一次这笔充值手续费**。超过 ¥50 的充值当前手续费为 10%，按整笔支付金额计算，详见 [充值规则](recharge-service-fee.md)。

充值发货使用订单到账快照，`balance_grant` 防止重复入账；抽奖余额奖励也是增加同一钱包，而不是增加聊天次数。

旧账户 `total_quota`、`surplus_quota`、商品 `quota`、订单 `product_quota` 和 `account_quota_grant` 已有退役迁移。`chat_quota_request`、`UserQuotaFilter` 等名字仍带 quota，现有新请求语义是钱包预授权，不能仅凭名字把它认作旧次数扣费，更不能直接删掉账本。

### 免费次数分支已移除（2026-10-07 修复）

无 `user_account` 的用户也必须按认证身份进入钱包预授权；没有钱包或余额不足直接拒绝，不再调用供应商。原来进程内免费次数及白名单跳过计费的路线已移除。已有业务账户的状态和模型权限检查仍保留；未开户不会凭空赠送余额。

本次不新增数据库列：钱包与请求账本沿用现有结构。此前删除的付费次数字段不会恢复。将来若需要新用户试用，应该发放可审计的钱包余额，不重新引入按次放行。

## 6. 代码定位与核查方法

下列路径以 chatgpt-data 根目录为基准：

| 关注点 | 文件/方法 |
| --- | --- |
| 模型费率及供应商 | `chatgpt-data-app/src/main/resources/application-models.yml` |
| 请求接入 | `chatgpt-data-trigger/src/main/java/cn/bugstack/chatgpt/data/trigger/http/ChatGPTAIServiceController.java` |
| 规则与模型调用 | `chatgpt-data-domain/src/main/java/cn/bugstack/chatgpt/data/domain/openai/service/AbstractChatService.java` |
| 预授权公式 | `chatgpt-data-domain/src/main/java/cn/bugstack/chatgpt/data/domain/openai/model/entity/ChatBillingQuote.java` |
| 事务预扣、结算、退款 | `chatgpt-data-infrastructure/src/main/java/cn/bugstack/chatgpt/data/infrastructure/adapter/repository/OpenAiRepository.java` |
| usage 字段适配 | `chatgpt-data-infrastructure/src/main/java/cn/bugstack/chatgpt/data/infrastructure/adapter/llm/OpenAiCompatibleChatProvider.java` |
| 流结束及取消 | `chatgpt-data-domain/src/main/java/cn/bugstack/chatgpt/data/domain/openai/service/channel/model/impl/ChatStreamListener.java` |
| 超时恢复调度 | `chatgpt-data-trigger/src/main/java/cn/bugstack/chatgpt/data/trigger/job/ChatQuotaRecoveryJob.java` |
| 前端上下文与 requestId | 相邻 `chatgpt-web/src/app/store/chat-store.ts` |

核对账单应按 openid + requestId 查 `chat_quota_request`，同时看模型、费率快照、usage、`charge_amount`、`settled_amount` 和状态；不能只看页面四舍五入后的钱包余额。`charge_amount` 保存原预授权 H，`settled_amount` 保存按 usage 算出的 C。CONFIRMED 的净扣款为 C；REVIEW 补扣失败时实际只扣住 H，不能把 `settled_amount` 当成已经足额收取的金额。RELEASED 已退 H，但原记录仍用于审计。数据库没有独立的 `reserved_amount` 列。

相关实现与迁移说明：[Wallet 与 Token 计费](wallet-token-billing.md)。本次检查了源码、当前模型列表和公式算例，没有重新跑数据库迁移或真实付费请求，旧测试记录不等于本次新增验证。本次额外运行了针对计费和供应商适配的测试，38 项通过；未开启 SQL 集成测试。

## 7. 设计追问与建议（尚未实施）

本次是现状整理，计费事实能由代码回答，无须让用户重新决定已经确定的余额计费方式。若继续升级，建议集中审视以下五项：

1. 用户是否能看懂每次扣款？建议显示本次模型、输入/缓存/输出 Token、实际费用及结算状态。
2. 少量余额是否被过高预授权挡住？建议加入可选输出预算与更精确输入估计。
3. 长对话如何兼顾质量和成本？有效历史发送已修复；后续建议按 Token 预算管理历史或摘要压缩。
4. 停止生成且缺失 usage 是否可持续退款？建议补充供应商对账、缺失 usage 监控及异常频率控制。
5. 未来 Agent 怎么收费？建议一个任务关联多笔调用账单，设置任务总预算；单列搜索等外部工具成本，避免“一轮一次”的误导。

## 8. 修复验收（2026-10-07）

- 前端上下文测试 3 项通过，TypeScript 检查和生产构建通过（构建仍有既存 lint 警告）。
- 后端 `ChatQuotaFlowTest`、`OpenAiRepositoryTest` 共 16 项通过，开启本地 MySQL 集成，0 跳过；包括无业务账户的有余额钱包预授权。
- 真实 MySQL 临时表验证新扣款 SQL：有余额无业务账户扣款成功，余额不足、无钱包、已有冻结账户均不扣款。
- 已重新部署前后端；本地无业务账户的零余额测试用户通过登录后请求 GLM，返回余额不足，未调用付费模型。
- 没有做数据库结构变更，也没有给用户赠送或扣除测试余额。
