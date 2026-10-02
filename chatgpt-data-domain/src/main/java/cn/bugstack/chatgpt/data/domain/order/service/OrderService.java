package cn.bugstack.chatgpt.data.domain.order.service;

import cn.bugstack.chatgpt.data.domain.order.adapter.port.IOrderPort;
import cn.bugstack.chatgpt.data.domain.order.adapter.port.PaymentStrategy;
import cn.bugstack.chatgpt.data.domain.order.model.aggregates.CreateOrderAggregate;
import cn.bugstack.chatgpt.data.domain.order.model.entity.*;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.*;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class OrderService extends AbstractOrderService {
    @Resource private IOrderPort port;

    @Override
    protected OrderEntity doSaveOrder(String openid, ProductEntity product, PayTypeVO type) {
        OrderEntity order = OrderEntity.builder().orderId(RandomStringUtils.randomNumeric(12))
                .orderTime(new Date()).orderStatus(OrderStatusVO.CREATE)
                .totalAmount(product.getPrice()).payTypeVO(type).build();
        orderRepository.saveOrder(CreateOrderAggregate.builder().openid(openid).product(product).order(order).build());
        return order;
    }

    @Override
    protected PayOrderEntity doPrepayOrder(String openid, String id, String name, BigDecimal amount, PayTypeVO type) {
        OrderEntity order = queryOrder(id).getOrder();
        Date expiresAt = new Date(order.getOrderTime().getTime() + 30 * 60_000L);
        String url = paymentStrategies.get(type).create(id, name, amount, expiresAt);
        PayOrderEntity payment = PayOrderEntity.builder().openid(openid).orderId(id)
                .payUrl(url).payStatus(PayStatusVO.WAIT).build();
        orderRepository.updateOrderPayInfo(payment);
        return payment;
    }

    @Override
    public boolean changeOrderPaySuccess(String id, String transactionId, BigDecimal amount, Date paidAt) {
        CreateOrderAggregate aggregate = queryOrder(id);
        if (aggregate == null || amount == null || aggregate.getOrder().getTotalAmount().compareTo(amount) != 0
                || transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("Payment does not match order");
        }
        return orderRepository.changeOrderPaySuccess(id, transactionId, amount, paidAt == null ? new Date() : paidAt);
    }

    @Override
    public void acceptNotification(PayTypeVO type, Map<String, String> parameters) {
        PaymentResult result = paymentStrategies.get(type).verifyNotification(parameters);
        CreateOrderAggregate aggregate = queryOrder(result.getOrderId());
        if (aggregate == null || aggregate.getOrder().getPayTypeVO() != type ||
                result.getAmount() == null || aggregate.getOrder().getTotalAmount().compareTo(result.getAmount()) != 0) {
            throw new IllegalArgumentException("Payment does not match order");
        }
        // Signed notification is additionally checked against the merchant's trade query.
        // This also validates the receiving merchant when seller-id is not configured.
        if (result.isPaid() || result.isClosed()) reconcileOrder(result.getOrderId());
    }

    @Override
    public void reconcileOrder(String id) {
        CreateOrderAggregate aggregate = queryOrder(id);
        if (aggregate == null) throw new IllegalArgumentException("Order not found");
        OrderEntity order = aggregate.getOrder();
        if (order.getOrderStatus() == OrderStatusVO.COMPLETED) return;
        if (order.getOrderStatus() == OrderStatusVO.WAIT) { deliverGoods(id); return; }
        if (order.getPayTypeVO() != PayTypeVO.ALIPAY_SANDBOX) return;
        PaymentStrategy strategy = paymentStrategies.get(order.getPayTypeVO());
        PaymentResult result = strategy.query(id);
        if (result.isPaid()) {
            changeOrderPaySuccess(id, result.getTransactionId(), result.getAmount(), result.getPaidAt());
            deliverGoods(id);
        } else if (result.isClosed()) {
            changeOrderClose(id);
        } else if (order.getOrderStatus() == OrderStatusVO.CREATE &&
                System.currentTimeMillis() >= order.getOrderTime().getTime() + 30 * 60_000L) {
            // Close at the provider first; a failed/uncertain close is retried, never assumed.
            if (strategy.close(id)) changeOrderClose(id);
        }
    }

    @Override public CreateOrderAggregate queryOrder(String id) { return orderRepository.queryOrder(id); }
    @Override public void deliverGoods(String id) { orderRepository.deliverGoods(id); }
    @Override public List<String> queryReplenishmentOrder() { return orderRepository.queryReplenishmentOrder(); }
    @Override public List<String> queryNoPayNotifyOrder() { return orderRepository.queryNoPayNotifyOrder(); }
    @Override public List<String> queryTimeoutCloseOrderList() { return orderRepository.queryTimeoutCloseOrderList(); }
    @Override public boolean changeOrderClose(String id) { return orderRepository.changeOrderClose(id); }
    @Override public List<ProductEntity> queryProductList() { return orderRepository.queryProductList(); }
    @Override public void rebateGoods(String openid, String id) { port.rebate(openid, id); }
}
