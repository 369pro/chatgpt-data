package cn.bugstack.chatgpt.data.domain.openai.service;

import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatModel;
import cn.bugstack.chatgpt.data.domain.openai.port.ChatProvider;
import java.util.*;

/** Resolves public model IDs to configured provider strategies. */
public class ChatProviderRegistry {
    private final Map<String, ChatProvider> providers = new LinkedHashMap<>();
    private final Map<String, ChatModel> models = new LinkedHashMap<>();
    private final Map<String, String> aliases;

    public ChatProviderRegistry(List<ChatProvider> strategies, List<ChatModel> catalog, Map<String, String> aliases) {
        this.aliases = Map.copyOf(aliases);
        for (ChatProvider provider : strategies) {
            if (providers.putIfAbsent(provider.id(), provider) != null)
                throw new IllegalArgumentException("Duplicate chat provider: " + provider.id());
        }
        for (ChatModel model : catalog) {
            if (model.getId() == null || model.getId().isBlank() || model.getDisplayName() == null
                    || model.getUpstreamModel() == null || !providers.containsKey(model.getProvider()))
                throw new IllegalArgumentException("Invalid chat model configuration");
            if (models.putIfAbsent(model.getId(), model) != null)
                throw new IllegalArgumentException("Duplicate chat model: " + model.getId());
        }
        for (String target : aliases.values()) {
            if (!models.containsKey(target)) throw new IllegalArgumentException("Unknown model alias target: " + target);
        }
    }

    public ChatModel model(String id) {
        if (id == null) throw new IllegalArgumentException("Model is required");
        ChatModel model = models.get(aliases.getOrDefault(id, id));
        if (model == null) throw new IllegalArgumentException("Unsupported model");
        return model;
    }

    public ChatProvider provider(String modelId) {
        return providers.get(model(modelId).getProvider());
    }

    public List<ChatModel> models() {
        return List.copyOf(models.values());
    }
}
