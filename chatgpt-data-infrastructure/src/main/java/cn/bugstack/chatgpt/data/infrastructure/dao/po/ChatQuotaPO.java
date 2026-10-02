package cn.bugstack.chatgpt.data.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.math.BigDecimal;

/**
 * Durable chat-quota reservation ledger row.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatQuotaPO {

    public static final int RESERVED = 0;
    public static final int CONFIRMED = 1;
    public static final int RELEASED = 2;
    public static final int REVIEW = 3;

    private Long id;
    private String openid;
    private String requestId;
    private Integer status;
    /** Null identifies a legacy one-conversation entitlement. */
    private BigDecimal chargeAmount;
    private String model;
    private BigDecimal inputPrice;
    private BigDecimal cachedInputPrice;
    private BigDecimal outputPrice;
    private BigDecimal settledAmount;
    private Long promptTokens;
    private Long completionTokens;
    private Long cachedPromptTokens;
    private Date createTime;
    private Date updateTime;

    public static ChatQuotaPO reserved(String openid, String requestId) {
        return ChatQuotaPO.builder()
                .openid(openid)
                .requestId(requestId)
                .status(RESERVED)
                .build();
    }

}
