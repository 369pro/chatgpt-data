package cn.bugstack.chatgpt.data.domain.openai.port;

import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatUsage;

public interface ChatObserver {
    void onContent(String content);

    default void onUsage(ChatUsage usage) {
        // Usage is optional for observers that only consume streamed content.
    }

    void onComplete();
    void onError(String message);
}
