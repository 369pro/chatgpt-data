package cn.bugstack.chatgpt.data.test.domain.openai;

import cn.bugstack.chatgpt.data.domain.openai.model.aggregates.ChatProcessAggregate;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.MessageEntity;
import cn.bugstack.chatgpt.data.domain.openai.service.rule.impl.SensitiveWordFilter;
import com.github.houbb.sensitive.word.bs.SensitiveWordBs;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SensitiveWordContextTest {
    @Test
    public void preservesRequestContextWhileFilteringMessages() throws Exception {
        SensitiveWordBs words = mock(SensitiveWordBs.class);
        when(words.replace("input")).thenReturn("filtered");
        SensitiveWordFilter filter = new SensitiveWordFilter();
        ReflectionTestUtils.setField(filter, "words", words);
        ReflectionTestUtils.setField(filter, "whiteListStr", "");
        ChatProcessAggregate request = ChatProcessAggregate.builder().openid("test-user")
                .model("glm-5.3-flash").requestId("request-1").thinking(true)
                .messages(List.of(MessageEntity.builder().role("user").content("input").build())).build();
        var result = filter.filter(request, null);
        assertSame(request, result.getData());
        assertEquals("request-1", result.getData().getRequestId());
        assertTrue(result.getData().isThinking());
        assertEquals("filtered", result.getData().getMessages().get(0).getContent());
    }
}
