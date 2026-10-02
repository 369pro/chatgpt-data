package cn.bugstack.chatgpt.data.domain.openai.port;

public interface ChatObserver {
    void onContent(String content);
    void onComplete();
    void onError(String message);
}
