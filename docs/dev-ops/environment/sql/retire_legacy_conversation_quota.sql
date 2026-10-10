-- Apply only after backing up user_account, openai_product, openai_order,
-- account_quota_grant and chat_quota_request, and stopping the old application.
-- Run after wallet_chat_billing.sql and wallet_purchase.sql.
-- No conversion from old conversation counts into money is performed.
USE openai;
DELIMITER $$
DROP PROCEDURE IF EXISTS retire_legacy_conversation_quota$$
CREATE PROCEDURE retire_legacy_conversation_quota()
BEGIN
    IF EXISTS (SELECT 1 FROM chat_quota_request WHERE charge_amount IS NULL AND status = 0) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unfinished legacy reservations must be resolved before migration';
    END IF;
    IF EXISTS (SELECT 1 FROM openai_product WHERE is_enabled = 1 AND credit_amount IS NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Disable legacy quota products before migration';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'user_account' AND column_name = 'total_quota') THEN
        ALTER TABLE user_account DROP COLUMN total_quota;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'user_account' AND column_name = 'surplus_quota') THEN
        ALTER TABLE user_account DROP COLUMN surplus_quota;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'openai_product' AND column_name = 'quota') THEN
        ALTER TABLE openai_product DROP COLUMN quota;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'openai_order' AND column_name = 'product_quota') THEN
        ALTER TABLE openai_order DROP COLUMN product_quota;
    END IF;
    DROP TABLE IF EXISTS account_quota_grant;
END$$
CALL retire_legacy_conversation_quota()$$
DROP PROCEDURE retire_legacy_conversation_quota$$
DELIMITER ;
