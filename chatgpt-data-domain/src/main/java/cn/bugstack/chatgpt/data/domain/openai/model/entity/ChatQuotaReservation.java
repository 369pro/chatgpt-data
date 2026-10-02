package cn.bugstack.chatgpt.data.domain.openai.model.entity;

import cn.bugstack.chatgpt.data.domain.openai.repository.IOpenAiRepository;
import java.util.function.Consumer;

/** One reservation shared by provider, timeout and disconnect callbacks. */
public final class ChatQuotaReservation {
    private final Consumer<Boolean> settlement;
    private Consumer<ChatUsage> usageSettlement;
    private boolean settled;

    public ChatQuotaReservation(IOpenAiRepository repository, String openid, String requestId) {
        this(delivered -> repository.settleChatQuota(openid, requestId, delivered));
    }

    public ChatQuotaReservation(Consumer<Boolean> settlement) {
        this.settlement = settlement;
    }

    public synchronized void settle(boolean deliveredContent) {
        if (settled) return;
        settlement.accept(deliveredContent);
        settled = true;
    }

    public static ChatQuotaReservation tokenBilling(IOpenAiRepository repository, String openid, String requestId) {
        ChatQuotaReservation reservation = new ChatQuotaReservation(repository, openid, requestId);
        reservation.usageSettlement = usage -> repository.settleChatUsage(openid, requestId, usage);
        return reservation;
    }

    public boolean requiresUsage() { return usageSettlement != null; }

    public synchronized void settleUsage(ChatUsage usage) {
        if (settled) return;
        if (usageSettlement == null) settlement.accept(true);
        else usageSettlement.accept(usage);
        settled = true;
    }
}
