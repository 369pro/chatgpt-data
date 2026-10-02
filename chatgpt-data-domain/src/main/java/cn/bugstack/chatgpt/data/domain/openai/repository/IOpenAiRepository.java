package cn.bugstack.chatgpt.data.domain.openai.repository;

import cn.bugstack.chatgpt.data.domain.openai.model.entity.UserAccountQuotaEntity;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatBillingQuote;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatUsage;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description OpenAi 仓储接口
 * @create 2023-10-03 16:49
 */
public interface IOpenAiRepository {

    int subAccountQuota(String openai);

    UserAccountQuotaEntity queryUserAccount(String openid);

    /**
     * Reserves one chat quota unit for a request. The reservation is durable
     * and idempotency is scoped to the user and request ID.
     *
     * @return {@code true} when one quota unit was reserved, otherwise
     * {@code false} when the existing account has no available quota
     * @throws IllegalStateException when the user has already used the request ID
     */
    boolean reserveChatQuota(String openid, String requestId);

    boolean reserveChatQuota(String openid, String requestId, ChatBillingQuote quote);

    void settleChatUsage(String openid, String requestId, ChatUsage usage);

    /**
     * Completes a reservation without holding a database transaction over the
     * model call. A failed request releases the unit exactly once.
     */
    void settleChatQuota(String openid, String requestId, boolean success);

    /**
     * Releases a bounded batch of reservations that have been stuck for too
     * long after a process crash.
     */
    void releaseExpiredChatQuotas();

}
