package cn.bugstack.chatgpt.data.trigger.job;

import cn.bugstack.chatgpt.data.domain.order.service.IOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;

@Slf4j
@Component
public class TimeoutCloseOrderJob {
    @Resource private IOrderService orderService;

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void exec() {
        for (String orderId : orderService.queryTimeoutCloseOrderList()) {
            try {
                orderService.reconcileOrder(orderId);
            } catch (Exception e) {
                log.warn("Payment reconciliation deferred for order {}", orderId);
            }
        }
    }
}
