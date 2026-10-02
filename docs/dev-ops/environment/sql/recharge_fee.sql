-- Run after wallet_purchase.sql. Historical order snapshots are never backfilled.
SET NAMES utf8mb4;
USE openai;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'openai_order'
        AND column_name = 'product_fee_rate') = 0,
    'ALTER TABLE openai_order ADD COLUMN product_fee_rate DECIMAL(5,4) NULL COMMENT ''Recharge service fee rate snapshot'' AFTER product_credit_amount',
    'SELECT 1');
PREPARE recharge_fee_stmt FROM @ddl;
EXECUTE recharge_fee_stmt;
DEALLOCATE PREPARE recharge_fee_stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'openai_order'
        AND column_name = 'product_fee_amount') = 0,
    'ALTER TABLE openai_order ADD COLUMN product_fee_amount DECIMAL(20,2) NULL COMMENT ''CNY recharge service fee snapshot'' AFTER product_fee_rate',
    'SELECT 1');
PREPARE recharge_fee_stmt FROM @ddl;
EXECUTE recharge_fee_stmt;
DEALLOCATE PREPARE recharge_fee_stmt;

-- Current wallet products use net credit; NULL remains a legacy quota product.
-- Always derive from price so rerunning this script cannot deduct the fee twice.
UPDATE openai_product
   SET credit_amount = price - ROUND(price * IF(price <= 50.00, 0.20, 0.10), 2)
 WHERE credit_amount IS NOT NULL AND price > 0;

UPDATE openai_product
   SET product_desc = '充值扣除手续费后计入对话余额'
 WHERE credit_amount IS NOT NULL AND product_id IN (1001, 1002, 1003)
   AND product_desc IN ('支付后到账 9.90 元 CNY 余额',
                        '支付后到账 29.90 元 CNY 余额',
                        '支付后到账 99.90 元 CNY 余额');
