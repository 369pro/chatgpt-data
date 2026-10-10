package cn.bugstack.chatgpt.data.test.payment;

import cn.bugstack.chatgpt.data.infrastructure.gateway.RebateServiceRPC;
import com.pro.trigger.api.IRebateService;
import com.pro.trigger.api.request.Request;
import com.pro.trigger.api.response.Response;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RebateServiceRPCTest {

    private IRebateService rebateService;
    private RebateServiceRPC rpc;

    @Before
    public void setUp() {
        rebateService = mock(IRebateService.class);
        rpc = new RebateServiceRPC();
        ReflectionTestUtils.setField(rpc, "rebateService", rebateService);
        ReflectionTestUtils.setField(rpc, "appId", "chatgpt-data");
        ReflectionTestUtils.setField(rpc, "appToken", "test-token");
    }

    @Test
    public void successCodeWithFalseDataIsRetriedAsFailure() {
        when(rebateService.rebate(any(Request.class)))
                .thenReturn(new Response<>("0000", "ok", false));

        assertThrows(IllegalStateException.class, () -> rpc.rebate("user-1", "order-1"));
    }

    @Test
    public void rpcExceptionIsPropagatedForMqRetry() {
        when(rebateService.rebate(any(Request.class)))
                .thenThrow(new IllegalStateException("network"));

        assertThrows(IllegalStateException.class, () -> rpc.rebate("user-1", "order-1"));
    }
}
