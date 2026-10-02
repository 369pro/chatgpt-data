package cn.bugstack.chatgpt.data.test.payment;

import cn.bugstack.chatgpt.data.infrastructure.adapter.repository.OrderRepository;
import cn.bugstack.chatgpt.data.infrastructure.dao.IOpenAIOrderDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IOpenAIProductDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IUserAccountDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.OpenAIOrderPO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.UserAccountPO;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OrderRepositoryPaymentTest {

    private static final String ORDER_ID = "order-1";
    private static final String OPEN_ID = "user-1";

    private IOpenAIOrderDao orderDao;
    private IOpenAIProductDao productDao;
    private IUserAccountDao accountDao;
    private OrderRepository repository;

    @Before
    public void setUp() {
        orderDao = mock(IOpenAIOrderDao.class);
        productDao = mock(IOpenAIProductDao.class);
        accountDao = mock(IUserAccountDao.class);
        repository = new OrderRepository();
        ReflectionTestUtils.setField(repository, "openAIOrderDao", orderDao);
        ReflectionTestUtils.setField(repository, "openAIProductDao", productDao);
        ReflectionTestUtils.setField(repository, "userAccountDao", accountDao);
        ReflectionTestUtils.setField(repository, "modelTypes", "sandbox-model");
    }

    @Test
    public void repeatedDeliveryOnlyGrantsQuotaAfterSuccessfulStatusCas() {
        when(orderDao.queryOrder(ORDER_ID)).thenReturn(order());
        when(orderDao.updateOrderStatusDeliverGoods(ORDER_ID)).thenReturn(1, 0);
        when(accountDao.queryUserAccount(OPEN_ID)).thenReturn(existingAccount());
        when(accountDao.addAccountQuota(any(UserAccountPO.class))).thenReturn(1);

        repository.deliverGoods(ORDER_ID);
        repository.deliverGoods(ORDER_ID);

        verify(orderDao, times(2)).updateOrderStatusDeliverGoods(ORDER_ID);
        verify(accountDao, times(1)).queryUserAccount(OPEN_ID);
        verify(accountDao, times(1)).addAccountQuota(any(UserAccountPO.class));
        verify(accountDao, never()).insert(any(UserAccountPO.class));
    }

    @Test
    public void quotaFailurePropagatesForTransactionalRollback() throws Exception {
        when(orderDao.queryOrder(ORDER_ID)).thenReturn(order());
        when(orderDao.updateOrderStatusDeliverGoods(ORDER_ID)).thenReturn(1);
        when(accountDao.queryUserAccount(OPEN_ID)).thenReturn(existingAccount());
        when(accountDao.addAccountQuota(any(UserAccountPO.class))).thenReturn(0);

        assertThrows(RuntimeException.class, () -> repository.deliverGoods(ORDER_ID));

        verify(orderDao).updateOrderStatusDeliverGoods(ORDER_ID);
        verify(accountDao).addAccountQuota(any(UserAccountPO.class));
        verify(accountDao, never()).insert(any(UserAccountPO.class));

        Method method = OrderRepository.class.getMethod("deliverGoods", String.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional);
        assertArrayEquals(new Class<?>[]{Exception.class}, transactional.rollbackFor());
    }

    @Test
    public void paymentSuccessReturnsDaoCasOutcome() {
        when(orderDao.changeOrderPaySuccess(any(OpenAIOrderPO.class))).thenReturn(0, 1);

        boolean first = repository.changeOrderPaySuccess(
                ORDER_ID, "provider-tx", new java.math.BigDecimal("10.00"), new java.util.Date());
        boolean second = repository.changeOrderPaySuccess(
                ORDER_ID, "provider-tx", new java.math.BigDecimal("10.00"), new java.util.Date());

        assertEquals(false, first);
        assertEquals(true, second);
        verify(orderDao, times(2)).changeOrderPaySuccess(any(OpenAIOrderPO.class));
    }

    private OpenAIOrderPO order() {
        return OpenAIOrderPO.builder()
                .orderId(ORDER_ID)
                .openid(OPEN_ID)
                .productQuota(100)
                .build();
    }

    private UserAccountPO existingAccount() {
        return UserAccountPO.builder().openid(OPEN_ID).surplusQuota(50).build();
    }
}
