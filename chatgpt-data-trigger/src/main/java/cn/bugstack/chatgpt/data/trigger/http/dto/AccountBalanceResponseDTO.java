package cn.bugstack.chatgpt.data.trigger.http.dto;

import cn.bugstack.chatgpt.data.domain.account.model.valobj.AccountBalanceVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Money values are strings in the HTTP contract so JavaScript clients do not
 * lose precision. Clients can parse them only for display or arithmetic with
 * a decimal library.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AccountBalanceResponseDTO {

    private String currency;
    private String availableAmount;
    private String totalAmount;

    public static AccountBalanceResponseDTO from(AccountBalanceVO balance) {
        return AccountBalanceResponseDTO.builder()
                .currency(balance.getCurrency())
                .availableAmount(balance.getAvailableAmount().toPlainString())
                .totalAmount(balance.getTotalAmount().toPlainString())
                .build();
    }
}
