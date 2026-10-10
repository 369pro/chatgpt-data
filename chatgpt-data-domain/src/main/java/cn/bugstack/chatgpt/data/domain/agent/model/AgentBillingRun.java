package cn.bugstack.chatgpt.data.domain.agent.model;

import java.math.BigDecimal;

/** Durable Agent budget snapshot returned by the billing service. */
public final class AgentBillingRun {
    private final String runId;
    private final String status;
    private final BigDecimal budgetLimit;
    private final BigDecimal reservedAmount;
    private final BigDecimal settledAmount;
    private final boolean reviewRequired;

    public AgentBillingRun(String runId, String status, BigDecimal budgetLimit,
                           BigDecimal reservedAmount, BigDecimal settledAmount,
                           boolean reviewRequired) {
        this.runId = runId;
        this.status = status;
        this.budgetLimit = budgetLimit;
        this.reservedAmount = reservedAmount;
        this.settledAmount = settledAmount;
        this.reviewRequired = reviewRequired;
    }

    public String getRunId() {
        return runId;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getBudgetLimit() {
        return budgetLimit;
    }

    public BigDecimal getReservedAmount() {
        return reservedAmount;
    }

    public BigDecimal getSettledAmount() {
        return settledAmount;
    }

    public boolean isReviewRequired() {
        return reviewRequired;
    }
}
