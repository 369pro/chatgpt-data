package cn.bugstack.chatgpt.data.domain.order.service;

import cn.bugstack.chatgpt.data.domain.order.model.entity.*;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PayTypeVO;
import cn.bugstack.chatgpt.data.domain.order.adapter.repository.IOrderRepository;
import javax.annotation.Resource;
import java.math.BigDecimal;

public abstract class AbstractOrderService implements IOrderService {
    @Resource protected IOrderRepository orderRepository;
    @Resource protected PaymentStrategies paymentStrategies;

    @Override
    public PayOrderEntity createOrder(ShopCartEntity cart) {
        paymentStrategies.get(cart.getPayType()).checkAvailable();
        ProductEntity product = orderRepository.queryProduct(cart.getProductId());
        if (product == null || !product.isAvailable() || product.getPrice() == null
                || product.getPrice().signum() <= 0
                || (product.getCreditAmount() != null && product.getCreditAmount().signum() <= 0)) {
            throw new IllegalArgumentException("Product unavailable");
        }
        product.applyRechargeFee();
        UnpaidOrderEntity unpaid = orderRepository.queryUnpaidOrder(cart);
        if (unpaid != null) {
            if (unpaid.getPayUrl() != null) {
                return PayOrderEntity.builder().openid(cart.getOpenid()).orderId(unpaid.getOrderId())
                        .payUrl(unpaid.getPayUrl()).payStatus(unpaid.getPayStatus()).build();
            }
            return doPrepayOrder(cart.getOpenid(), unpaid.getOrderId(), unpaid.getProductName(), unpaid.getTotalAmount(), cart.getPayType());
        }
        OrderEntity order = doSaveOrder(cart.getOpenid(), product, cart.getPayType());
        return doPrepayOrder(cart.getOpenid(), order.getOrderId(), product.getProductName(), order.getTotalAmount(), cart.getPayType());
    }

    protected abstract OrderEntity doSaveOrder(String openid, ProductEntity product, PayTypeVO type);
    protected abstract PayOrderEntity doPrepayOrder(String openid, String orderId, String name, BigDecimal amount, PayTypeVO type);
}
