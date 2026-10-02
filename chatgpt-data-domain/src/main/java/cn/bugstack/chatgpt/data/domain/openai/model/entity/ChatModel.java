package cn.bugstack.chatgpt.data.domain.openai.model.entity;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class ChatModel {
    private String id;
    private String displayName;
    private String provider;
    private String upstreamModel;
    private BigDecimal inputPrice;
    private BigDecimal cachedInputPrice;
    private BigDecimal outputPrice;
    private int maxOutputTokens = 4096;
}
