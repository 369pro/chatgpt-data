package cn.bugstack.chatgpt.data.domain.order.model.entity;

import cn.bugstack.chatgpt.data.types.enums.OpenAIProductEnableModel;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.RechargeQuote;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 商品实体对象
 * @create 2023-10-05 11:42
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProductEntity {

    /**
     * 商品ID
     */
    private Integer productId;
    /**
     * 商品名称
     */
    private String productName;
    /**
     * 商品描述
     */
    private String productDesc;
    /**
     * 额度次数
     */
    private Integer quota;
    /**
     * 商品价格
     */
    private BigDecimal price;
    /**
     * 钱包充值金额；为空表示历史额度商品
     */
    private BigDecimal creditAmount;
    private BigDecimal feeRate;
    private BigDecimal feeAmount;

    /** Requote only current products; historical order snapshots must remain unchanged. */
    public void applyRechargeFee() {
        if (creditAmount == null) return;
        RechargeQuote quote = RechargeQuote.from(price);
        feeRate = quote.getFeeRate();
        feeAmount = quote.getFeeAmount();
        creditAmount = quote.getCreditAmount();
    }
    /**
     * 商品状态；0无效、1有效
     */
    private OpenAIProductEnableModel enable;

    /**
     * 是否有效；true = 有效，false = 无效
     */
    public boolean isAvailable() {
        return OpenAIProductEnableModel.OPEN.equals(enable);
    }

}
