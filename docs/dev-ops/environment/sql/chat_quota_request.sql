-- Durable chat quota reservation ledger migration.
-- Apply to the openai database before wiring the chat reservation flow.
-- Status: 0 RESERVED, 1 CONFIRMED, 2 RELEASED.
-- The composite unique key rejects a reused request ID for the same user.

SET NAMES utf8mb4;
USE openai;

CREATE TABLE IF NOT EXISTS chat_quota_request (
    id bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    openid varchar(64) NOT NULL COMMENT '用户ID',
    request_id varchar(64) NOT NULL COMMENT '客户端或服务端生成的请求ID',
    status tinyint(1) NOT NULL COMMENT '0-RESERVED、1-CONFIRMED、2-RELEASED',
    create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uq_chat_quota_request_openid_request_id (openid, request_id),
    KEY idx_chat_quota_request_status_create_time (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
