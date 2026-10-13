# DeepSeek 本地运行

> 本文是独立本机 `3003/8093` 方案。当前 Docker 环境使用 `3002/8091`，请按相邻 `big-market/docs/dev-ops/local/README.md` 启动。两种模式不要混用构建：Docker 前端使用 `npm run build:local` 和 `.next-local`，本机开发使用 `.next`。

## MVP 更新

- 模型列表由后端 `GET /api/v1/chatgpt/models` 提供，默认包含 `deepseek-flash`（V4.1 Flash）和 `deepseek-v4-pro`（V4 Pro）；启用 `ZHIPU_ENABLED` 后还会包含 `glm-5.3-flash`。旧会话中的 `deepseek-chat`、`deepseek-reasoner` 和 `deepseek-v4-flash` 会自动迁移到 `deepseek-flash`，消息保留。
- 模型配置位于 `chatgpt-data-app/src/main/resources/application-models.yml`。同协议的新提供商只需配置 `llm.providers` 与 `llm.models`；不同协议实现 `ChatProvider` 并注册为 Spring Bean。密钥仍只放后端 `.env`。
- 启动前增量执行 `docs/dev-ops/environment/sql/chat_quota_request.sql`，不要重新导入整个数据库。本机已执行。
- 账户额度先预占，输出首段有效内容后确认；此前失败或取消返还，部分回复后取消仍计一次。过期预占超过 15 分钟后由每分钟任务回收。免费体验次数仍是原有单机缓存，失败时也会返还，尚不是分布式计费。
- 同一账户的重复 `requestId` 不重复扣减；手动重试使用新 ID。数据库不可用或进程崩溃的极端情况下，待确认流水按超时返还处理，MVP 不承诺跨网络的精确一次计费。
- 本轮不升级 Spring Boot / Next.js，也不改抽奖、支付和现有路由。

后端沿用原有注册、登录、额度校验和流式对话接口。DeepSeek 的 Key 只保存在后端根目录 `.env`，不会交给浏览器。

## 环境

- JDK 17、Maven；运行验证脚本另需 Node.js 20 或更新版本。
- MySQL 8：本机 `127.0.0.1:3306/openai`。
- Redis：本机 `127.0.0.1:16379`。
- Nacos：本机 `127.0.0.1:8848`，保留现有 Dubbo 依赖。
- 前端为相邻的 `../chatgpt-web` 仓库。

上述端口均可通过 `.env` 修改。本机已有基础服务；不要重复导入原始 SQL 覆盖已有数据。空数据库请初始化 `docs/dev-ops/environment/sql/current-schema.sql`；已有数据库应先备份并按已执行版本选择增量迁移。

## 配置与启动

参考 `.env.example` 创建根目录 `.env`，填写 `DEEPSEEK_API_KEY`、`MYSQL_PASSWORD`、`REDIS_PASSWORD` 和 `APP_JWT_SECRET`。JWT 密钥至少 32 字节，可用 `openssl rand -hex 32` 生成。

与 `big-market` 联动时，两端必须使用相同的 `APP_JWT_SECRET` 和 Redis 会话存储，否则个人中心会把登录状态判为无效。本机 `.env` 已复用现有联动环境的 JWT 配置。

`.env` 使用 Java properties 格式：每行 `KEY=value`，不加 `export`，值不加引号。Spring Boot 从启动工作目录加载文件，系统环境变量可覆盖这些值。`DEEPSEEK_API_HOST` 默认 `https://api.deepseek.com/`，SDK 会追加 `v1/chat/completions`，不要重复添加 `/v1/`。

在后端仓库根目录执行：

```bash
bash run-local.sh
```

首次会构建项目；已构建时可以执行 `bash run-local.sh --no-build`。默认仅监听 `127.0.0.1:8093`。

独立本机模式在前端仓库临时指定 API 地址，不改写 Docker 默认 `.env.local`：

```bash
npm install
NEXT_PUBLIC_API_HOST_URL=http://127.0.0.1:8093 npx next dev -H 127.0.0.1 -p 3003
```

打开 `http://localhost:3003`，注册或登录账号，选择 `deepseek-flash`（默认）或 `deepseek-v4-pro` 开始对话；启用 `ZHIPU_ENABLED` 后还可选择 `glm-5.3-flash`。账户 `model_types` 应保存这些规范模型 ID，旧别名仅用于兼容已持久化的会话。新账号沿用原有免费访问额度，默认 10 次。前端变量带 `NEXT_PUBLIC_` 会公开到浏览器，绝对不要放 API Key。

## 验证

```bash
curl http://127.0.0.1:8093/actuator/health
node scripts/smoke-chat.mjs
mvn -pl chatgpt-data-app -am -DskipTests=false \
  -Dtest=ChatStreamListenerTest,PasswordAuthTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false test
```

冒烟脚本会创建一个独立测试账号、调用两次真实 DeepSeek 对话并消耗少量 API 额度，验证后注销测试会话，账号记录保留。API 地址可用 `CHAT_API_URL` 覆盖。

## GitHub 提交

提交 `.env.example`、源码和文档，不提交 `.env`、`.env.local`、运行日志或构建产物。可以用 `git check-ignore .env` 确认本地配置被忽略。

仓库原本已跟踪部分证书私钥、旧 profile 配置，`.gitignore` 不会自动移除已跟踪文件，也不会清理历史记录。在公开发布整个仓库前，需要单独检查和处理这些旧凭据；本次没有改写 Git 历史，也没有推送远程仓库。
