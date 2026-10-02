package cn.bugstack.chatgpt.data.domain.account.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One idempotent CNY balance grant request.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AdjustBalanceEntity {

    private String appId;
    private String openid;
    private String requestId;
    private BigDecimal increaseAmount;
    private String source;
}
