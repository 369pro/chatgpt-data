package cn.bugstack.chatgpt.data.test.domain.openai;

import cn.bugstack.chatgpt.data.domain.openai.service.channel.model.impl.ChatStreamListener;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatQuotaReservation;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatUsage;
import cn.bugstack.chatgpt.data.domain.openai.repository.IOpenAiRepository;
import cn.bugstack.chatgpt.data.domain.openai.port.ChatCall;
import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ChatStreamListenerTest {
    @Test
    public void contentAndCompletionAreDeliveredOnce() throws Exception {
        ResponseBodyEmitter emitter = mock(ResponseBodyEmitter.class);
        ChatStreamListener listener = new ChatStreamListener(emitter);
        listener.onContent("hello");
        listener.onComplete();
        listener.onComplete();
        listener.onError("late error");
        verify(emitter).send("hello");
        verify(emitter).complete();
        verifyNoMoreInteractions(emitter);
    }

    @Test
    public void failureTimeoutAndDisconnectRefundOnlyOnce() {
        IOpenAiRepository repository = mock(IOpenAiRepository.class);
        ChatStreamListener listener = new ChatStreamListener(mock(ResponseBodyEmitter.class),
                new ChatQuotaReservation(repository, "user", "request"));
        listener.onError("failure");
        listener.timeout();
        listener.cancel();
        verify(repository).settleChatQuota("user", "request", false);
        verifyNoMoreInteractions(repository);
    }

    @Test
    public void partialReplyRemainsChargedAfterCancellation() {
        IOpenAiRepository repository = mock(IOpenAiRepository.class);
        ChatStreamListener listener = new ChatStreamListener(mock(ResponseBodyEmitter.class),
                new ChatQuotaReservation(repository, "user", "request"));
        listener.onContent("hello");
        listener.cancel();
        listener.onError("failure");
        verify(repository).settleChatQuota("user", "request", true);
        verifyNoMoreInteractions(repository);
    }

    @Test
    public void emptyCompletedResponseRefundsAndShowsError() throws Exception {
        IOpenAiRepository repository = mock(IOpenAiRepository.class);
        ResponseBodyEmitter emitter = mock(ResponseBodyEmitter.class);
        ChatStreamListener listener = new ChatStreamListener(emitter,
                new ChatQuotaReservation(repository, "user", "request"));
        listener.onComplete();
        verify(repository).settleChatQuota("user", "request", false);
        verify(emitter).send(contains("[错误]"));
    }

    @Test
    public void failedSendDoesNotConfirmCharge() throws Exception {
        IOpenAiRepository repository = mock(IOpenAiRepository.class);
        ResponseBodyEmitter emitter = mock(ResponseBodyEmitter.class);
        doThrow(new java.io.IOException("disconnected")).when(emitter).send(any(String.class));
        ChatStreamListener listener = new ChatStreamListener(emitter,
                new ChatQuotaReservation(repository, "user", "request"));
        listener.onContent("hello");
        verify(repository).settleChatQuota("user", "request", false);
        verify(emitter).completeWithError(any());
    }

    @Test
    public void cancellationBeforeProviderReturnsCancelsHandleAndIgnoresLateContent() {
        ResponseBodyEmitter emitter = mock(ResponseBodyEmitter.class);
        ChatStreamListener listener = new ChatStreamListener(emitter);
        listener.cancel();
        ChatCall call = mock(ChatCall.class);
        listener.attach(call);
        listener.onContent("late");
        verify(call).cancel();
        verifyNoInteractions(emitter);
    }

    @Test
    public void tokenReservationDoesNotSettleOnFirstContentChunk() throws Exception {
        IOpenAiRepository repository = mock(IOpenAiRepository.class);
        ChatStreamListener listener = new ChatStreamListener(mock(ResponseBodyEmitter.class),
                ChatQuotaReservation.tokenBilling(repository, "user", "request"));

        listener.onContent("hello");

        verifyNoInteractions(repository);
    }

    @Test
    public void tokenUsageConfirmsOnlyWhenTheStreamCompletes() throws Exception {
        IOpenAiRepository repository = mock(IOpenAiRepository.class);
        ResponseBodyEmitter emitter = mock(ResponseBodyEmitter.class);
        ChatStreamListener listener = new ChatStreamListener(emitter,
                ChatQuotaReservation.tokenBilling(repository, "user", "request"));
        ChatUsage usage = new ChatUsage(120, 40, 30);

        listener.onContent("hello");
        listener.onUsage(usage);
        verifyNoInteractions(repository);

        listener.onComplete();

        verify(repository).settleChatUsage("user", "request", usage);
        verifyNoMoreInteractions(repository);
        verify(emitter).complete();
    }

    @Test
    public void tokenCancellationWithoutUsageRefundsReservation() {
        IOpenAiRepository repository = mock(IOpenAiRepository.class);
        ChatStreamListener listener = new ChatStreamListener(mock(ResponseBodyEmitter.class),
                ChatQuotaReservation.tokenBilling(repository, "user", "request"));

        listener.onContent("partial");
        listener.cancel();
        listener.onError("late failure");

        verify(repository).settleChatQuota("user", "request", false);
        verifyNoMoreInteractions(repository);
    }

    @Test
    public void duplicateUsageSnapshotsSettleLatestUsageOnlyOnce() throws Exception {
        IOpenAiRepository repository = mock(IOpenAiRepository.class);
        ChatStreamListener listener = new ChatStreamListener(mock(ResponseBodyEmitter.class),
                ChatQuotaReservation.tokenBilling(repository, "user", "request"));
        ChatUsage first = new ChatUsage(100, 30, 20);
        ChatUsage latest = new ChatUsage(120, 40, 30);

        listener.onContent("hello");
        listener.onUsage(first);
        listener.onUsage(latest);
        listener.onComplete();
        listener.onComplete();
        listener.cancel();

        ArgumentCaptor<ChatUsage> usage = ArgumentCaptor.forClass(ChatUsage.class);
        verify(repository, times(1)).settleChatUsage(eq("user"), eq("request"), usage.capture());
        assertSame(latest, usage.getValue());
        verifyNoMoreInteractions(repository);
    }
}
