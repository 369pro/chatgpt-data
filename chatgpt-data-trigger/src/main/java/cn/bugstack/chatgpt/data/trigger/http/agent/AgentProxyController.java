package cn.bugstack.chatgpt.data.trigger.http.agent;

import cn.bugstack.chatgpt.data.domain.auth.service.IAuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Set;

/**
 * Public authenticated bridge to the independent Agent service. Browser
 * identity headers are never forwarded; the Java auth session is the only
 * source of the downstream user identity.
 */
@RestController
@CrossOrigin("${app.config.cross-origin}")
@RequestMapping("/api/${app.config.api-version:v1}/agent")
public class AgentProxyController {

    private static final Set<String> STRIPPED_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "x-user-id", "x-agent-key",
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-real-ip",
            "host", "content-length", "connection", "transfer-encoding"
    );

    private final RestTemplate restTemplate;
    private final IAuthService authService;
    private final String agentServiceUrl;
    private final String internalKey;
    private final String apiVersion;

    public AgentProxyController(IAuthService authService,
                                @Value("${agent.service.url:${AGENT_SERVICE_URL:http://agent-service:8093}}") String agentServiceUrl,
                                @Value("${AGENT_INTERNAL_KEY:}") String internalKey,
                                @Value("${app.config.api-version:v1}") String apiVersion,
                                @Value("${agent.proxy.connect-timeout-ms:2000}") long connectTimeoutMs,
                                @Value("${agent.proxy.read-timeout-ms:120000}") long readTimeoutMs) {
        this.authService = authService;
        this.agentServiceUrl = trimTrailingSlash(agentServiceUrl);
        this.internalKey = internalKey == null ? "" : internalKey;
        this.apiVersion = apiVersion;
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(timeoutMillis(readTimeoutMs));
        RestTemplate template = new RestTemplate(requestFactory);
        template.setErrorHandler(new NoOpResponseErrorHandler());
        this.restTemplate = template;
    }

    @RequestMapping(value = {"", "/", "/**"}, method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.HEAD
    })
    public void proxy(HttpServletRequest request,
                      HttpServletResponse response,
                      @RequestHeader(value = "Authorization", required = false) String token) throws IOException {
        if (token == null || token.isBlank() || !authService.checkToken(stripBearer(token))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
            return;
        }
        if (internalKey.isBlank()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "agent service is not configured");
            return;
        }
        String userId;
        try {
            userId = authService.openid(stripBearer(token));
        } catch (RuntimeException invalidToken) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
            return;
        }

        HttpMethod method = HttpMethod.resolve(request.getMethod());
        if (method == null) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        URI target = target(request);
        HttpHeaders headers = forwardedHeaders(request);
        headers.set("X-Agent-Key", internalKey);
        headers.set("X-User-Id", userId);
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        try {
            ResponseEntity<byte[]> upstream = restTemplate.exchange(target, method,
                    new HttpEntity<>(body, headers), byte[].class);
            response.setStatus(upstream.getStatusCodeValue());
            MediaType contentType = upstream.getHeaders().getContentType();
            if (contentType != null) response.setContentType(contentType.toString());
            String cacheControl = upstream.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
            if (cacheControl != null) response.setHeader(HttpHeaders.CACHE_CONTROL, cacheControl);
            byte[] payload = upstream.getBody();
            if (payload != null && payload.length > 0) response.getOutputStream().write(payload);
        } catch (RestClientException unavailable) {
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "agent service unavailable");
        }
    }

    private URI target(HttpServletRequest request) {
        String publicPrefix = "/api/" + apiVersion + "/agent";
        String path = request.getRequestURI();
        String suffix = path.startsWith(publicPrefix) ? path.substring(publicPrefix.length()) : "/";
        if (suffix.isEmpty()) suffix = "/";
        String query = request.getQueryString();
        return URI.create(agentServiceUrl + "/v1" + suffix + (query == null ? "" : "?" + query));
    }

    private static HttpHeaders forwardedHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (STRIPPED_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) continue;
                Enumeration<String> values = request.getHeaders(name);
                while (values.hasMoreElements()) headers.add(name, values.nextElement());
            }
        }
        String contentType = request.getContentType();
        if (contentType != null) headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        return headers;
    }

    private static String stripBearer(String token) {
        String trimmed = token.trim();
        return trimmed.regionMatches(true, 0, "Bearer ", 0, 7) ? trimmed.substring(7).trim() : trimmed;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "http://agent-service:8093";
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static int timeoutMillis(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, value));
    }

    private static final class NoOpResponseErrorHandler implements ResponseErrorHandler {
        @Override
        public boolean hasError(ClientHttpResponse response) {
            return false;
        }

        @Override
        public void handleError(ClientHttpResponse response) {
            // The proxy must preserve upstream HTTP status and JSON body.
        }
    }
}
