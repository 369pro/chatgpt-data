-- Independent CNY wallet and lottery-grant idempotency ledger.
-- This migration deliberately does not convert historical count quotas.
SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS openai DEFAULT CHARACTER SET utf8mb4;
USE openai;

CREATE TABLE IF NOT EXISTS account_wallet (
    id bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    openid varchar(64) NOT NULL COMMENT '用户账户ID',
    currency char(3) NOT NULL DEFAULT 'CNY' COMMENT '钱包币种',
    available_amount decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '当前可用余额',
    total_amount decimal(20,8) NOT NULL DEFAULT '0.00000000' COMMENT '累计发放余额',
    create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uq_account_wallet_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS balance_grant (
    id bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    app_id varchar(64) NOT NULL COMMENT '调用方应用ID',
    request_id varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '幂等请求ID',
    openid varchar(64) NOT NULL COMMENT '用户账户ID',
    amount decimal(20,8) NOT NULL COMMENT '本次发放的CNY金额',
    source varchar(64) NOT NULL COMMENT '发放来源',
    create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uq_balance_grant_app_request (app_id, request_id),
    KEY idx_balance_grant_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
