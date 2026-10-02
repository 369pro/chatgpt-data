package cn.bugstack.chatgpt.data.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * CNY wallet row. Total amount is cumulative granted amount.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AccountWalletPO {

    private Long id;
    private String openid;
    private String currency;
    private BigDecimal availableAmount;
    private BigDecimal totalAmount;
    private Date createTime;
    private Date updateTime;
}
