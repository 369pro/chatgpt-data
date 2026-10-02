-- Apply once to the existing openai database. No account/order rows are deleted.
-- Signed page-payment URLs exceed the old 128-character WeChat QR URL limit.
ALTER TABLE openai.openai_order MODIFY COLUMN pay_url TEXT NULL;
