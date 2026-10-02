-- Run against openai. Preserve all existing model grants and quota balances.
UPDATE user_account
SET model_types = CONCAT_WS(',', NULLIF(model_types, ''), 'glm-5.3-flash')
WHERE FIND_IN_SET('glm-5.3-flash', COALESCE(model_types, '')) = 0
  AND (FIND_IN_SET('deepseek-flash', model_types) > 0
    OR FIND_IN_SET('deepseek-v4-pro', model_types) > 0
    OR FIND_IN_SET('deepseek-chat', model_types) > 0
    OR FIND_IN_SET('deepseek-reasoner', model_types) > 0
    OR FIND_IN_SET('glm-4', model_types) > 0);
