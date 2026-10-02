package cn.bugstack.chatgpt.data.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * One idempotent CNY balance grant request.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BalanceGrantPO {

    private Long id;
    private String appId;
    private String requestId;
    private String openid;
    private BigDecimal amount;
    private String source;
    private Date createTime;
}
