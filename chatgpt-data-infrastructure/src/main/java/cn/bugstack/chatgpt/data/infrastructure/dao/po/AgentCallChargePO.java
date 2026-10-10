package cn.bugstack.chatgpt.data.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/** One idempotent, wallet-backed Agent model call. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentCallChargePO {
    public static final int RESERVED = 0;
    public static final int SETTLED = 1;
    public static final int RELEASED = 2;

    private Long id;
    private String userId;
    private String runId;
    private String callId;
    private String payloadHash;
    private String purpose;
    private String model;
    private BigDecimal inputPrice;
    private BigDecimal cachedInputPrice;
    private BigDecimal outputPrice;
    private Integer maxOutputTokens;
    private BigDecimal reservedAmount;
    private BigDecimal settledAmount;
    private BigDecimal observedAmount;
    private BigDecimal observedExcessAmount;
    private Long promptTokens;
    private Long completionTokens;
    private Long cachedPromptTokens;
    private Integer status;
    private Boolean reviewRequired;
    private String releaseReason;
    private Date leaseUntil;
    private Date createTime;
    private Date updateTime;
}
