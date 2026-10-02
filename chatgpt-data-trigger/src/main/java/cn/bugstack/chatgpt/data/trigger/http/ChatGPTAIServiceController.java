package cn.bugstack.chatgpt.data.trigger.http;

import cn.bugstack.chatgpt.data.domain.auth.service.IAuthService;
import cn.bugstack.chatgpt.data.domain.openai.model.aggregates.ChatProcessAggregate;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.MessageEntity;
import cn.bugstack.chatgpt.data.domain.openai.service.IChatService;
import cn.bugstack.chatgpt.data.trigger.http.dto.ChatGPTRequestDTO;
import cn.bugstack.chatgpt.data.types.common.Constants;
import cn.bugstack.chatgpt.data.types.exception.ChatGPTException;
import cn.bugstack.chatgpt.data.types.model.Response;
import cn.bugstack.chatgpt.data.domain.openai.service.ChatProviderRegistry;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description ChatGPT AI 服务
 * @create 2023-07-16 09:19
 */
@Slf4j
@RestController()
@CrossOrigin("${app.config.cross-origin}")
@RequestMapping("/api/${app.config.api-version}/chatgpt/")
public class ChatGPTAIServiceController {

    @Resource
    private IChatService chatService;

    @Resource
    private IAuthService authService;

    @Resource
    private ChatProviderRegistry providers;

    @GetMapping("models")
    public List<Map<String, String>> models() {
        return providers.models().stream().map(model -> Map.of("id", model.getId(),
                "displayName", model.getDisplayName(), "provider", model.getProvider(),
                "inputPrice", model.getInputPrice().toPlainString(),
                "cachedInputPrice", model.getCachedInputPrice().toPlainString(),
                "outputPrice", model.getOutputPrice().toPlainString()))
                .collect(Collectors.toList());
    }

    /**
     * 【apix.natapp1.cc 是我在 <a href="https://natapp.cn/">https://natapp.cn</a> 购买的渠道，你需要自己购买一个使用】
     * 流式问题，ChatGPT 请求接口
     * <p>
     * curl -X POST \
     * http://apix.natapp1.cc/api/v1/chatgpt/chat/completions \
     * -H 'Content-Type: application/json;charset=utf-8' \
     * -H 'Authorization: b8b6' \
     * -d '{
     * "messages": [
     * {
     * "content": "写一个java冒泡排序",
     * "role": "user"
     * }
     * ],
     * "model": "gpt-3.5-turbo"
     * }'
     * <p>
     * curl -X POST \
     * http://localhost:8091/api/v1/chatgpt/chat/completions \
     * -H 'Content-Type: application/json;charset=utf-8' \
     * -H 'Authorization: b8b6' \
     * -d '{
     * "messages": [
     * {
     * "content": "1+1",
     * "role": "user"
     * }
     * ],
     * "model": "gpt-3.5-turbo"
     * }'
     */
    @RequestMapping(value = "chat/completions", method = RequestMethod.POST)
    public ResponseBodyEmitter completionsStream(@RequestBody ChatGPTRequestDTO request, @RequestHeader(value = "Authorization", required = false) String token, HttpServletResponse response) {
        log.info("流式问答请求开始，使用模型：{}", request.getModel());
        try {
            // 1. 基础配置；流式输出、编码、禁用缓存
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");

            // 2. 构建异步响应对象【对 Token 过期拦截】
            ResponseBodyEmitter emitter = new ResponseBodyEmitter(3 * 60 * 1000L);
            boolean success = token != null && authService.checkToken(token);

            if (!success) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                try {
                    emitter.send(Constants.ResponseCode.TOKEN_ERROR.getCode());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                emitter.complete();
                return emitter;
            }

            boolean supported;
            try {
                providers.model(request.getModel());
                supported = true;
            } catch (IllegalArgumentException invalid) {
                supported = false;
            }
            if (!supported || request.getMessages() == null || request.getMessages().isEmpty()
                    || request.getMessages().stream().anyMatch(message -> message == null
                    || message.getContent() == null || message.getRole() == null
                    || !("user".equals(message.getRole()) || "assistant".equals(message.getRole())
                    || "system".equals(message.getRole())))
                    || (request.getRequestId() != null && !request.getRequestId().matches("[A-Za-z0-9_-]{1,64}"))) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                emitter.send("\n[错误] 请求参数无效，请选择可用的对话模型。");
                emitter.complete();
                return emitter;
            }

            // 3. 获取 OpenID
            String openid = authService.openid(token);
            log.info("流式问答请求处理，openid:{} 请求模型:{}", openid, request.getModel());

            // 4. 构建参数
            ChatProcessAggregate chatProcessAggregate = ChatProcessAggregate.builder()
                    .openid(openid)
                    .requestId(request.getRequestId() == null ? UUID.randomUUID().toString() : request.getRequestId())
                    .thinking(request.isThinking() || "deepseek-reasoner".equals(request.getModel()))
                    .model(request.getModel())
                    .messages(request.getMessages().stream()
                            .map(entity -> MessageEntity.builder()
                                    .role(entity.getRole())
                                    .content(entity.getContent())
                                    .name(entity.getName())
                                    .build())
                            .collect(Collectors.toList()))
                    .build();

            // 5. 请求结果&返回
            return chatService.completions(emitter, chatProcessAggregate);
        } catch (Exception e) {
            log.error("流式应答，请求模型：{} 发生异常", request.getModel(), e);
            throw new ChatGPTException(e.getMessage());
        }
    }

    public ResponseBodyEmitter completionsStreamError(@RequestBody ChatGPTRequestDTO request, @RequestHeader("Authorization") String token, HttpServletResponse response) throws IOException {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");

        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        emitter.send("【错误编码】502"+ "\r\n");
        emitter.send("【错误提示】网络超时，请重新对话尝试。");
        emitter.complete();
        return emitter;
    }

}
