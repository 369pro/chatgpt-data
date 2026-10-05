package cn.bugstack.chatgpt.data.test.domain.openai;

import cn.bugstack.chatgpt.data.domain.openai.model.aggregates.ChatProcessAggregate;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.MessageEntity;
import cn.bugstack.chatgpt.data.domain.openai.service.IChatService;
import cn.bugstack.chatgpt.data.types.enums.ChatGPTModel;
import org.junit.Ignore;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 对话测试
 * @create 2023-10-03 18:13
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
@Ignore("Live provider smoke test is covered by scripts/smoke-chat.mjs")
public class ChatServiceTest {

    @Resource
    private IChatService chatService;
    @Test
    public void test_completions() throws InterruptedException {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();

        ChatProcessAggregate chatProcessAggregate = new ChatProcessAggregate();
        chatProcessAggregate.setOpenid("xfg");
        chatProcessAggregate.setModel(ChatGPTModel.DEEPSEEK_CHAT.getCode());
        chatProcessAggregate.setMessages(Collections.singletonList(MessageEntity.builder().role("user").content("你好").build()));

        ResponseBodyEmitter completions = chatService.completions(emitter, chatProcessAggregate);

        // 等待
        new CountDownLatch(1).await(90, java.util.concurrent.TimeUnit.SECONDS);

    }

}
