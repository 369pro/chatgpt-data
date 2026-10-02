package cn.bugstack.chatgpt.data.domain.openai.service.channel.model.impl;

import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatQuotaReservation;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatUsage;
import cn.bugstack.chatgpt.data.domain.openai.port.ChatCall;
import cn.bugstack.chatgpt.data.domain.openai.port.ChatObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/** Coordinates output, cancellation and quota settlement independently of the provider protocol. */
@Slf4j
public class ChatStreamListener implements ChatObserver {
    private final ResponseBodyEmitter emitter;
    private final ChatQuotaReservation reservation;
    private ChatCall call;
    private boolean finished;
    private boolean deliveredContent;
    private ChatUsage usage;

    public ChatStreamListener(ResponseBodyEmitter emitter) {
        this(emitter, null);
    }

    public ChatStreamListener(ResponseBodyEmitter emitter, ChatQuotaReservation reservation) {
        this.emitter = emitter;
        this.reservation = reservation;
    }

    public synchronized void attach(ChatCall call) {
        this.call = call;
        if (finished) call.cancel();
    }

    @Override
    public synchronized void onContent(String content) {
        if (finished || content == null || content.isEmpty()) return;
        try {
            emitter.send(content);
            deliveredContent = true;
            if (reservation == null || !reservation.requiresUsage()) settle();
        } catch (Exception error) {
            cancel();
            emitter.completeWithError(error);
        }
    }

    @Override
    public synchronized void onUsage(ChatUsage usage) {
        if (!finished) this.usage = usage;
    }

    @Override
    public synchronized void onComplete() {
        if (finished) return;
        if (!deliveredContent) {
            onError("模型未返回有效内容，请重试。");
            return;
        }
        finish();
        emitter.complete();
    }

    @Override
    public synchronized void onError(String message) {
        if (finished) return;
        finish();
        try {
            emitter.send("\n[错误] " + message);
            emitter.complete();
        } catch (Exception error) {
            emitter.completeWithError(error);
        }
    }

    public synchronized void cancel() {
        finish();
    }

    public void timeout() {
        onError("模型响应超时，请重试。");
    }

    private void finish() {
        finished = true;
        settle();
        if (call != null) call.cancel();
    }

    private void settle() {
        if (reservation == null) return;
        try {
            if (reservation.requiresUsage() && usage != null) reservation.settleUsage(usage);
            else reservation.settle(reservation.requiresUsage() ? false : deliveredContent);
        } catch (RuntimeException error) {
            log.error("Chat settlement failed; expired pending authorizations will be refunded", error);
        }
    }
}
