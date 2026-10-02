package cn.bugstack.chatgpt.data.domain.openai.port;

import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatRequest;

public interface ChatProvider {
    String id();
    ChatCall stream(ChatRequest request, ChatObserver observer);
}
