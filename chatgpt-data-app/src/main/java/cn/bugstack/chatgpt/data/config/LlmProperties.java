package cn.bugstack.chatgpt.data.config;

import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatModel;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.*;

@Data
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {
    private Map<String, Provider> providers = new LinkedHashMap<>();
    private List<ChatModel> models = new ArrayList<>();
    private Map<String, String> aliases = new LinkedHashMap<>();

    @Data
    public static class Provider {
        private boolean enabled = true;
        private String adapter = "openai-compatible";
        private String baseUrl;
        private Map<String, Object> requestOptions = new LinkedHashMap<>();
        @ToString.Exclude
        private String apiKey;
    }
}
