-- Incremental migration for reliable payment delivery and rebate events.
-- Existing orders are intentionally not backfilled; new transitions create rows.
CREATE TABLE IF NOT EXISTS payment_event_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(32) NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0 pending, 1 sent, 2 done, 3 dead/manual review',
    send_attempts INT NOT NULL DEFAULT 0,
    consume_attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at DATETIME NULL,
    done_at DATETIME NULL,
    last_error VARCHAR(512) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_event_type_order (event_type, order_id),
    KEY idx_payment_event_ready (status, next_attempt_at),
    KEY idx_payment_event_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
