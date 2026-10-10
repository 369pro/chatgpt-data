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
    public void zeroBalanceWithoutBusinessAccountDoesNotCallProvider() throws Exception {
        IOpenAiRepository repository = mock(IOpenAiRepository.class);
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
        assertEquals(LogicCheckTypeVO.REFUSE, filter.filter(request, null).getType());
        assertNull(request.getQuotaReservation());
        verify(repository).reserveChatQuota(eq("user"), eq("request"), any(ChatBillingQuote.class));
        verify(repository, never()).settleChatQuota(anyString(), anyString(), anyBoolean());
    }

    @Test
    public void legacyAccessLimitNeverCreatesFreeReservation() throws Exception {
        AccessLimitFilter filter = new AccessLimitFilter();
        ChatProcessAggregate request = ChatProcessAggregate.builder().openid("user").build();
        assertEquals(LogicCheckTypeVO.SUCCESS, filter.filter(request, null).getType());
        assertNull(request.getQuotaReservation());
    }

    @Test
    public void fundedWalletWithoutBusinessAccountCreatesTokenReservation() throws Exception {
        IOpenAiRepository repository = mock(IOpenAiRepository.class);
        ChatProvider provider = mock(ChatProvider.class);
        when(provider.id()).thenReturn("deepseek");
        ChatProviderRegistry providers = new ChatProviderRegistry(
                List.of(provider), List.of(pricedModel()), Map.of());
        UserQuotaFilter filter = new UserQuotaFilter();
        ReflectionTestUtils.setField(filter, "openAiRepository", repository);
        ReflectionTestUtils.setField(filter, "providers", providers);
        when(repository.reserveChatQuota(eq("user"), eq("request"), any(ChatBillingQuote.class)))
                .thenReturn(true);

        ChatProcessAggregate request = ChatProcessAggregate.builder()
                .openid("user")
                .requestId("request")
                .build();
        assertEquals(LogicCheckTypeVO.SUCCESS, filter.filter(request, null).getType());
        assertNotNull(request.getQuotaReservation());
        verify(repository).reserveChatQuota(eq("user"), eq("request"), any(ChatBillingQuote.class));
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
