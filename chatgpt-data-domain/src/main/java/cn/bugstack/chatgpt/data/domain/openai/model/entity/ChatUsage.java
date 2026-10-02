package cn.bugstack.chatgpt.data.domain.openai.model.entity;

/** Immutable token usage snapshot reported by a chat provider. */
public final class ChatUsage {

    private final long promptTokens;
    private final long completionTokens;
    private final long cachedPromptTokens;

    public ChatUsage(long promptTokens, long completionTokens, long cachedPromptTokens) {
        if (promptTokens < 0 || completionTokens < 0 || cachedPromptTokens < 0) {
            throw new IllegalArgumentException("Token counts must be nonnegative");
        }
        if (cachedPromptTokens > promptTokens) {
            throw new IllegalArgumentException("Cached prompt tokens cannot exceed prompt tokens");
        }
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.cachedPromptTokens = cachedPromptTokens;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getCachedPromptTokens() {
        return cachedPromptTokens;
    }
}
