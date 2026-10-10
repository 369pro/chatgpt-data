package cn.bugstack.chatgpt.data.trigger.job;

import cn.bugstack.chatgpt.data.domain.agent.service.IAgentBillingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/** Recovers only the independent Agent ledger; legacy chat rows are untouched. */
@Slf4j
@Component
public class AgentBillingRecoveryJob {

    @Resource
    private IAgentBillingService billingService;

    @Scheduled(fixedDelayString = "${agent.billing.recovery-delay-ms:60000}", initialDelayString = "${agent.billing.recovery-initial-delay-ms:60000}")
    public void releaseStaleAgentCalls() {
        try {
            int released = billingService.releaseStaleCalls();
            if (released > 0) log.info("Released {} stale Agent billing calls", released);
        } catch (RuntimeException error) {
            log.error("Agent billing recovery failed; will retry next interval", error);
        }
    }
}
