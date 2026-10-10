
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `account_wallet`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `account_wallet` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `openid` varchar(64) NOT NULL COMMENT '用户账户ID',
  `currency` char(3) NOT NULL DEFAULT 'CNY' COMMENT '钱包币种',
  `available_amount` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '当前可用余额',
  `total_amount` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '累计发放余额',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_account_wallet_openid` (`openid`)
) ENGINE=InnoDB AUTO_INCREMENT=65 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户余额钱包：按 openid 为每个用户保存一份 CNY 余额。available_amount 为当前可用金额，total_amount 为累计入账金额；充值和抽奖余额奖励增加余额，聊天请求预扣余额，结算后退还差额或失败退款。与 user_account 通过 openid 逻辑关联；入账凭据见 balance_grant，旧聊天扣费见 chat_quota_request，新 Agent 调用扣费见 agent_call_charge 和 ai_run_budget。';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `agent_call_charge`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agent_call_charge` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` varchar(64) NOT NULL COMMENT '用户 openid（认证身份）',
  `run_id` varchar(96) NOT NULL COMMENT '所属 Agent 运行幂等键',
  `call_id` varchar(128) NOT NULL COMMENT '单次模型调用幂等键',
  `payload_hash` char(64) NOT NULL COMMENT '不可变预留请求的 SHA-256 指纹',
  `purpose` varchar(64) NOT NULL DEFAULT 'agent' COMMENT '调用用途，例如 chat 或 research',
  `model` varchar(64) NOT NULL COMMENT 'Java 计费目录中的模型 id',
  `input_price` decimal(20,8) NOT NULL COMMENT '非缓存输入单价（人民币/百万 token）',
  `cached_input_price` decimal(20,8) NOT NULL COMMENT '缓存输入单价（人民币/百万 token）',
  `output_price` decimal(20,8) NOT NULL COMMENT '输出单价（人民币/百万 token）',
  `max_output_tokens` int NOT NULL COMMENT '本次调用授权的最大输出 token 数',
  `reserved_amount` decimal(20,8) NOT NULL COMMENT '从钱包预留的金额（人民币）',
  `settled_amount` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '实际向用户结算金额（不超过预留）',
  `observed_amount` decimal(20,8) DEFAULT NULL COMMENT '按上游实际 token 用量计算的金额（人民币）',
  `observed_excess_amount` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '超过预留且由平台承担的金额（人民币）',
  `prompt_tokens` bigint DEFAULT NULL COMMENT '上游返回的输入 token 数',
  `completion_tokens` bigint DEFAULT NULL COMMENT '上游返回的输出 token 数',
  `cached_prompt_tokens` bigint DEFAULT NULL COMMENT '上游返回的缓存输入 token 数',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0 已预留，1 已结算，2 已释放',
  `review_required` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否因超额用量需要复核：0 否，1 是',
  `release_reason` varchar(64) DEFAULT NULL COMMENT '释放原因，例如 unknown_usage 或 stale_recovery',
  `lease_until` datetime DEFAULT NULL COMMENT '过期回收截止时间；结算或释放后为 NULL',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_agent_call_charge_user_call` (`user_id`,`call_id`),
  KEY `idx_agent_call_charge_run` (`user_id`,`run_id`),
  KEY `idx_agent_call_charge_stale` (`status`,`lease_until`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent 单次模型调用的钱包预留、用量结算、幂等及过期回收审计';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `ai_run_budget`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_run_budget` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` varchar(64) NOT NULL COMMENT '用户 openid（认证身份）',
  `run_id` varchar(96) NOT NULL COMMENT 'Agent 运行幂等键',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0 活跃，1 已达预算上限，2 待人工复核',
  `budget_limit` decimal(20,8) NOT NULL COMMENT '本次运行允许向用户扣款的最高金额（人民币）',
  `reserved_amount` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '尚未结算的模型调用预留金额（人民币）',
  `settled_amount` decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '已结算且受预算上限保护的用户扣款（人民币）',
  `review_required` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否因上游用量超出预留而需要复核：0 否，1 是',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ai_run_budget_user_run` (`user_id`,`run_id`),
  KEY `idx_ai_run_budget_status_update` (`status`,`update_time`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent 运行预算聚合：预留、结算、释放均锁定本行以串行化预算上限';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `auth_account`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `auth_account` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `username` varchar(32) NOT NULL COMMENT '登录用户名',
  `openid` varchar(128) NOT NULL COMMENT '用户账户ID，与 user_account.openid 关联',
  `password_hash` varchar(100) NOT NULL COMMENT '登录密码哈希',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_openid` (`openid`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户名密码登录凭据：保存唯一用户名、密码哈希及对应的 openid，用于注册和密码登录认证。username 与 openid 分别唯一；通过 openid 关联用户权限及钱包。密码字段只保存哈希；模型权限和冻结状态由 user_account 管理。';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `balance_grant`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `balance_grant` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `app_id` varchar(64) NOT NULL COMMENT '调用方应用ID',
  `request_id` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '幂等请求ID',
  `openid` varchar(64) NOT NULL COMMENT '用户账户ID',
  `amount` decimal(20,8) NOT NULL COMMENT '本次发放的CNY金额',
  `source` varchar(64) NOT NULL COMMENT '发放来源',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_balance_grant_app_request` (`app_id`,`request_id`),
  KEY `idx_balance_grant_openid` (`openid`)
) ENGINE=InnoDB AUTO_INCREMENT=46 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='余额入账流水及幂等凭据：记录充值到账、抽奖奖励等余额发放，amount 单位为人民币元。app_id 标识调用方，request_id 标识业务请求，两者组成唯一键，防止重复回调或重试造成重复入账。source 标识来源，例如 wallet_purchase 为充值、lottery 为抽奖；openid 关联 account_wallet。这里只记录入账，旧聊天消费见 chat_quota_request，新 Agent 消费见 agent_call_charge。';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `chat_quota_request`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_quota_request` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `openid` varchar(64) NOT NULL COMMENT '用户ID',
  `request_id` varchar(64) NOT NULL COMMENT '客户端或服务端生成的请求ID',
  `status` tinyint(1) NOT NULL COMMENT '0-RESERVED、1-CONFIRMED、2-RELEASED',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `charge_amount` decimal(20,8) DEFAULT NULL COMMENT '预扣余额快照（元）；NULL 表示旧次数请求',
  `model` varchar(64) DEFAULT NULL COMMENT '请求使用的模型ID；历史请求可为空',
  `input_price` decimal(20,8) DEFAULT NULL COMMENT '每百万未缓存输入token单价（元）',
  `cached_input_price` decimal(20,8) DEFAULT NULL COMMENT '每百万缓存输入token单价（元）',
  `output_price` decimal(20,8) DEFAULT NULL COMMENT '每百万输出token单价（元）',
  `settled_amount` decimal(20,8) DEFAULT NULL COMMENT '实际结算金额（元）；未结算或历史请求可为空',
  `prompt_tokens` bigint DEFAULT NULL COMMENT '输入token总数，包含缓存命中部分',
  `completion_tokens` bigint DEFAULT NULL COMMENT '输出token数量',
  `cached_prompt_tokens` bigint DEFAULT NULL COMMENT '输入token中缓存命中的数量',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_chat_quota_request_openid_request_id` (`openid`,`request_id`),
  KEY `idx_chat_quota_request_status_create_time` (`status`,`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=60 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='聊天请求计费与幂等记录：按 openid + request_id 唯一标识一次请求，记录模型、Token 单价快照、预扣金额、实际结算金额及 Token 用量。status：0-已预留，1-已确认，2-已释放；用于重复请求防护、失败退款及超时预留恢复。金额单位为元，单价为元/百万 Token。表名沿用旧次数计费命名；charge_amount 为空的历史记录属于旧次数请求，不代表零元消费。';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `openai_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `openai_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `openid` varchar(128) NOT NULL COMMENT '用户账户ID：微信 openid 或系统生成的 u_ 标识',
  `product_id` int NOT NULL COMMENT '商品ID',
  `product_name` varchar(32) NOT NULL COMMENT '商品名称',
  `product_credit_amount` decimal(20,8) DEFAULT NULL COMMENT '到账余额快照（元）；NULL 表示旧套餐订单',
  `product_fee_rate` decimal(5,4) DEFAULT NULL COMMENT '充值服务费率快照，0 到 1；NULL 表示历史订单未记录',
  `product_fee_amount` decimal(20,2) DEFAULT NULL COMMENT '充值服务费快照（元）；NULL 表示历史订单未记录',
  `product_model_types` varchar(128) DEFAULT NULL COMMENT '旧模型套餐的历史订单快照；新余额订单留空',
  `order_id` varchar(12) NOT NULL COMMENT '订单编号',
  `order_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `order_status` tinyint(1) NOT NULL COMMENT '订单状态；0-创建完成、1-等待发货、2-发货完成、3-系统关单',
  `total_amount` decimal(10,2) NOT NULL COMMENT '订单金额',
  `pay_type` tinyint(1) NOT NULL DEFAULT '0' COMMENT '支付方式：0-微信Native，1-支付宝沙箱',
  `pay_url` text COMMENT '支付渠道返回的支付链接',
  `pay_amount` decimal(10,2) DEFAULT NULL COMMENT '支付金额；支付成功后，以回调信息更新金额',
  `transaction_id` varchar(32) DEFAULT NULL COMMENT '交易单号；支付成功后，回调信息的交易单号',
  `pay_status` tinyint(1) DEFAULT NULL COMMENT '支付状态；0-等待支付、1-支付完成、2-支付失败、3-放弃支付',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_order_id` (`order_id`),
  KEY `idx_openid` (`openid`),
  KEY `idx_order_status_pay_status_order_time` (`order_time`,`order_status`,`pay_status`)
) ENGINE=InnoDB AUTO_INCREMENT=71 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='余额充值订单：保存购买用户、商品及到账金额/手续费快照、应付金额、支付渠道、支付流水和到账状态，用于支付回调、订单查询、超时关单及补偿发货。order_id 唯一，openid 关联用户；成功入账由 balance_grant 幂等保护并更新 account_wallet。订单状态：0-创建，1-等待发货，2-发货完成，3-关单；支付方式：0-微信Native，1-支付宝沙箱。旧模型套餐字段仅保留历史快照。';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `openai_product`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `openai_product` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `product_id` int NOT NULL COMMENT '商品ID',
  `product_name` varchar(32) NOT NULL COMMENT '商品名称',
  `product_desc` varchar(128) NOT NULL COMMENT '商品描述',
  `product_model_types` varchar(128) DEFAULT NULL COMMENT '旧模型套餐兼容字段；余额充值不授予模型权限，新商品留空',
  `price` decimal(10,2) NOT NULL COMMENT '商品价格',
  `credit_amount` decimal(20,8) DEFAULT NULL COMMENT '实际到账余额（元）；NULL 表示旧套餐，不可上架',
  `sort` int NOT NULL COMMENT '商品排序',
  `is_enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否有效；0无效、1有效',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_product_id_enabled` (`product_id`,`is_enabled`)
) ENGINE=InnoDB AUTO_INCREMENT=20 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='余额充值商品目录：定义商品名称、描述、售价 price、实际到账余额 credit_amount、展示排序 sort 和上架状态 is_enabled（0-下架，1-上架）。前台查询上架商品，下单时将商品及金额快照写入 openai_order，后续商品修改不应改变历史订单。金额单位为人民币元；充值增加钱包余额，不直接授予模型权限。product_model_types 为旧模型套餐兼容字段，新余额商品留空。';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_account`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_account` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `openid` varchar(64) NOT NULL COMMENT '用户账户ID：微信 openid 或系统生成的 u_ 标识',
  `model_types` varchar(128) NOT NULL COMMENT '账号可用模型ID，逗号分隔；须与 application-models.yml 一致',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '账户状态；0-可用、1-冻结',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_openid` (`openid`),
  KEY `idx_surplus_quota_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=75 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户业务账户与模型权限：openid 为唯一用户标识，可为微信 openid 或系统生成的用户ID；model_types 保存逗号分隔的可用模型ID，需匹配应用模型配置。status：0-可用，1-冻结。聊天校验账户状态与模型权限；拥有模型权限不代表对应服务渠道已启用。通过 openid 与 auth_account、account_wallet 逻辑关联；本表不保存密码、钱包余额或旧对话次数额度。';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

