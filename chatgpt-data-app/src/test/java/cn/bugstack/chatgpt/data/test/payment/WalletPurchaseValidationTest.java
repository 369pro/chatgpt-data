package cn.bugstack.chatgpt.data.test.payment;

import cn.bugstack.chatgpt.data.domain.order.adapter.port.PaymentStrategy;
import cn.bugstack.chatgpt.data.domain.order.adapter.repository.IOrderRepository;
import cn.bugstack.chatgpt.data.domain.order.model.entity.ProductEntity;
import cn.bugstack.chatgpt.data.domain.order.model.entity.ShopCartEntity;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PayTypeVO;
import cn.bugstack.chatgpt.data.domain.order.service.OrderService;
import cn.bugstack.chatgpt.data.domain.order.service.PaymentStrategies;
import cn.bugstack.chatgpt.data.types.enums.OpenAIProductEnableModel;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class WalletPurchaseValidationTest {

    private IOrderRepository orderRepository;
    private OrderService orderService;

    @Before
    public void setUp() {
        orderRepository = mock(IOrderRepository.class);
        PaymentStrategy paymentStrategy = mock(PaymentStrategy.class);
        PaymentStrategies paymentStrategies = mock(PaymentStrategies.class);
        when(paymentStrategies.get(PayTypeVO.ALIPAY_SANDBOX)).thenReturn(paymentStrategy);

        orderService = new OrderService();
        ReflectionTestUtils.setField(orderService, "orderRepository", orderRepository);
        ReflectionTestUtils.setField(orderService, "paymentStrategies", paymentStrategies);
    }

    @Test
    public void invalidWalletCreditIsRejectedBeforeOrderPersistence() {
        when(orderRepository.queryProduct(1001)).thenReturn(product(BigDecimal.ZERO));

        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(cart()));

        verify(orderRepository, never()).queryUnpaidOrder(org.mockito.ArgumentMatchers.any());
        verify(orderRepository, never()).saveOrder(org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void missingPriceIsRejectedBeforeOrderPersistence() {
        ProductEntity product = product(new BigDecimal("9.90"));
        product.setPrice(null);
        when(orderRepository.queryProduct(1001)).thenReturn(product);

        assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(cart()));

        verify(orderRepository, never()).queryUnpaidOrder(org.mockito.ArgumentMatchers.any());
        verify(orderRepository, never()).saveOrder(org.mockito.ArgumentMatchers.any());
    }

    private ProductEntity product(BigDecimal creditAmount) {
        return ProductEntity.builder()
                .productId(1001)
                .productName("wallet")
                .productDesc("CNY")
                .quota(0)
                .price(new BigDecimal("9.90"))
                .creditAmount(creditAmount)
                .enable(OpenAIProductEnableModel.OPEN)
                .build();
    }

    private ShopCartEntity cart() {
        return ShopCartEntity.builder()
                .openid("user-1")
                .productId(1001)
                .payType(PayTypeVO.ALIPAY_SANDBOX)
                .build();
    }
}
