package cn.bugstack.chatgpt.data.domain.agent.service;

import cn.bugstack.chatgpt.data.domain.agent.model.AgentBillingCall;
import cn.bugstack.chatgpt.data.domain.agent.model.AgentBillingRun;

import java.math.BigDecimal;

/** Wallet-backed billing port used by the independent Agent service. */
public interface IAgentBillingService {

    AgentBillingRun createOrGetRun(String userId, String runId, BigDecimal budgetLimit);

    AgentBillingCall reserveCall(String userId, String runId, String callId, String model,
                                 long inputTokensEstimate, int maxOutputTokens, String purpose);

    AgentBillingCall settleCall(String userId, String callId, long promptTokens,
                                long completionTokens, long cachedPromptTokens);

    AgentBillingCall releaseCall(String userId, String callId, String reason);

    AgentBillingRun queryRun(String userId, String runId);

    /** Releases stale agent reservations only; legacy chat quota recovery is separate. */
    int releaseStaleCalls();
}
