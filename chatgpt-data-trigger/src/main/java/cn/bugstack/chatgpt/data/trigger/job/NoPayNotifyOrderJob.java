package cn.bugstack.chatgpt.data.trigger.job;

import cn.bugstack.chatgpt.data.domain.order.service.IOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;

@Slf4j
@Component
public class NoPayNotifyOrderJob {
    @Resource private IOrderService orderService;

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void exec() {
        for (String orderId : orderService.queryNoPayNotifyOrder()) {
            try {
                orderService.reconcileOrder(orderId);
            } catch (Exception e) {
                log.warn("Payment reconciliation deferred for order {}", orderId);
            }
        }
    }
}
