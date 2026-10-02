package cn.bugstack.chatgpt.data.config;

import cn.bugstack.chatgpt.data.domain.openai.port.ChatProvider;
import cn.bugstack.chatgpt.data.domain.openai.service.ChatProviderRegistry;
import cn.bugstack.chatgpt.data.infrastructure.adapter.llm.OpenAiCompatibleChatProvider;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {
    @Bean
    public OkHttpClient llmHttpClient() {
        return new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
                .callTimeout(180, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
    }

    @Bean
    public ChatProviderRegistry chatProviderRegistry(LlmProperties properties, OkHttpClient llmHttpClient,
                                                      ObjectProvider<ChatProvider> customProviders) {
        List<ChatProvider> strategies = new ArrayList<>();
        customProviders.orderedStream().forEach(strategies::add);
        properties.getProviders().forEach((id, config) -> {
            if (!config.isEnabled()) return;
            if (!"openai-compatible".equals(config.getAdapter()))
                throw new IllegalArgumentException("Unknown LLM adapter: " + config.getAdapter());
            if (config.getApiKey() == null || config.getApiKey().isBlank())
                throw new IllegalArgumentException("Missing API key for LLM provider: " + id);
            strategies.add(new OpenAiCompatibleChatProvider(id, config.getBaseUrl(), config.getApiKey(),
                    llmHttpClient, config.getRequestOptions()));
        });
        var models = properties.getModels().stream().filter(model -> {
            var provider = properties.getProviders().get(model.getProvider());
            return provider == null || provider.isEnabled();
        }).collect(java.util.stream.Collectors.toList());
        return new ChatProviderRegistry(strategies, models, properties.getAliases());
    }
}
