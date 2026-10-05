package cn.bugstack.chatgpt.data.trigger.http.dto;

import cn.bugstack.chatgpt.data.types.enums.ChatGPTModel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description
 * @create 2023-07-22 21:13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatGPTRequestDTO {

    /** 默认模型 */
    @Builder.Default
    private String model = ChatGPTModel.DEEPSEEK_CHAT.getCode();
    private String requestId;
    private boolean thinking;

    /** 问题描述 */
    private List<MessageEntity> messages;

}
