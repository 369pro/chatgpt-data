package cn.bugstack.chatgpt.data.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/** Durable per-Agent-run budget aggregate. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentRunBudgetPO {
    public static final int ACTIVE = 0;
    public static final int CAP_REACHED = 1;
    public static final int REVIEW = 2;

    private Long id;
    private String userId;
    private String runId;
    private Integer status;
    private BigDecimal budgetLimit;
    private BigDecimal reservedAmount;
    private BigDecimal settledAmount;
    private Boolean reviewRequired;
    private Date createTime;
    private Date updateTime;
}
