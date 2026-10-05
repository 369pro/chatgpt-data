# chatgpt-data

LLM Market 对话与账户服务，包含多模型流式对话、Token 计费、钱包充值和支付订单。配套项目为 `chatgpt-web`、`big-market` 和 `big-market-erp`。

## 本地部署

准备 JDK 17、Maven、MySQL 和 Redis；活动联动还需要 Nacos 与 `big-market`。当前本地端口为 `8093`，用户端为 `3003`；旧 Docker 示例中的 `8091` 不是当前本地默认端口。

在空数据库中导入 `docs/dev-ops/environment/sql/openai.sql`，再按顺序执行同目录的 `auth_account.sql`、`chat_quota_request.sql`、`account_balance.sql`、`account_quota_grant.sql`、`wallet_purchase.sql`、`recharge_fee.sql`、`wallet_chat_billing.sql`。支付宝沙箱和智谱接入分别参见 `alipay-sandbox-migration.sql`、`zhipu-model-access.sql`。执行前逐份检查 SQL；基础脚本只用于新库，已有数据库应先备份并按已执行版本选择增量，不能直接重建。

```bash
cp .env.example .env
# 编辑 .env，填入自己的配置后再启动
bash run-local.sh
```

`.env` 使用 Java properties 格式 `KEY=value`，不要添加 `export` 或引号。至少配置 MySQL 连接、Redis 连接、`APP_JWT_SECRET`（至少 32 字节）和需要启用的模型密钥。与大市场联动时，两端签名密钥应一致。智谱由 `ZHIPU_ENABLED` 开关控制，密钥只保存在后端。模型价格配置表示每百万 Token 的人民币费率，部署者应按自己的定价审核。

`run-local.sh` 默认重新打包，然后以 `dev` profile 启动。已有最新 JAR 时可用 `bash run-local.sh --no-build`。前端构建前设置 `NEXT_PUBLIC_API_HOST_URL` 为浏览器可访问的后端地址，并在后端配置匹配的 `APP_CROSS_ORIGIN`。

## 支付配置

以 `.env.payment.example` 为模板准备本地 `.env.payment`，填入自己的支付宝沙箱应用、密钥、回调地址等配置。不要把真实密钥上传到 Git。支付回调必须由支付平台访问得到，不能使用外部不可达的 localhost 地址。联调步骤见 `docs/` 中支付文档；生产部署须使用正式商户配置并验证验签、金额校验和重复通知幂等处理。

## 构建与运行检查

```bash
mvn clean package -DskipTests
# 依赖服务准备好后运行测试
mvn test
```

构建产物为 `chatgpt-data-app/target/chatgpt-data-app.jar`。生产环境建议由服务管理器或容器运行 JAR，通过 HTTPS 网关暴露业务接口。现有 `prod` 配置需要按实际环境完善，不能直接把开发配置视为生产配置。上线前检查数据库迁移、CORS、模型访问、充值回调和余额扣费；不要对公网暴露数据库、Redis 或 Nacos。

## 来源与安全

本项目基于原始 ChatGPT DDD 工程继续开发，保留代码中的原作者说明与授权信息。详细本地对话说明见 [docs/local-deepseek.md](docs/local-deepseek.md)。示例口令只适用于隔离开发环境；历史中出现过的真实凭据应撤销或轮换。
