package cn.bugstack.chatgpt.data.test.domain.openai;

import cn.bugstack.chatgpt.data.domain.openai.model.aggregates.ChatProcessAggregate;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatBillingQuote;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.RuleLogicEntity;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.UserAccountQuotaEntity;
import cn.bugstack.chatgpt.data.domain.openai.model.valobj.LogicCheckTypeVO;
import cn.bugstack.chatgpt.data.domain.openai.repository.IOpenAiRepository;
import cn.bugstack.chatgpt.data.domain.openai.service.AbstractChatService;
import cn.bugstack.chatgpt.data.domain.openai.port.ChatProvider;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatModel;
import cn.bugstack.chatgpt.data.domain.openai.service.ChatProviderRegistry;
import java.util.List;
import java.util.Map;
import cn.bugstack.chatgpt.data.domain.openai.service.rule.impl.AccessLimitFilter;
import cn.bugstack.chatgpt.data.domain.openai.service.rule.impl.UserQuotaFilter;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.math.BigDecimal;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ChatQuotaFlowTest {
    @Test
    public void synchronousProviderFailureReleasesReservation() throws Exception {
        IOpenAiRepository repository = mock(IOpenAiRepository.class);
        UserAccountQuotaEntity account = new UserAccountQuotaEntity();
        account.setOpenid("user");
        account.setSurplusQuota(2);
        when(repository.queryUserAccount("user")).thenReturn(account);
        when(repository.reserveChatQuota(eq("user"), eq("request"), any(ChatBillingQuote.class)))
                .thenReturn(true);
        UserQuotaFilter filter = new UserQuotaFilter();
        ReflectionTestUtils.setField(filter, "openAiRepository", repository);
        ChatProvider provider = mock(ChatProvider.class);
        when(provider.id()).thenReturn("deepseek");
        doThrow(new RuntimeException("unavailable")).when(provider).stream(any(), any());
        ChatModel model = new ChatModel();
        model.setId("deepseek-flash");
        model.setDisplayName("Flash");
        model.setProvider("deepseek");
        model.setUpstreamModel("deepseek-flash");
        model.setInputPrice(new BigDecimal("2.0"));
        model.setCachedInputPrice(new BigDecimal("0.04"));
        model.setOutputPrice(new BigDecimal("8.0"));
        model.setMaxOutputTokens(4096);
        ChatProviderRegistry providers = new ChatProviderRegistry(
                List.of(provider), List.of(model), Map.of());
        ReflectionTestUtils.setField(filter, "providers", providers);
        AbstractChatService service = new AbstractChatService(providers) {
            @Override
            protected RuleLogicEntity<ChatProcessAggregate> doCheckLogic(ChatProcessAggregate request,
                    UserAccountQuotaEntity data, String... logics) throws Exception {
                return filter.filter(request, data);
            }
        };
        ReflectionTestUtils.setField(service, "openAiRepository", repository);
        ResponseBodyEmitter emitter = mock(ResponseBodyEmitter.class);
        service.completions(emitter, ChatProcessAggregate.builder().openid("user").requestId("request").build());
        verify(repository).reserveChatQuota(eq("user"), eq("request"), any(ChatBillingQuote.class));
        verify(repository).settleChatQuota("user", "request", false);
        verify(emitter).send(contains("[错误]"));
        verify(emitter).complete();
    }

    @Test
    public void insufficientQuotaDoesNotCallProvider() throws Exception {
        IOpenAiRepository repository = mock(IOpenAiRepository.class);
        UserAccountQuotaEntity account = new UserAccountQuotaEntity();
        account.setOpenid("user");
        account.setSurplusQuota(1);
        account.setTotalQuota(1);
        ChatProvider provider = mock(ChatProvider.class);
        when(provider.id()).thenReturn("deepseek");
        ChatProviderRegistry providers = new ChatProviderRegistry(
                List.of(provider), List.of(pricedModel()), Map.of());
        UserQuotaFilter filter = new UserQuotaFilter();
        ReflectionTestUtils.setField(filter, "openAiRepository", repository);
        ReflectionTestUtils.setField(filter, "providers", providers);
        when(repository.reserveChatQuota(eq("user"), eq("request"), any(ChatBillingQuote.class)))
                .thenReturn(false);
        ChatProcessAggregate request = ChatProcessAggregate.builder().openid("user").requestId("request").build();
        assertEquals(LogicCheckTypeVO.REFUSE, filter.filter(request, account).getType());
        assertNull(request.getQuotaReservation());
        verify(repository, never()).settleChatQuota(anyString(), anyString(), anyBoolean());
    }

    @Test
    public void freeQuotaIsRefundedOnceAndConfirmedUsageIsRetained() throws Exception {
        Cache<String, Integer> cache = CacheBuilder.newBuilder().build();
        AccessLimitFilter filter = new AccessLimitFilter();
        ReflectionTestUtils.setField(filter, "visitCache", cache);
        ReflectionTestUtils.setField(filter, "limitCount", 1);
        ReflectionTestUtils.setField(filter, "whiteListStr", "");
        ChatProcessAggregate request = ChatProcessAggregate.builder().openid("user").build();
        assertEquals(LogicCheckTypeVO.SUCCESS, filter.filter(request, null).getType());
        assertEquals(Integer.valueOf(1), cache.getIfPresent("user"));
        request.getQuotaReservation().settle(false);
        request.getQuotaReservation().settle(false);
        assertEquals(Integer.valueOf(0), cache.getIfPresent("user"));
        assertEquals(LogicCheckTypeVO.SUCCESS, filter.filter(request, null).getType());
        request.getQuotaReservation().settle(true);
        request.getQuotaReservation().settle(false);
        assertEquals(Integer.valueOf(1), cache.getIfPresent("user"));
        assertEquals(LogicCheckTypeVO.REFUSE, filter.filter(request, null).getType());
    }

    private static ChatModel pricedModel() {
        ChatModel model = new ChatModel();
        model.setId("deepseek-flash");
        model.setDisplayName("Flash");
        model.setProvider("deepseek");
        model.setUpstreamModel("deepseek-flash");
        model.setInputPrice(new BigDecimal("2.0"));
        model.setCachedInputPrice(new BigDecimal("0.04"));
        model.setOutputPrice(new BigDecimal("8.0"));
        model.setMaxOutputTokens(4096);
        return model;
    }
}
