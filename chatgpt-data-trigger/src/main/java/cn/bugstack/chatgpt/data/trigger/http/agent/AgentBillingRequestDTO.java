package cn.bugstack.chatgpt.data.trigger.http.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public final class AgentBillingRequestDTO {

    private AgentBillingRequestDTO() {
    }

    public static class RunRequest {
        @JsonProperty("run_id")
        private String runId;
        @JsonProperty("budget_limit")
        private BigDecimal budgetLimit;

        public String getRunId() { return runId; }
        public void setRunId(String runId) { this.runId = runId; }
        public BigDecimal getBudgetLimit() { return budgetLimit; }
        public void setBudgetLimit(BigDecimal budgetLimit) { this.budgetLimit = budgetLimit; }
    }

    public static class ReserveRequest {
        @JsonProperty("run_id")
        private String runId;
        @JsonProperty("call_id")
        private String callId;
        private String model;
        @JsonProperty("input_tokens_estimate")
        private Long inputTokensEstimate;
        @JsonProperty("max_output_tokens")
        private Integer maxOutputTokens;
        private String purpose;

        public String getRunId() { return runId; }
        public void setRunId(String runId) { this.runId = runId; }
        public String getCallId() { return callId; }
        public void setCallId(String callId) { this.callId = callId; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public Long getInputTokensEstimate() { return inputTokensEstimate; }
        public void setInputTokensEstimate(Long inputTokensEstimate) { this.inputTokensEstimate = inputTokensEstimate; }
        public Integer getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
        public String getPurpose() { return purpose; }
        public void setPurpose(String purpose) { this.purpose = purpose; }
    }

    public static class SettleRequest {
        @JsonProperty("call_id")
        private String callId;
        @JsonProperty("prompt_tokens")
        private Long promptTokens;
        @JsonProperty("completion_tokens")
        private Long completionTokens;
        @JsonProperty("cached_prompt_tokens")
        private Long cachedPromptTokens;

        public String getCallId() { return callId; }
        public void setCallId(String callId) { this.callId = callId; }
        public Long getPromptTokens() { return promptTokens; }
        public void setPromptTokens(Long promptTokens) { this.promptTokens = promptTokens; }
        public Long getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(Long completionTokens) { this.completionTokens = completionTokens; }
        public Long getCachedPromptTokens() { return cachedPromptTokens; }
        public void setCachedPromptTokens(Long cachedPromptTokens) { this.cachedPromptTokens = cachedPromptTokens; }
    }

    public static class ReleaseRequest {
        @JsonProperty("call_id")
        private String callId;
        private String reason;

        public String getCallId() { return callId; }
        public void setCallId(String callId) { this.callId = callId; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
