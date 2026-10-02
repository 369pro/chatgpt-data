package cn.bugstack.chatgpt.data.domain.openai.model.entity;

import lombok.Data;

@Data
public class ChatModel {
    private String id;
    private String displayName;
    private String provider;
    private String upstreamModel;
}
