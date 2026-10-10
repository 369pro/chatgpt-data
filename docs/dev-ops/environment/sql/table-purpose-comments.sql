-- Table purpose documentation; safe to repeat. No business data changes.
USE openai;
SET NAMES utf8mb4;

ALTER TABLE `account_wallet` COMMENT = '用户余额钱包：按 openid 为每个用户保存一份 CNY 余额。available_amount 为当前可用金额，total_amount 为累计入账金额；充值和抽奖余额奖励增加余额，聊天请求预扣余额，结算后退还差额或失败退款。与 user_account 通过 openid 逻辑关联；入账凭据见 balance_grant，旧聊天扣费见 chat_quota_request，新 Agent 调用扣费见 agent_call_charge 和 ai_run_budget。';

ALTER TABLE `auth_account` COMMENT = '用户名密码登录凭据：保存唯一用户名、密码哈希及对应的 openid，用于注册和密码登录认证。username 与 openid 分别唯一；通过 openid 关联用户权限及钱包。密码字段只保存哈希；模型权限和冻结状态由 user_account 管理。';

ALTER TABLE `balance_grant` COMMENT = '余额入账流水及幂等凭据：记录充值到账、抽奖奖励等余额发放，amount 单位为人民币元。app_id 标识调用方，request_id 标识业务请求，两者组成唯一键，防止重复回调或重试造成重复入账。source 标识来源，例如 wallet_purchase 为充值、lottery 为抽奖；openid 关联 account_wallet。这里只记录入账，旧聊天消费见 chat_quota_request，新 Agent 消费见 agent_call_charge。';

ALTER TABLE `chat_quota_request` COMMENT = '聊天请求计费与幂等记录：按 openid + request_id 唯一标识一次请求，记录模型、Token 单价快照、预扣金额、实际结算金额及 Token 用量。status：0-已预留，1-已确认，2-已释放；用于重复请求防护、失败退款及超时预留恢复。金额单位为元，单价为元/百万 Token。表名沿用旧次数计费命名；charge_amount 为空的历史记录属于旧次数请求，不代表零元消费。';

ALTER TABLE `openai_order` COMMENT = '余额充值订单：保存购买用户、商品及到账金额/手续费快照、应付金额、支付渠道、支付流水和到账状态，用于支付回调、订单查询、超时关单及补偿发货。order_id 唯一，openid 关联用户；成功入账由 balance_grant 幂等保护并更新 account_wallet。订单状态：0-创建，1-等待发货，2-发货完成，3-关单；支付方式：0-微信Native，1-支付宝沙箱。旧模型套餐字段仅保留历史快照。';

ALTER TABLE `openai_product` COMMENT = '余额充值商品目录：定义商品名称、描述、售价 price、实际到账余额 credit_amount、展示排序 sort 和上架状态 is_enabled（0-下架，1-上架）。前台查询上架商品，下单时将商品及金额快照写入 openai_order，后续商品修改不应改变历史订单。金额单位为人民币元；充值增加钱包余额，不直接授予模型权限。product_model_types 为旧模型套餐兼容字段，新余额商品留空。';

ALTER TABLE `user_account` COMMENT = '用户业务账户与模型权限：openid 为唯一用户标识，可为微信 openid 或系统生成的用户ID；model_types 保存逗号分隔的可用模型ID，需匹配应用模型配置。status：0-可用，1-冻结。聊天校验账户状态与模型权限；拥有模型权限不代表对应服务渠道已启用。通过 openid 与 auth_account、account_wallet 逻辑关联；本表不保存密码、钱包余额或旧对话次数额度。';

