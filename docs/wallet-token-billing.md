# Wallet 与 Token 计费

> 设计与运维说明，基线日期：2026-10-02。金额单位为 CNY。

## 1. 账户与购买

### 商品和订单快照

- `openai_product.credit_amount` 表示扣除充值手续费后的 CNY 钱包到账金额，新钱包商品的旧 `quota` 保持为 `0`。商品查询和下单统一按实付 `price` 计算手续费，不对已有净额再次扣费。
- 当前种子商品 `1001`、`1002`、`1003` 的实付金额分别为 `9.90`、`29.90`、`99.90` CNY，净到账分别为 `7.92`、`23.92`、`89.91` CNY。
- 创建订单时把净到账金额写入 `openai_order.product_credit_amount`，手续费率和金额写入 `product_fee_rate`、`product_fee_amount`。这是订单快照，不能在发货时重新读取商品价格或套用新费率。详细规则见 [充值手续费](recharge-service-fee.md)。
- `product_credit_amount IS NULL` 只表示历史额度订单。历史订单不转换，仍按原有 `product_quota` 发货；非空快照才走钱包充值。
- 新聊天仍然优先消耗 `user_account.surplus_quota` 中的历史剩余额度。只有额度 CAS 扣减失败且请求有 Token 计费报价时，才预授权钱包。

### 支付 CAS 与钱包发货

支付回调先用订单金额匹配和状态条件做 CAS：同一订单只能从待支付/待处理状态成功转为已支付，回调金额必须等于订单金额。支付提供方不会直接写钱包。

已支付订单发货在一个本地数据库事务内完成，不能嵌套 `REQUIRES_NEW` 的余额事务：

1. 查询订单，并执行 `DELIVER_GOODS` CAS：只有 `order_status = 1 AND pay_status = 1` 的订单可以变为 `order_status = 2`。
2. 若订单有 `product_credit_amount`，必要时创建零额度 `user_account`，不增加次数额度。
3. 以 `app_id = 'payment'`、`request_id = orderId` 插入 `balance_grant`。该键由唯一索引保护；首次插入才累加 `account_wallet`，重试只校验 `openid` 和金额快照，不能再次充值。
4. 若订单快照为 `NULL`，执行旧的 `product_quota` 发货路径，不创建余额 grant。

订单状态 CAS、grant 唯一插入、钱包 upsert 和必要的账户初始化必须同一事务提交。任一步失败都回滚发货状态和 grant，避免“已发货但未到账”或重复到账。

## 2. Token 计费

### 费率快照和预授权

每个新聊天请求按所选模型的每百万 Token 费率计费，费率包括：

- 输入 Token：缓存未命中价；
- 缓存命中输入 Token：缓存价；
- 输出 Token：输出价。

创建 `chat_quota_request` 时把 `model`、`input_price`、`cached_input_price`、`output_price` 固化到该请求。后续配置变更不会改变已持有请求的结算价格。

预授权只是安全上限，不是最终用量。当前估算不依赖分词器，而是按 UTF-8 字节数做保守估计：

```text
inputBudget = 1024
           + 每条非空消息 256
           + 2 × role/name/content 各字段的 UTF-8 字节数

hold = CEILING_8(
    ((inputBudget - cachedInput) × inputPrice
      + cachedInput × cachedInputPrice
      + maxOutputTokens × outputPrice) / 1,000,000
)
```

预授权阶段使用 `cachedInput = 0`，并把 `maxOutputTokens` 作为输出上限。这个估算值只用于暂时扣住余额；上游返回的实际 `usage` 才用于最终结算。所有金额使用 `BigDecimal`，数据库精度为 `DECIMAL(20,8)`，金额计算保留 8 位并使用 `RoundingMode.CEILING`，禁止用 `double`。

### 请求、结算和异常

1. 先写 `RESERVED` 记录；优先尝试扣减历史次数额度。
2. 历史额度不足时，在同一短事务内按 hold 扣减钱包，并把 hold 写入 `charge_amount`。
3. 模型调用在事务外进行，避免把网络调用放进数据库事务。
4. 上游返回实际用量后，按请求内的费率快照计算实际费用：hold 大于实际费用时退回差额；实际费用相等时不动余额。
5. 实际费用大于 hold 时，只尝试补扣差额。补扣失败则进入 `REVIEW`，保留原 hold，禁止透支；实际用量和结算金额保留给人工对账。

无实际 usage 的取消、错误、超时或进程崩溃，不把预估 hold 当作消费：

- 明确失败或取消：释放记录并退回 hold；MVP 由商户吸收上游可能产生的估算误差，不向用户收取估算费用。
- 进程崩溃：恢复任务只处理 `status = 0` 且创建时间超过 15 分钟的 RESERVED 记录，释放并退款；不会自动处理 CONFIRMED、RELEASED 或 REVIEW。

账本状态固定为：

| 值 | 名称 | 含义 |
| ---: | --- | --- |
| `0` | `RESERVED` | 已暂扣 hold，等待模型结果 |
| `1` | `CONFIRMED` | 按实际 usage 结算完成 |
| `2` | `RELEASED` | 未产生 usage，hold 已释放 |
| `3` | `REVIEW` | 实际费用超过 hold 且无法补扣，保留 hold 等人工处理 |

## 3. 默认费率和配置

下表是 2026-10-02 的固定高峰参考值，单位为 CNY / 1M tokens。它们是本项目的默认值，不随供应商发票、峰谷时段或节假日自动变化；需要调整时通过配置评审后显式修改。

