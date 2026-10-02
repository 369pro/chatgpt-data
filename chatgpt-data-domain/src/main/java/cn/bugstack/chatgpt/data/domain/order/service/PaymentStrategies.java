package cn.bugstack.chatgpt.data.domain.order.service;

import cn.bugstack.chatgpt.data.domain.order.adapter.port.PaymentStrategy;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PayTypeVO;
import org.springframework.stereotype.Component;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentStrategies {
    private final Map<PayTypeVO, PaymentStrategy> strategies = new EnumMap<>(PayTypeVO.class);

    public PaymentStrategies(List<PaymentStrategy> implementations) {
        for (PaymentStrategy strategy : implementations) {
            if (strategies.put(strategy.type(), strategy) != null) {
                throw new IllegalStateException("Duplicate payment strategy");
            }
        }
    }

    public PaymentStrategy get(PayTypeVO type) {
        PaymentStrategy strategy = strategies.get(type);
        if (strategy == null) throw new IllegalArgumentException("Unsupported payment channel");
        return strategy;
    }
}
