-- LOCAL DEVELOPMENT ONLY. Back up openai_order and openai_product before running.
-- Replaces business fixtures; never changes auth_account or user_account.
-- Orders belong to the existing ui_check_1001 test login. No real payment or
-- quota delivery is performed. Terminal states keep scheduled jobs from acting.
SET NAMES utf8mb4;
USE openai;
SET @fixture_openid = (SELECT openid FROM auth_account WHERE username = 'ui_check_1001');
SET @fixture_time = UTC_TIMESTAMP();

START TRANSACTION;
DELETE FROM openai_order;
DELETE FROM openai_product;

INSERT INTO openai_product
    (product_id, product_name, product_desc, product_model_types, quota, price, sort, is_enabled)
VALUES
    (1001, '测试体验包', '测试数据：30次对话额度，用于基础购买流程验证', 'glm-4,gpt-4,gpt-3.5-turbo', 30, 9.90, 1, 1),
    (1002, '测试标准包', '测试数据：100次对话额度，用于常规套餐展示', 'glm-4,gpt-4,gpt-3.5-turbo', 100, 29.90, 2, 1),
    (1003, '测试进阶包', '测试数据：500次对话额度，用于大额度场景验证', 'glm-4,gpt-4,gpt-3.5-turbo', 500, 99.90, 3, 1),
    (1004, '测试下架包', '测试数据：已下架，前台商品列表不应展示', 'glm-4,gpt-4,gpt-3.5-turbo', 60, 19.90, 4, 0);

COMMIT;
