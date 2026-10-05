package cn.bugstack.chatgpt.data.test;

import cn.bugstack.chatgpt.data.trigger.http.dto.ChatGPTRequestDTO;
import com.alibaba.fastjson.JSON;
import org.junit.Test;
import static org.junit.Assert.*;

public class ApiTest {
    @Test
    public void requestSupportsExplicitModelAndRequestId() {
        ChatGPTRequestDTO request = JSON.parseObject(
                "{\"model\":\"deepseek-v4-pro\",\"requestId\":\"request-1\",\"thinking\":true}",
                ChatGPTRequestDTO.class);
        assertEquals("deepseek-v4-pro", request.getModel());
        assertEquals("request-1", request.getRequestId());
        assertTrue(request.isThinking());
    }
}
