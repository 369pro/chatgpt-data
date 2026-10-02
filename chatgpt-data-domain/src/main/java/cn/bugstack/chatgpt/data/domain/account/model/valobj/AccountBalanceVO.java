package cn.bugstack.chatgpt.data.domain.account.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Current CNY wallet totals.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AccountBalanceVO {

    public static final String CURRENCY_CNY = "CNY";

    private String currency;
    private BigDecimal availableAmount;
    private BigDecimal totalAmount;
}
