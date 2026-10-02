package cn.bugstack.chatgpt.data.domain.openai.service;

import java.util.UUID;

import javax.annotation.Resource;

import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import cn.bugstack.chatgpt.data.domain.openai.model.aggregates.ChatProcessAggregate;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.RuleLogicEntity;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.UserAccountQuotaEntity;
import cn.bugstack.chatgpt.data.domain.openai.model.valobj.LogicCheckTypeVO;
import cn.bugstack.chatgpt.data.domain.openai.repository.IOpenAiRepository;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatModel;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatRequest;
import cn.bugstack.chatgpt.data.domain.openai.service.channel.model.impl.ChatStreamListener;
import cn.bugstack.chatgpt.data.domain.openai.service.rule.factory.DefaultLogicFactory;
import cn.bugstack.chatgpt.data.types.common.Constants;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 对话模型抽象类
 * @create 2023-07-22 21:12
 */
@Slf4j
public abstract class AbstractChatService implements IChatService {

    private final ChatProviderRegistry providers;

    public AbstractChatService(ChatProviderRegistry providers) {
        this.providers = providers;
    }

    @Resource
    private IOpenAiRepository openAiRepository;

    @Override
    public ResponseBodyEmitter completions(ResponseBodyEmitter emitter, ChatProcessAggregate chatProcess) {
        try {
            ChatModel model = providers.model(chatProcess.getModel());
            chatProcess.setModel(model.getId());
            if (chatProcess.getRequestId() == null) {
                chatProcess.setRequestId(UUID.randomUUID().toString());
            }
            // 1. 请求应答
            emitter.onCompletion(() -> {
                log.info("流式问答请求完成，使用模型：{}", chatProcess.getModel());
            });
            emitter.onError(throwable -> log.error("流式问答请求异常，使用模型：{}", chatProcess.getModel(), throwable));

            // 2. 账户获取
            UserAccountQuotaEntity userAccountQuotaEntity = openAiRepository.queryUserAccount(chatProcess.getOpenid());

            // 3. 规则过滤
            RuleLogicEntity<ChatProcessAggregate> ruleLogicEntity = this.doCheckLogic(chatProcess, userAccountQuotaEntity,
                    DefaultLogicFactory.LogicModel.ACCESS_LIMIT.getCode(),
                    DefaultLogicFactory.LogicModel.SENSITIVE_WORD.getCode(),
                    null != userAccountQuotaEntity ? DefaultLogicFactory.LogicModel.ACCOUNT_STATUS.getCode() : DefaultLogicFactory.LogicModel.NULL.getCode(),
                    null != userAccountQuotaEntity ? DefaultLogicFactory.LogicModel.MODEL_TYPE.getCode() : DefaultLogicFactory.LogicModel.NULL.getCode(),
                    null != userAccountQuotaEntity ? DefaultLogicFactory.LogicModel.USER_QUOTA.getCode() : DefaultLogicFactory.LogicModel.NULL.getCode()
            );

            if (!LogicCheckTypeVO.SUCCESS.equals(ruleLogicEntity.getType())) {
                if (chatProcess.getQuotaReservation() != null) chatProcess.getQuotaReservation().settle(false);
                emitter.send("\n[错误] " + ruleLogicEntity.getInfo());
                emitter.complete();
                return emitter;
            }

            ChatStreamListener listener = new ChatStreamListener(emitter, chatProcess.getQuotaReservation());
            emitter.onTimeout(listener::timeout);
            emitter.onError(error -> listener.cancel());
            emitter.onCompletion(listener::cancel);
            listener.attach(providers.provider(model.getId()).stream(
                    new ChatRequest(model.getUpstreamModel(), ruleLogicEntity.getData().getMessages(),
                            chatProcess.isThinking(), model.getMaxOutputTokens()), listener));
        } catch (Exception e) {
            if (chatProcess.getQuotaReservation() != null) {
                try {
                    chatProcess.getQuotaReservation().settle(false);
                } catch (Exception settlementError) {
                    log.error("Quota release deferred to recovery, requestId:{}", chatProcess.getRequestId(), settlementError);
                }
            }
            log.error("流式问答，请求模型：{} 发生异常", chatProcess.getModel(), e);
            try {
                emitter.send("\n[错误] " + (e instanceof IllegalStateException
                        ? "请求已处理或正在处理中，请勿重复提交。" : "对话请求失败，请稍后重试。"));
                emitter.complete();
            } catch (Exception sendError) {
                emitter.completeWithError(sendError);
            }
        }

        // 5. 返回结果
        return emitter;
    }

    protected abstract RuleLogicEntity<ChatProcessAggregate> doCheckLogic(ChatProcessAggregate chatProcess, UserAccountQuotaEntity userAccountQuotaEntity, String... logics) throws Exception;

}
