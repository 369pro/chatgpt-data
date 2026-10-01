-- Durable idempotency ledger for award/account quota grants.
SET NAMES utf8mb4;
USE openai;

CREATE TABLE IF NOT EXISTS account_quota_grant (
    id bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    openid varchar(64) NOT NULL COMMENT '用户ID',
    request_id varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '奖品抽奖订单号或其他幂等请求ID',
    increase_quota int(11) NOT NULL COMMENT '本次调增额度',
    create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uq_account_quota_grant_openid_request (openid, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
