package cn.bugstack.chatgpt.data.test.payment;

import cn.bugstack.chatgpt.data.domain.order.model.entity.ShopCartEntity;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PayTypeVO;
import cn.bugstack.chatgpt.data.infrastructure.adapter.repository.OrderRepository;
import cn.bugstack.chatgpt.data.infrastructure.dao.IOpenAIOrderDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IOpenAIProductDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IUserAccountDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.OpenAIOrderPO;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

public class PaymentCutoffRegressionTest {

    private IOpenAIOrderDao orderDao;
    private OrderRepository repository;

    @Before
    public void setUp() {
        orderDao = mock(IOpenAIOrderDao.class);
        repository = new OrderRepository();
        ReflectionTestUtils.setField(repository, "openAIOrderDao", orderDao);
        ReflectionTestUtils.setField(repository, "openAIProductDao", mock(IOpenAIProductDao.class));
        ReflectionTestUtils.setField(repository, "userAccountDao", mock(IUserAccountDao.class));
    }

    @Test
    public void unpaidOrderLookupBindsThirtyMinuteCutoff() {
        ShopCartEntity cart = ShopCartEntity.builder()
                .openid("user-1")
                .productId(1001)
                .payType(PayTypeVO.ALIPAY_SANDBOX)
                .build();
        long before = System.currentTimeMillis();

        repository.queryUnpaidOrder(cart);

        long after = System.currentTimeMillis();
        ArgumentCaptor<OpenAIOrderPO> captor = ArgumentCaptor.forClass(OpenAIOrderPO.class);
        verify(orderDao).queryUnpaidOrder(captor.capture());
        assertCutoff(captor.getValue().getOrderTime(), 30 * 60_000L, before, after);
    }

    @Test
    public void scheduledOrderLookupsBindLocalClockCutoffs() {
        when(orderDao.queryReplenishmentOrder(any(Date.class))).thenReturn(Collections.emptyList());
        when(orderDao.queryNoPayNotifyOrder(any(Date.class))).thenReturn(Collections.emptyList());
        when(orderDao.queryTimeoutCloseOrderList(any(Date.class))).thenReturn(Collections.emptyList());

        long before = System.currentTimeMillis();
        repository.queryReplenishmentOrder();
        repository.queryNoPayNotifyOrder();
        repository.queryTimeoutCloseOrderList();
        long after = System.currentTimeMillis();

        ArgumentCaptor<Date> captor = ArgumentCaptor.forClass(Date.class);
        verify(orderDao).queryReplenishmentOrder(captor.capture());
        assertCutoff(captor.getValue(), 3 * 60_000L, before, after);

        captor = ArgumentCaptor.forClass(Date.class);
        verify(orderDao).queryNoPayNotifyOrder(captor.capture());
        assertCutoff(captor.getValue(), 60_000L, before, after);

        captor = ArgumentCaptor.forClass(Date.class);
        verify(orderDao).queryTimeoutCloseOrderList(captor.capture());
        assertCutoff(captor.getValue(), 30 * 60_000L, before, after);
    }

    @Test
    public void mapperUsesBoundDatesInsteadOfDatabaseNowArithmetic() throws IOException {
        String mapper;
        try (InputStream stream = getClass().getResourceAsStream("/mybatis/mapper/openai_order_mapper.xml")) {
            assertNotNull(stream);
            mapper = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(mapper.contains("order_time &lt;= #{olderThan}"));
        assertTrue(mapper.contains("order_time > #{orderTime}"));
        assertFalse(mapper.contains("NOW() >= order_time"));
        assertFalse(mapper.contains("INTERVAL 1 MINUTE"));
        assertFalse(mapper.contains("INTERVAL 3 MINUTE"));
        assertFalse(mapper.contains("INTERVAL 30 MINUTE"));
    }

    private void assertCutoff(Date cutoff, long ageMillis, long before, long after) {
        assertNotNull(cutoff);
        assertTrue(cutoff.getTime() >= before - ageMillis - 1_000L);
        assertTrue(cutoff.getTime() <= after - ageMillis + 1_000L);
    }
}
