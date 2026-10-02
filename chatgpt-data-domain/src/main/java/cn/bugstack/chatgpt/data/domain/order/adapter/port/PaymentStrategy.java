package cn.bugstack.chatgpt.data.domain.order.adapter.port;

import cn.bugstack.chatgpt.data.domain.order.model.entity.PaymentResult;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PayTypeVO;
import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

public interface PaymentStrategy {
    PayTypeVO type();
    void checkAvailable();
    String create(String orderId, String subject, BigDecimal amount, Date expiresAt);
    PaymentResult query(String orderId);
    boolean close(String orderId);
    PaymentResult verifyNotification(Map<String, String> parameters);
}
