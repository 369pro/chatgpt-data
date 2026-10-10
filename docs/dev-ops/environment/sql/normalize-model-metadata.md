# openai 本地库字段核查（2026-10-07）

迁移文件：`normalize-model-metadata.sql`，在现有钱包迁移和 `retire_legacy_conversation_quota.sql` 之后执行。仅修正已知旧默认模型列表，自定义权限不自动覆盖。模型 ID 来自当前源码和部署 JAR 的 `application-models.yml`，不代表已验证上游服务可用。

## 表用途

| 表 | 当前用途 | 处理 |
|---|---|---|
| user_account | 模型权限、冻结状态 | 4 个旧默认权限列表更新为 deepseek-flash,deepseek-v4-pro,glm-5.3-flash |
| account_wallet | 余额预扣、退款、充值 | 保留 |
| auth_account | 用户名密码登录 | 保留，补充字段说明 |
| balance_grant | 余额发放幂等及流水 | 保留 |
| chat_quota_request | 请求幂等、Token 计费快照及结算 | 保留；旧请求金额为空不能直接改成零 |
| openai_product | 余额充值商品 | 旧 product_model_types 清空；保留兼容字段并标注 |
| openai_order | 充值支付和到账快照 | 保留历史 product_model_types；补充支付与金额说明 |
| account_quota_grant | 已退役次数发放 | 核查时实际库已不存在，并非本次删除 |

当前运行 JAR 不含旧 account_quota_grant mapper。商品与订单 mapper 已不读写 product_model_types，保留该列用于历史快照和现有 SQL 测试/种子兼容。不同表的 status 有不同业务含义，不统一改值。openid 存在 64/128 字符长度差异，现有最长值为 34；本次不收窄，避免无必要的数据兼容风险。

## 验证

- 修改前完整 mysqldump：`/Users/tsy/WorkSpace/LLM-market/big-market/tmp/openai-audit/openai-before-20261007.sql`，文件权限限制为当前用户。
- 7 张表 CHECK TABLE 均 OK。
- 4 个账号均使用当前模型 ID；4 个商品旧模型权限均为空。
- 未发现负钱包余额、非法账号/聊天状态、重复 product_id、上架但缺失正数到账金额的商品。
- 历史聊天 5 条均已确认，金额为空代表旧次数请求；未伪造 Token 或金额记录。
- 历史订单和钱包金额未被迁移修改。

回退可使用完整备份恢复；恢复前应停止写入并另存最新数据，避免覆盖迁移后的新增业务记录。

## 接口与兼容性边界

模型目录接口已返回当前 DeepSeek 两个模型；GLM 默认禁用，权限列表保留不等于渠道启用。商品接口需要 Authorization，匿名验证返回 400，未验证登录后的商品接口。

`current-schema.sql` 为本次核查后的无数据结构快照，用于新空库。旧 `openai.sql`、`wallet_purchase.sql`、`openai-test-data.sql` 属于旧结构迁移/种子流程，不能在已退役次数字段的库上直接重跑。

big-market 源码仍保留 `/account/adjust_quota` 次数奖品调用；当前 chatgpt-data 已移除对应接口。若仍配置 `openai_use_count` 奖品，需要另行迁移奖品业务；本次没有删除任何实际存在的表，也未改变奖品语义。