| 模型 | 输入未命中 | 缓存命中 | 输出 |
| --- | ---: | ---: | ---: |
| `deepseek-flash` | `2.00` | `0.04` | `8.00` |
| `deepseek-v4-pro` | `9.00` | `0.30` | `27.00` |
| `glm-5.3-flash` | `0.80` | `0.23` | `2.80` |

参考定价页： [DeepSeek 模型与价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing) 、[BigModel 定价](https://bigmodel.cn/pricing)。供应商页面可能变化；本项目不会自动把页面价格或假日折扣写入订单，订单始终使用自己的费率快照。

当前费率环境变量：

```text
DEEPSEEK_FLASH_INPUT_PRICE       # 默认 2.0
DEEPSEEK_FLASH_CACHE_PRICE       # 默认 0.04
DEEPSEEK_FLASH_OUTPUT_PRICE      # 默认 8.0
DEEPSEEK_PRO_INPUT_PRICE         # 默认 9.0
DEEPSEEK_PRO_CACHE_PRICE         # 默认 0.30
DEEPSEEK_PRO_OUTPUT_PRICE        # 默认 27.0
ZHIPU_FLASH_INPUT_PRICE          # 默认 0.8
ZHIPU_FLASH_CACHE_PRICE          # 默认 0.23
ZHIPU_FLASH_OUTPUT_PRICE         # 默认 2.8
```

`maxOutputTokens` 是模型级的输出 Token 上限，配置键为 `llm.models[].max-output-tokens`；未显式配置的模型使用代码默认值 `4096`，当前 GLM 配置为 `8192`。适配器用该值覆盖 provider 级 `request-options.max_tokens`，保证预冻结和实际请求使用同一个输出上限。它不是实际 usage。`completion_tokens` 已包含推理 Token 时，不再额外累加推理字段。

## 4. 迁移和运维

执行顺序：

1. 先确认基础 `account_balance.sql` 已执行，`account_wallet`（以及 `balance_grant`）已存在。
2. 执行 `chat_quota_request.sql`，创建聊天预授权账本。
3. 执行 `wallet_purchase.sql`，增加商品金额和订单金额快照列，并更新新商品种子数据。
4. 执行 `wallet_chat_billing.sql`，增加 Token 费率、usage、hold 和结算字段。
5. 执行 `recharge_fee.sql`，增加手续费快照列，并将当前钱包商品更新为净到账金额。

这些迁移均按 `information_schema` 检查后执行 `ALTER TABLE`，可以重复运行。它们不重写历史订单：历史次数订单的 `product_credit_amount` 保持 `NULL`，历史聊天记录的 `charge_amount` 也保持 `NULL`，继续使用原有次数语义；手续费上线前的余额订单保留原到账金额，手续费快照保持 `NULL`。不要用重新导入或 `DROP TABLE` 的方式替代迁移；生产执行前先备份并确认数据库、表和列名。

`docs/dev-ops/environment/sql/` 中的 SQL 是内部部署 helper，不是公开 API，也不能通过 HTTP/Dubbo 暴露任意 SQL 执行能力。仅由受控的发布/运维流程执行，并记录执行顺序和数据库版本。

## 5. 测试入口

测试不会启动 Spring Boot，不调用真实支付或模型 API。SQL 测试默认跳过，只有显式开启时才连接 MySQL：

```bash
CHAT_QUOTA_SQL_INTEGRATION=true \
MYSQL_URL='jdbc:mysql://127.0.0.1:3306/openai?useSSL=false&allowPublicKeyRetrieval=true' \
MYSQL_USERNAME=root MYSQL_PASSWORD='***' \
mvn -pl chatgpt-data-app -am -DskipTests=false \
  -Dtest=WalletPurchaseSqlTest,TokenBillingRepositoryTest,OpenAiRepositoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

也可使用测试 fixture 兼容的 `CHAT_QUOTA_JDBC_URL`、`CHAT_QUOTA_DB_USER`、`CHAT_QUOTA_DB_PASSWORD`。迁移完成后重点覆盖：

- 钱包购买的 real MyBatis mapper、发货 CAS、grant 唯一性、重复/并发发货、历史订单 quota 分支、事务回滚和金额快照；
- Token 预授权优先消耗历史额度、费率快照、实际 usage 退款、错误释放、15 分钟恢复以及不足补扣进入 `REVIEW`；
- SQL 测试使用唯一 fixture 账号和订单，不产生真实支付费用。

回归结果见下面的验证记录，后续修改仍需重新运行。

## 6. 最终验证记录

- 在 `CHAT_QUOTA_SQL_INTEGRATION=true` 下，最终选定的 61 个测试全部通过，0 个跳过。
- 后端 package 通过，应用已重启并监听 `8093`。
- 使用隔离的本地注册测试用户验证真实链路：初始次数额度为 0，钱包余额为 `1.00` CNY。真实 DeepSeek Flash 请求使用 prompt 8、output 1、cached 0，结算费用为 `0.00002400` CNY；GLM Flash 请求使用 prompt 16、output 3、cached 0，结算费用为 `0.00002120` CNY。两次请求均返回 `OK`，相同 request 重试后余额保持不变。
- 钱包购买发货已由真实 MySQL 测试验证；测试 fixture 已登出并完成数据库清理。
- 本任务没有重新执行 Alipay 浏览器支付流程；该项不属于本轮新增验证证据。
