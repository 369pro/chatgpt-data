-- Durable wallet billing ledger for the independent Agent service.
-- Apply to the openai database after account_wallet and user_account exist.
-- This schema is separate from chat_quota_request so the legacy 15 minute
-- chat recovery job can never release an Agent reservation.

USE openai;

CREATE TABLE IF NOT EXISTS ai_run_budget (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL COMMENT '用户 openid（认证身份）',
    run_id VARCHAR(96) NOT NULL COMMENT 'Agent 运行幂等键',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0 活跃，1 已达预算上限，2 待人工复核',
    budget_limit DECIMAL(20,8) NOT NULL COMMENT '本次运行允许向用户扣款的最高金额（人民币）',
    reserved_amount DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '尚未结算的模型调用预留金额（人民币）',
    settled_amount DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '已结算且受预算上限保护的用户扣款（人民币）',
    review_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否因上游用量超出预留而需要复核：0 否，1 是',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uq_ai_run_budget_user_run (user_id, run_id),
    KEY idx_ai_run_budget_status_update (status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Agent 运行预算聚合：预留、结算、释放均锁定本行以串行化预算上限';

CREATE TABLE IF NOT EXISTS agent_call_charge (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL COMMENT '用户 openid（认证身份）',
    run_id VARCHAR(96) NOT NULL COMMENT '所属 Agent 运行幂等键',
    call_id VARCHAR(128) NOT NULL COMMENT '单次模型调用幂等键',
    payload_hash CHAR(64) NOT NULL COMMENT '不可变预留请求的 SHA-256 指纹',
    purpose VARCHAR(64) NOT NULL DEFAULT 'agent' COMMENT '调用用途，例如 chat 或 research',
    model VARCHAR(64) NOT NULL COMMENT 'Java 计费目录中的模型 id',
    input_price DECIMAL(20,8) NOT NULL COMMENT '非缓存输入单价（人民币/百万 token）',
    cached_input_price DECIMAL(20,8) NOT NULL COMMENT '缓存输入单价（人民币/百万 token）',
    output_price DECIMAL(20,8) NOT NULL COMMENT '输出单价（人民币/百万 token）',
    max_output_tokens INT NOT NULL COMMENT '本次调用授权的最大输出 token 数',
    reserved_amount DECIMAL(20,8) NOT NULL COMMENT '从钱包预留的金额（人民币）',
    settled_amount DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '实际向用户结算金额（不超过预留）',
    observed_amount DECIMAL(20,8) DEFAULT NULL COMMENT '按上游实际 token 用量计算的金额（人民币）',
    observed_excess_amount DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '超过预留且由平台承担的金额（人民币）',
    prompt_tokens BIGINT DEFAULT NULL COMMENT '上游返回的输入 token 数',
    completion_tokens BIGINT DEFAULT NULL COMMENT '上游返回的输出 token 数',
    cached_prompt_tokens BIGINT DEFAULT NULL COMMENT '上游返回的缓存输入 token 数',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0 已预留，1 已结算，2 已释放',
    review_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否因超额用量需要复核：0 否，1 是',
    release_reason VARCHAR(64) DEFAULT NULL COMMENT '释放原因，例如 unknown_usage 或 stale_recovery',
    lease_until DATETIME DEFAULT NULL COMMENT '过期回收截止时间；结算或释放后为 NULL',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uq_agent_call_charge_user_call (user_id, call_id),
    KEY idx_agent_call_charge_run (user_id, run_id),
    KEY idx_agent_call_charge_stale (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Agent 单次模型调用的钱包预留、用量结算、幂等及过期回收审计';
