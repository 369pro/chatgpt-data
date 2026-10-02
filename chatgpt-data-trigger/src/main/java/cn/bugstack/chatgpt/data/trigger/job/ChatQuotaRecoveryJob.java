package cn.bugstack.chatgpt.data.trigger.job;

import cn.bugstack.chatgpt.data.domain.openai.repository.IOpenAiRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ChatQuotaRecoveryJob {
    private final IOpenAiRepository repository;

    public ChatQuotaRecoveryJob(IOpenAiRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void releaseExpiredReservations() {
        try {
            repository.releaseExpiredChatQuotas();
        } catch (RuntimeException error) {
            log.error("Chat quota recovery failed; will retry next minute", error);
        }
    }
}
