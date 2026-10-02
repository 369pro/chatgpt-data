package cn.bugstack.chatgpt.data.domain.openai.model.entity;

import lombok.Value;
import java.util.List;

@Value
public class ChatRequest {
    String model;
    List<MessageEntity> messages;
    boolean thinking;
}
