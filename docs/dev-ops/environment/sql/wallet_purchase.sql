-- Wallet purchase migration. Run after the existing openai schema.
-- This migration is repeatable and deliberately leaves historical orders unchanged.
SET NAMES utf8mb4;
USE openai;

SET @ddl = IF(
    (SELECT COUNT(*)
       FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'openai_product'
        AND column_name = 'credit_amount') = 0,
    'ALTER TABLE openai_product ADD COLUMN credit_amount DECIMAL(20,8) NULL COMMENT ''CNY wallet credit amount'' AFTER price',
    'SELECT 1');
PREPARE wallet_purchase_stmt FROM @ddl;
EXECUTE wallet_purchase_stmt;
DEALLOCATE PREPARE wallet_purchase_stmt;

SET @ddl = IF(
    (SELECT COUNT(*)
       FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'openai_order'
        AND column_name = 'product_credit_amount') = 0,
    'ALTER TABLE openai_order ADD COLUMN product_credit_amount DECIMAL(20,8) NULL COMMENT ''CNY wallet credit snapshot; NULL means legacy quota'' AFTER product_quota',
    'SELECT 1');
PREPARE wallet_purchase_stmt FROM @ddl;
EXECUTE wallet_purchase_stmt;
DEALLOCATE PREPARE wallet_purchase_stmt;

UPDATE openai_product
   SET product_name = '余额充值 9.90 元',
       product_desc = '支付后到账 9.90 元 CNY 余额',
       quota = 0,
       price = 9.90,
       credit_amount = 9.90
 WHERE product_id = 1001 AND credit_amount IS NULL;

UPDATE openai_product
   SET product_name = '余额充值 29.90 元',
       product_desc = '支付后到账 29.90 元 CNY 余额',
       quota = 0,
       price = 29.90,
       credit_amount = 29.90
 WHERE product_id = 1002 AND credit_amount IS NULL;

UPDATE openai_product
   SET product_name = '余额充值 99.90 元',
       product_desc = '支付后到账 99.90 元 CNY 余额',
       quota = 0,
       price = 99.90,
       credit_amount = 99.90
 WHERE product_id = 1003 AND credit_amount IS NULL;
