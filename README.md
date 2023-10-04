# chatgpt-data | DDD 工程分层架构

## 测试脚本

### 1. 验证码

```java
curl -X POST \
 http://localhost:8091/api/v1/auth/gen/code \
-H 'Content-Type: application/x-www-form-urlencoded' \
-d 'openid=xfg'
```

- 也可以通过启动本地 natapp 内网穿透，对接公众号进行获取验证码

### 2. 登录 - 获取 Token

```java
curl -X POST \
http://localhost:8091/api/v1/auth/login \
-H 'Content-Type: application/x-www-form-urlencoded' \
-d 'code=7884'
```

- 登录后可以获取 Token

### 3. 功能 - 流式问题

```java
curl -X POST \
http://localhost:8091/api/v1/chatgpt/chat/completions \
-H 'Content-Type: application/json;charset=utf-8' \
-H 'Authorization: REDACTED' \
-d '{
"messages": [
{
"content": "1+1",
"role": "user"
}
],
"model": "gpt-3.5-turbo"
}'
```

- Token 是通过登录从控制台复制的，注意可别复制错了。