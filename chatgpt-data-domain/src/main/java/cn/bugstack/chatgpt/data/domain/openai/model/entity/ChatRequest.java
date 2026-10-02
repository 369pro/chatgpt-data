package cn.bugstack.chatgpt.data.domain.openai.model.entity;

import java.util.List;

public final class ChatRequest {
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final String model;
    private final List<MessageEntity> messages;
    private final boolean thinking;
    private final int maxTokens;

    public ChatRequest(String model, List<MessageEntity> messages, boolean thinking) {
        this(model, messages, thinking, DEFAULT_MAX_TOKENS);
    }

    public ChatRequest(String model, List<MessageEntity> messages, boolean thinking, int maxTokens) {
        this.model = model;
        this.messages = messages;
        this.thinking = thinking;
        this.maxTokens = maxTokens;
    }

    public String getModel() {
        return model;
    }

    public List<MessageEntity> getMessages() {
        return messages;
    }

    public boolean isThinking() {
        return thinking;
    }

    public int getMaxTokens() {
        return maxTokens;
    }
}
