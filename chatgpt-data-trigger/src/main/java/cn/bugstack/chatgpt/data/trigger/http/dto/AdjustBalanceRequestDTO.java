package cn.bugstack.chatgpt.data.trigger.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Internal app request for an idempotent CNY balance grant.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AdjustBalanceRequestDTO {

    private String appId;
    private String appToken;
    private String openid;
    private String requestId;
    private BigDecimal increaseAmount;
}
