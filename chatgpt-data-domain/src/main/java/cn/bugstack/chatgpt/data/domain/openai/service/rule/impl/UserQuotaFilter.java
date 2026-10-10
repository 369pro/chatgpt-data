package cn.bugstack.chatgpt.data.domain.openai.service.rule.impl;

import cn.bugstack.chatgpt.data.domain.openai.annotation.LogicStrategy;
import cn.bugstack.chatgpt.data.domain.openai.model.aggregates.ChatProcessAggregate;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.RuleLogicEntity;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatQuotaReservation;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.UserAccountQuotaEntity;
import cn.bugstack.chatgpt.data.domain.openai.model.valobj.LogicCheckTypeVO;
import cn.bugstack.chatgpt.data.domain.openai.repository.IOpenAiRepository;
import cn.bugstack.chatgpt.data.domain.openai.service.rule.ILogicFilter;
import cn.bugstack.chatgpt.data.domain.openai.service.rule.factory.DefaultLogicFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import cn.bugstack.chatgpt.data.domain.openai.service.ChatProviderRegistry;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatBillingQuote;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 钱包 Token 预授权规则过滤
 * @create 2023-10-03 16:48
 */
@Slf4j
@Component
@LogicStrategy(logicMode = DefaultLogicFactory.LogicModel.USER_QUOTA)
public class UserQuotaFilter implements ILogicFilter<UserAccountQuotaEntity> {

    @Resource
    private IOpenAiRepository openAiRepository;
    @Resource
    private ChatProviderRegistry providers;

    @Override
    public RuleLogicEntity<ChatProcessAggregate> filter(ChatProcessAggregate chatProcess, UserAccountQuotaEntity data) throws Exception {
        String openid = chatProcess.getOpenid();
        if (openid == null || openid.trim().isEmpty()) {
            return RuleLogicEntity.<ChatProcessAggregate>builder()
                    .info("登录账户无效，请重新登录后重试。")
                    .type(LogicCheckTypeVO.REFUSE).data(chatProcess).build();
        }

        ChatBillingQuote quote = ChatBillingQuote.from(providers.model(chatProcess.getModel()),
                chatProcess.getMessages());
        if (openAiRepository.reserveChatQuota(openid, chatProcess.getRequestId(), quote)) {
            chatProcess.setQuotaReservation(ChatQuotaReservation.tokenBilling(
                    openAiRepository, openid, chatProcess.getRequestId()));
            return RuleLogicEntity.<ChatProcessAggregate>builder()
                    .type(LogicCheckTypeVO.SUCCESS).data(chatProcess).build();
        }

        return RuleLogicEntity.<ChatProcessAggregate>builder()
                .info("余额不足以预冻结本次请求费用，请充值或缩短上下文后重试。")
                .type(LogicCheckTypeVO.REFUSE).data(chatProcess).build();
    }

}
