-- 已存在 Agent 计费表的中文注释补充脚本。
-- 仅 MODIFY COLUMN 的 COMMENT 与表 COMMENT；每一列的数据类型、默认值、可空性和索引定义
-- 与 agent-billing.sql 保持一致，不创建、删除、迁移或修改任何数据。

USE openai;

ALTER TABLE ai_run_budget
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    MODIFY COLUMN user_id VARCHAR(64) NOT NULL COMMENT '用户 openid（认证身份）',
    MODIFY COLUMN run_id VARCHAR(96) NOT NULL COMMENT 'Agent 运行幂等键',
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0 活跃，1 已达预算上限，2 待人工复核',
    MODIFY COLUMN budget_limit DECIMAL(20,8) NOT NULL COMMENT '本次运行允许向用户扣款的最高金额（人民币）',
    MODIFY COLUMN reserved_amount DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '尚未结算的模型调用预留金额（人民币）',
    MODIFY COLUMN settled_amount DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '已结算且受预算上限保护的用户扣款（人民币）',
    MODIFY COLUMN review_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否因上游用量超出预留而需要复核：0 否，1 是',
    MODIFY COLUMN create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    MODIFY COLUMN update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    COMMENT = 'Agent 运行预算聚合：预留、结算、释放均锁定本行以串行化预算上限';

ALTER TABLE agent_call_charge
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    MODIFY COLUMN user_id VARCHAR(64) NOT NULL COMMENT '用户 openid（认证身份）',
    MODIFY COLUMN run_id VARCHAR(96) NOT NULL COMMENT '所属 Agent 运行幂等键',
    MODIFY COLUMN call_id VARCHAR(128) NOT NULL COMMENT '单次模型调用幂等键',
    MODIFY COLUMN payload_hash CHAR(64) NOT NULL COMMENT '不可变预留请求的 SHA-256 指纹',
    MODIFY COLUMN purpose VARCHAR(64) NOT NULL DEFAULT 'agent' COMMENT '调用用途，例如 chat 或 research',
    MODIFY COLUMN model VARCHAR(64) NOT NULL COMMENT 'Java 计费目录中的模型 id',
    MODIFY COLUMN input_price DECIMAL(20,8) NOT NULL COMMENT '非缓存输入单价（人民币/百万 token）',
    MODIFY COLUMN cached_input_price DECIMAL(20,8) NOT NULL COMMENT '缓存输入单价（人民币/百万 token）',
    MODIFY COLUMN output_price DECIMAL(20,8) NOT NULL COMMENT '输出单价（人民币/百万 token）',
    MODIFY COLUMN max_output_tokens INT NOT NULL COMMENT '本次调用授权的最大输出 token 数',
    MODIFY COLUMN reserved_amount DECIMAL(20,8) NOT NULL COMMENT '从钱包预留的金额（人民币）',
    MODIFY COLUMN settled_amount DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '实际向用户结算金额（不超过预留）',
    MODIFY COLUMN observed_amount DECIMAL(20,8) DEFAULT NULL COMMENT '按上游实际 token 用量计算的金额（人民币）',
    MODIFY COLUMN observed_excess_amount DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '超过预留且由平台承担的金额（人民币）',
    MODIFY COLUMN prompt_tokens BIGINT DEFAULT NULL COMMENT '上游返回的输入 token 数',
    MODIFY COLUMN completion_tokens BIGINT DEFAULT NULL COMMENT '上游返回的输出 token 数',
    MODIFY COLUMN cached_prompt_tokens BIGINT DEFAULT NULL COMMENT '上游返回的缓存输入 token 数',
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0 已预留，1 已结算，2 已释放',
    MODIFY COLUMN review_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否因超额用量需要复核：0 否，1 是',
    MODIFY COLUMN release_reason VARCHAR(64) DEFAULT NULL COMMENT '释放原因，例如 unknown_usage 或 stale_recovery',
    MODIFY COLUMN lease_until DATETIME DEFAULT NULL COMMENT '过期回收截止时间；结算或释放后为 NULL',
    MODIFY COLUMN create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    MODIFY COLUMN update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    COMMENT = 'Agent 单次模型调用的钱包预留、用量结算、幂等及过期回收审计';
