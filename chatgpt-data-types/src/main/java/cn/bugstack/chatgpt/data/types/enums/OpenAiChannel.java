package cn.bugstack.chatgpt.data.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description OpenAi 渠道
 * @create 2023-10-15 14:01
 */
@Getter
@AllArgsConstructor
public enum OpenAiChannel {

    ChatGPT("ChatGPT"),

    ;
    private final String code;

    public static OpenAiChannel getChannel(String model) {
        if ("deepseek-chat".equals(model) || "deepseek-reasoner".equals(model)) return OpenAiChannel.ChatGPT;
        return null;
    }

}
