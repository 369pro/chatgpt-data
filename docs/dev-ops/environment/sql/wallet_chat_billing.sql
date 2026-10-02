-- Run after account_balance.sql and chat_quota_request.sql. Safe to repeat.
USE openai;
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'chat_quota_request'
       AND column_name = 'charge_amount') = 0,
    'ALTER TABLE chat_quota_request ADD COLUMN charge_amount DECIMAL(20,8) NULL COMMENT ''CNY charge snapshot; NULL means legacy quota''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'chat_quota_request'
       AND column_name = 'model') = 0,
    'ALTER TABLE chat_quota_request ADD COLUMN model VARCHAR(64) NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'chat_quota_request'
       AND column_name = 'input_price') = 0,
    'ALTER TABLE chat_quota_request ADD COLUMN input_price DECIMAL(20,8) NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'chat_quota_request'
       AND column_name = 'cached_input_price') = 0,
    'ALTER TABLE chat_quota_request ADD COLUMN cached_input_price DECIMAL(20,8) NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'chat_quota_request'
       AND column_name = 'output_price') = 0,
    'ALTER TABLE chat_quota_request ADD COLUMN output_price DECIMAL(20,8) NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'chat_quota_request'
       AND column_name = 'settled_amount') = 0,
    'ALTER TABLE chat_quota_request ADD COLUMN settled_amount DECIMAL(20,8) NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'chat_quota_request'
       AND column_name = 'prompt_tokens') = 0,
    'ALTER TABLE chat_quota_request ADD COLUMN prompt_tokens BIGINT NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'chat_quota_request'
       AND column_name = 'completion_tokens') = 0,
    'ALTER TABLE chat_quota_request ADD COLUMN completion_tokens BIGINT NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'chat_quota_request'
       AND column_name = 'cached_prompt_tokens') = 0,
    'ALTER TABLE chat_quota_request ADD COLUMN cached_prompt_tokens BIGINT NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
