package cn.bugstack.chatgpt.data.infrastructure.payment;

import cn.bugstack.chatgpt.data.domain.order.adapter.port.PaymentStrategy;
import cn.bugstack.chatgpt.data.domain.order.model.entity.PaymentResult;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PayTypeVO;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

@Component
public class WeChatPaymentStrategy implements PaymentStrategy {
    public PayTypeVO type() { return PayTypeVO.WEIXIN_NATIVE; }
    public void checkAvailable() { throw new IllegalStateException("WeChat payment is not enabled"); }
    public String create(String id, String subject, BigDecimal amount, Date expiresAt) {
        checkAvailable(); return null;
    }
    public PaymentResult query(String id) { checkAvailable(); return null; }
    public boolean close(String id) { checkAvailable(); return false; }
    public PaymentResult verifyNotification(Map<String, String> parameters) { checkAvailable(); return null; }
}
