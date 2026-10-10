package cn.bugstack.chatgpt.data.domain.agent.model;

import java.math.BigDecimal;

/**
 * Result of one model-call authorization or settlement.
 *
 * Monetary fields are kept as BigDecimal at the Java boundary. HTTP adapters
 * serialize them with toPlainString so callers do not have to infer money from
 * JSON number formatting.
 */
public final class AgentBillingCall {
    private final String callId;
    private final String runId;
    private final String status;
    private final BigDecimal reservedAmount;
    private final BigDecimal settledAmount;
    private final BigDecimal observedAmount;
    private final BigDecimal observedExcessAmount;
    private final int maxOutputTokens;
    private final boolean reviewRequired;

    public AgentBillingCall(String callId, String runId, String status,
                            BigDecimal reservedAmount, BigDecimal settledAmount,
                            BigDecimal observedAmount, BigDecimal observedExcessAmount,
                            int maxOutputTokens, boolean reviewRequired) {
        this.callId = callId;
        this.runId = runId;
        this.status = status;
        this.reservedAmount = reservedAmount;
        this.settledAmount = settledAmount;
        this.observedAmount = observedAmount;
        this.observedExcessAmount = observedExcessAmount;
        this.maxOutputTokens = maxOutputTokens;
        this.reviewRequired = reviewRequired;
    }

    public String getCallId() {
        return callId;
    }

    public String getRunId() {
        return runId;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getReservedAmount() {
        return reservedAmount;
    }

    public BigDecimal getSettledAmount() {
        return settledAmount;
    }

    public BigDecimal getObservedAmount() {
        return observedAmount;
    }

    public BigDecimal getObservedExcessAmount() {
        return observedExcessAmount;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public boolean isReviewRequired() {
        return reviewRequired;
    }
}
