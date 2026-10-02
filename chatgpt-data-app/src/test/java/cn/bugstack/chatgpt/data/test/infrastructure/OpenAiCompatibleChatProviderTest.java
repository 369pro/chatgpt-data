package cn.bugstack.chatgpt.data.test.infrastructure;

import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatRequest;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatUsage;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.MessageEntity;
import cn.bugstack.chatgpt.data.domain.openai.port.ChatCall;
import cn.bugstack.chatgpt.data.domain.openai.port.ChatObserver;
import cn.bugstack.chatgpt.data.infrastructure.adapter.llm.OpenAiCompatibleChatProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OpenAiCompatibleChatProviderTest {

    private HttpServer server;
    private ExecutorService executor;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
    }

    @After
    public void tearDown() throws InterruptedException {
        server.stop(0);
        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    public void streamsPayloadAndContentFromLocalOpenAiCompatibleEndpoint() throws Exception {
        AtomicReference<String> payload = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        CountDownLatch requestSeen = new CountDownLatch(1);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestSeen.countDown();
            byte[] response = ("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"!\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,"
                    + "\"prompt_cache_hit_tokens\":4}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        List<String> content = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<ChatUsage> usage = new AtomicReference<>();
        OpenAiCompatibleChatProvider provider = new OpenAiCompatibleChatProvider(
                "deepseek", baseUrl(), "test-key", new OkHttpClient());

        ChatCall call = provider.stream(
                new ChatRequest("deepseek-chat", Collections.singletonList(
                        MessageEntity.builder().role("user").content("hi").name("tester").build()), true),
                observer(content, completed, error, new AtomicInteger(), usage));

        assertTrue(requestSeen.await(3, TimeUnit.SECONDS));
        assertTrue(completed.await(3, TimeUnit.SECONDS));
        assertEquals("hello!", String.join("", content));
        assertNull(error.get());
        assertEquals(12L, usage.get().getPromptTokens());
        assertEquals(3L, usage.get().getCompletionTokens());
        assertEquals(4L, usage.get().getCachedPromptTokens());
        assertEquals("Bearer test-key", authorization.get());

        JsonNode request = new ObjectMapper().readTree(payload.get());
        assertEquals("deepseek-chat", request.get("model").asText());
        assertTrue(request.get("stream").asBoolean());
        assertEquals(4096, request.get("max_tokens").asInt());
        assertTrue(request.get("stream_options").get("include_usage").asBoolean());
        assertEquals("tester", request.get("messages").get(0).get("name").asText());
        assertEquals("enabled", request.get("thinking").get("type").asText());
        call.cancel();
    }

    @Test
    public void reportsSanitizedHttpFailureOnce() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "upstream-secret-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        OpenAiCompatibleChatProvider provider = new OpenAiCompatibleChatProvider(
                "openai", baseUrl(), "secret-key", new OkHttpClient());
        provider.stream(new ChatRequest("gpt-test", Collections.emptyList(), false),
                observer(new ArrayList<>(), failed, error, completions));

        assertTrue(failed.await(3, TimeUnit.SECONDS));
        assertEquals("Provider request failed (HTTP 503)", error.get());
        assertFalse(error.get().contains("secret"));
        assertEquals(0, completions.get());
    }

    @Test
    public void streamsZhipuV4WithConfiguredThinkingAndProtectedRouting() throws Exception {
        AtomicReference<String> payload = new AtomicReference<>();
        server.createContext("/api/paas/v4/chat/completions", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"internal\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"hello GLM\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":7,"
                    + "\"prompt_tokens_details\":{\"cached_tokens\":2}}}\n\n"
                    + "data: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        List<String> content = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        var provider = new OpenAiCompatibleChatProvider("zhipu",
                baseUrl() + "/api/paas/v4/chat/completions", "test-key", new OkHttpClient(),
                Map.of("thinking", Map.of("type", "enabled"), "reasoning_effort", "low",
                        "max_tokens", 8192, "model", "wrong-model", "stream", false,
                        "stream_options", Map.of("include_usage", false, "vendor_flag", "keep")));
        provider.stream(new ChatRequest("glm-5.3-flash", List.of(
                        MessageEntity.builder().role("user").content("hi").build()), false, 8192),
                observer(content, done, error, new AtomicInteger()));
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals("hello GLM", String.join("", content));
        JsonNode request = new ObjectMapper().readTree(payload.get());
        assertEquals("glm-5.3-flash", request.path("model").asText());
        assertTrue(request.path("stream").asBoolean());
        assertEquals("enabled", request.path("thinking").path("type").asText());
        assertEquals("low", request.path("reasoning_effort").asText());
        assertEquals(8192, request.path("max_tokens").asInt());
        assertTrue(request.path("stream_options").path("include_usage").asBoolean());
        assertEquals("keep", request.path("stream_options").path("vendor_flag").asText());
    }

    @Test
    public void waitsForUsageOnlyChunkAfterFinishReasonBeforeCompleting() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = ("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},"
                    + "\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":20,"
                    + "\"completion_tokens\":8,\"prompt_cache_hit_tokens\":5}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            writeSse(exchange, response);
        });
        server.start();

        List<String> content = Collections.synchronizedList(new ArrayList<>());
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<ChatUsage> usage = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        OpenAiCompatibleChatProvider provider = new OpenAiCompatibleChatProvider(
                "openai", baseUrl(), "test-key", new OkHttpClient());

        provider.stream(new ChatRequest("gpt-test", Collections.emptyList(), false),
                observer(content, done, error, completions, usage, events));

        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals(1, completions.get());
        assertEquals("answer", String.join("", content));
        assertEquals(20L, usage.get().getPromptTokens());
        assertEquals(8L, usage.get().getCompletionTokens());
        assertEquals(5L, usage.get().getCachedPromptTokens());
        assertTrue(events.indexOf("usage") < events.indexOf("complete"));
    }

    @Test
    public void retainsLatestCumulativeUsageAndSupportsBothCacheFieldShapes() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = ("data: {\"choices\":[{\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4,"
                    + "\"prompt_cache_hit_tokens\":3}}\n\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,"
                    + "\"completion_tokens\":6,\"prompt_tokens_details\":{"
                    + "\"cached_tokens\":4}}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            writeSse(exchange, response);
        });
        server.start();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<ChatUsage> usage = new AtomicReference<>();
        OpenAiCompatibleChatProvider provider = new OpenAiCompatibleChatProvider(
                "openai", baseUrl(), "test-key", new OkHttpClient());

        provider.stream(new ChatRequest("gpt-test", Collections.emptyList(), false),
                observer(new ArrayList<>(), done, error, new AtomicInteger(), usage));

        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals(12L, usage.get().getPromptTokens());
        assertEquals(6L, usage.get().getCompletionTokens());
        assertEquals(4L, usage.get().getCachedPromptTokens());
    }

    @Test
    public void rejectsMalformedUsageCounts() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = ("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":4,"
                    + "\"completion_tokens\":\"not-a-number\"}}\n\n").getBytes(StandardCharsets.UTF_8);
            writeSse(exchange, response);
        });
        server.start();

        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        OpenAiCompatibleChatProvider provider = new OpenAiCompatibleChatProvider(
                "openai", baseUrl(), "test-key", new OkHttpClient());

        provider.stream(new ChatRequest("gpt-test", Collections.emptyList(), false),
                observer(new ArrayList<>(), failed, error, completions));

        assertTrue(failed.await(3, TimeUnit.SECONDS));
        assertEquals("Provider returned malformed usage data", error.get());
        assertEquals(0, completions.get());
    }

    @Test
    public void treatsProviderErrorObjectAsStreamFailure() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "data: {\"error\":{\"message\":\"upstream failed\"}}\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            writeSse(exchange, response);
        });
        server.start();

        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        OpenAiCompatibleChatProvider provider = new OpenAiCompatibleChatProvider(
                "openai", baseUrl(), "test-key", new OkHttpClient());

        provider.stream(new ChatRequest("gpt-test", Collections.emptyList(), false),
                observer(new ArrayList<>(), failed, error, completions));

        assertTrue(failed.await(3, TimeUnit.SECONDS));
        assertEquals("Provider returned an error", error.get());
        assertEquals(0, completions.get());
    }

    @Test
    public void reportsPrematureCloseWhenFinishOrUsageIsMissing() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "data: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            writeSse(exchange, response);
        });
        server.start();

        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        OpenAiCompatibleChatProvider provider = new OpenAiCompatibleChatProvider(
                "openai", baseUrl(), "test-key", new OkHttpClient());

        provider.stream(new ChatRequest("gpt-test", Collections.emptyList(), false),
                observer(new ArrayList<>(), failed, error, completions));

        assertTrue(failed.await(3, TimeUnit.SECONDS));
        assertEquals("Provider stream ended before completion", error.get());
        assertEquals(0, completions.get());
    }

    @Test
    public void acceptsCloseAfterFinishAndValidUsageWhenDoneMarkerIsMissing() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = ("data: {\"choices\":[{\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            writeSse(exchange, response);
        });
        server.start();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        OpenAiCompatibleChatProvider provider = new OpenAiCompatibleChatProvider(
                "openai", baseUrl(), "test-key", new OkHttpClient());

        provider.stream(new ChatRequest("gpt-test", Collections.emptyList(), false),
                observer(new ArrayList<>(), done, error, completions));

        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals(1, completions.get());
    }

    @Test
    public void validatesChatUsageBounds() {
        ChatUsage usage = new ChatUsage(9, 4, 3);
        assertEquals(9L, usage.getPromptTokens());
        assertEquals(4L, usage.getCompletionTokens());
        assertEquals(3L, usage.getCachedPromptTokens());

        assertInvalidUsage(-1, 1, 0);
        assertInvalidUsage(1, -1, 0);
        assertInvalidUsage(1, 1, -1);
        assertInvalidUsage(1, 1, 2);
    }

    private static void assertInvalidUsage(long promptTokens, long completionTokens, long cachedPromptTokens) {
        try {
            new ChatUsage(promptTokens, completionTokens, cachedPromptTokens);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected invalid usage to be rejected");
    }

    private static void writeSse(com.sun.net.httpserver.HttpExchange exchange, byte[] response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private ChatObserver observer(List<String> content, CountDownLatch terminal,
                                  AtomicReference<String> error, AtomicInteger completions) {
        return observer(content, terminal, error, completions, new AtomicReference<>());
    }

    private ChatObserver observer(List<String> content, CountDownLatch terminal,
                                  AtomicReference<String> error, AtomicInteger completions,
                                  AtomicReference<ChatUsage> usage) {
        return observer(content, terminal, error, completions, usage, null);
    }

    private ChatObserver observer(List<String> content, CountDownLatch terminal,
                                  AtomicReference<String> error, AtomicInteger completions,
                                  AtomicReference<ChatUsage> usage, List<String> events) {
        return new ChatObserver() {
            @Override
            public void onContent(String value) {
                content.add(value);
                if (events != null) events.add("content");
            }

            @Override
            public void onUsage(ChatUsage value) {
                usage.set(value);
                if (events != null) events.add("usage");
            }

            @Override
            public void onComplete() {
                completions.incrementAndGet();
                if (events != null) events.add("complete");
                terminal.countDown();
            }

            @Override
            public void onError(String message) {
                error.set(message);
                if (events != null) events.add("error");
                terminal.countDown();
            }
        };
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
