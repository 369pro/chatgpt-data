package cn.bugstack.chatgpt.data.test.infrastructure;

import cn.bugstack.chatgpt.data.config.LlmConfig;
import cn.bugstack.chatgpt.data.config.LlmProperties;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatModel;
import cn.bugstack.chatgpt.data.domain.openai.port.ChatProvider;
import cn.bugstack.chatgpt.data.domain.openai.service.ChatProviderRegistry;
import okhttp3.OkHttpClient;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ZhipuProviderConfigTest {

    @Test
    public void disabledZhipuWithoutKeyPreservesDeepseekCatalog() {
        ChatProviderRegistry registry = registry(false, "");

        assertEquals(Collections.singletonList("deepseek-flash"), modelIds(registry));
        assertEquals("deepseek", registry.provider("deepseek-flash").id());
        assertFalse(modelIds(registry).contains("glm-5.3-flash"));
    }

    @Test
    public void enabledZhipuResolvesGlmToZhipuProvider() {
        ChatProviderRegistry registry = registry(true, "zhipu-key");

        assertTrue(modelIds(registry).containsAll(Arrays.asList("deepseek-flash", "glm-5.3-flash")));
        assertEquals("zhipu", registry.provider("glm-5.3-flash").id());
        assertEquals("glm-5.3-flash", registry.model("glm-5.3-flash").getUpstreamModel());
    }

    @Test
    public void enabledZhipuWithoutKeyFailsFast() {
        try {
            registry(true, "");
            fail("Expected enabled Zhipu provider without a key to fail");
        } catch (IllegalArgumentException exception) {
            assertEquals("Missing API key for LLM provider: zhipu", exception.getMessage());
        }
    }

    private ChatProviderRegistry registry(boolean zhipuEnabled, String zhipuApiKey) {
        LlmProperties properties = new LlmProperties();
        Map<String, LlmProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("deepseek", provider(true, "deepseek-key"));
        providers.put("zhipu", provider(zhipuEnabled, zhipuApiKey));
        properties.setProviders(providers);
        properties.setModels(List.of(
                model("deepseek-flash", "DeepSeek Flash", "deepseek", "deepseek-flash"),
                model("glm-5.3-flash", "GLM-5.3-Flash", "zhipu", "glm-5.3-flash")
        ));
        properties.setAliases(Collections.emptyMap());

        ObjectProvider<ChatProvider> customProviders = mock(ObjectProvider.class);
        when(customProviders.orderedStream()).thenReturn(java.util.stream.Stream.empty());
        return new LlmConfig().chatProviderRegistry(properties, new OkHttpClient(), customProviders);
    }

    private LlmProperties.Provider provider(boolean enabled, String apiKey) {
        LlmProperties.Provider provider = new LlmProperties.Provider();
        provider.setEnabled(enabled);
        provider.setBaseUrl("https://example.test");
        provider.setApiKey(apiKey);
        return provider;
    }

    private ChatModel model(String id, String displayName, String provider, String upstreamModel) {
        ChatModel model = new ChatModel();
        model.setId(id);
        model.setDisplayName(displayName);
        model.setProvider(provider);
        model.setUpstreamModel(upstreamModel);
        return model;
    }

    private List<String> modelIds(ChatProviderRegistry registry) {
        return registry.models().stream().map(ChatModel::getId).collect(Collectors.toList());
    }
}
