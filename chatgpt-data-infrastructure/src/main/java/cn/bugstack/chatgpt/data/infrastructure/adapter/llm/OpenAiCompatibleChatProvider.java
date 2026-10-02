package cn.bugstack.chatgpt.data.infrastructure.adapter.llm;

import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatRequest;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.MessageEntity;
import cn.bugstack.chatgpt.data.domain.openai.port.ChatCall;
import cn.bugstack.chatgpt.data.domain.openai.port.ChatObserver;
import cn.bugstack.chatgpt.data.domain.openai.port.ChatProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** OpenAI-compatible streaming adapter shared by OpenAI, DeepSeek, and local gateways. */
public final class OpenAiCompatibleChatProvider implements ChatProvider {

    private static final String DEEPSEEK_PROVIDER_ID = "deepseek";
    private static final int MAX_TOKENS = 4096;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final String providerId;
    private final String endpoint;
    private final String apiKey;
    private final EventSource.Factory eventSourceFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ObjectNode requestOptions;

    public OpenAiCompatibleChatProvider(String providerId, String baseUrl, String apiKey, OkHttpClient client) {
        this(providerId, baseUrl, apiKey, client, Map.of());
    }

    public OpenAiCompatibleChatProvider(String providerId, String baseUrl, String apiKey, OkHttpClient client,
                                        Map<String, Object> requestOptions) {
        this.providerId = requiredText(providerId, "providerId");
        this.endpoint = buildEndpoint(baseUrl);
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.eventSourceFactory = EventSources.createFactory(Objects.requireNonNull(client, "client"));
        this.requestOptions = objectMapper.valueToTree(Objects.requireNonNull(requestOptions, "requestOptions"));
    }

    @Override
    public String id() {
        return providerId;
    }

    @Override
    public ChatCall stream(ChatRequest request, ChatObserver observer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(observer, "observer");

        final String payload;
        try {
            payload = requestPayload(request);
        } catch (JsonProcessingException e) {
            observer.onError("Unable to encode chat request");
            return () -> { };
        }

        Request httpRequest = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload, JSON_MEDIA_TYPE))
                .build();

        StreamingCall call = new StreamingCall();
        EventSourceListener listener = new Listener(observer, call);
        EventSource source = eventSourceFactory.newEventSource(httpRequest, listener);
        call.attach(source);
        return call;
    }

    private String requestPayload(ChatRequest request) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("max_tokens", MAX_TOKENS);
        root.setAll(requestOptions);
        // Routing and messages always come from the validated domain request.
        root.put("model", request.getModel());
        root.put("stream", true);

        ArrayNode messages = root.putArray("messages");
        List<MessageEntity> requestMessages = request.getMessages();
        if (requestMessages != null) {
            for (MessageEntity message : requestMessages) {
                if (message == null) {
                    continue;
                }
                ObjectNode messageNode = messages.addObject();
                messageNode.put("role", message.getRole());
                messageNode.put("content", message.getContent());
                if (message.getName() != null) {
                    messageNode.put("name", message.getName());
                }
            }
        }

        if (DEEPSEEK_PROVIDER_ID.equalsIgnoreCase(providerId)) {
            ObjectNode thinking = root.putObject("thinking");
            thinking.put("type", request.isThinking() ? "enabled" : "disabled");
        }
        return objectMapper.writeValueAsString(root);
    }

    private static String buildEndpoint(String baseUrl) {
        String value = requiredText(baseUrl, "baseUrl");
        final URI base;
        try {
            base = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseUrl must be an HTTP(S) URL", e);
        }
        if (base.getScheme() == null
                || !("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getRawAuthority() == null
                || base.getQuery() != null
                || base.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must be an HTTP(S) URL without query or fragment");
        }

        String path = base.getRawPath() == null ? "" : base.getRawPath();
        while (path.endsWith("/") && !path.isEmpty()) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith("/chat/completions")) {
            return base.getScheme() + "://" + base.getRawAuthority() + path;
        }
        String suffix = hasV1Segment(path) ? "/chat/completions" : "/v1/chat/completions";
        return base.getScheme() + "://" + base.getRawAuthority() + path + suffix;
    }

    private static boolean hasV1Segment(String path) {
        if (path.isEmpty()) {
            return false;
        }
        for (String segment : path.split("/")) {
            if ("v1".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static final class StreamingCall implements ChatCall {
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicReference<EventSource> source = new AtomicReference<>();

        private void attach(EventSource eventSource) {
            source.set(eventSource);
            if (terminated.get()) eventSource.cancel();
        }

        private boolean terminate() {
            return terminated.compareAndSet(false, true);
        }

        private boolean isTerminated() {
            return terminated.get();
        }

        @Override
        public void cancel() {
            terminated.set(true);
            EventSource eventSource = source.get();
            if (eventSource != null) {
                eventSource.cancel();
            }
        }
    }

    private final class Listener extends EventSourceListener {
        private final ChatObserver observer;
        private final StreamingCall call;

        private Listener(ChatObserver observer, StreamingCall call) {
            this.observer = observer;
            this.call = call;
        }

        @Override
        public void onEvent(EventSource source, String id, String type, String data) {
            if (call.isTerminated()) {
                return;
            }
            if (data != null && "[DONE]".equals(data.trim())) {
                complete(source);
                return;
            }

            final JsonNode chunk;
            try {
                if (data == null || data.trim().isEmpty()) {
                    throw new JsonProcessingException("empty SSE data") { };
                }
                chunk = objectMapper.readTree(data);
                if (chunk == null || !chunk.isObject()) {
                    throw new JsonProcessingException("non-object SSE data") { };
                }
            } catch (JsonProcessingException e) {
                fail(source, "Provider returned malformed SSE data");
                return;
            }

            JsonNode choices = chunk.get("choices");
            if (choices == null || !choices.isArray()) {
                return;
            }

            boolean finished = false;
            for (JsonNode choice : choices) {
                if (call.isTerminated() || !choice.isObject()) {
                    continue;
                }
                JsonNode delta = choice.get("delta");
                JsonNode content = delta == null ? null : delta.get("content");
                if (content != null && content.isTextual() && !content.asText().isEmpty()) {
                    observer.onContent(content.asText());
                }

                JsonNode finishReason = choice.get("finish_reason");
                if (finishReason != null && finishReason.isTextual() && !finishReason.asText().isEmpty()) {
                    finished = true;
                }
            }
            if (finished) {
                complete(source);
            }
        }

        @Override
        public void onFailure(EventSource source, Throwable error, Response response) {
            if (response == null) {
                fail(source, "Provider request failed");
            } else {
                fail(source, "Provider request failed (HTTP " + response.code() + ")");
            }
        }

        @Override
        public void onClosed(EventSource source) {
            fail(source, "Provider stream ended before completion");
        }

        private void complete(EventSource source) {
            if (call.terminate()) {
                source.cancel();
                observer.onComplete();
            }
        }

        private void fail(EventSource source, String message) {
            if (call.terminate()) {
                source.cancel();
                observer.onError(message);
            }
        }
    }
}
