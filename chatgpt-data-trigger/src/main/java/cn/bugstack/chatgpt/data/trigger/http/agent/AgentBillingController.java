package cn.bugstack.chatgpt.data.trigger.http.agent;

import cn.bugstack.chatgpt.data.domain.agent.model.AgentBillingCall;
import cn.bugstack.chatgpt.data.domain.agent.model.AgentBillingRun;
import cn.bugstack.chatgpt.data.domain.agent.service.AgentBillingException;
import cn.bugstack.chatgpt.data.domain.agent.service.IAgentBillingService;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatModel;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.UserAccountQuotaEntity;
import cn.bugstack.chatgpt.data.domain.openai.model.valobj.UserAccountStatusVO;
import cn.bugstack.chatgpt.data.domain.openai.repository.IOpenAiRepository;
import cn.bugstack.chatgpt.data.domain.openai.service.ChatProviderRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Private HTTP contract consumed by the independent Agent service. */
@RestController
@RequestMapping("/api/${app.config.api-version:v1}/internal/agent")
public class AgentBillingController {

    private final IAgentBillingService billingService;
    private final AgentInternalRequestGuard guard;
    private final ChatProviderRegistry providers;
    private final IOpenAiRepository openAiRepository;

    public AgentBillingController(IAgentBillingService billingService,
                                  AgentInternalRequestGuard guard,
                                  ChatProviderRegistry providers,
                                  IOpenAiRepository openAiRepository) {
        this.billingService = billingService;
        this.guard = guard;
        this.providers = providers;
        this.openAiRepository = openAiRepository;
    }

    @GetMapping("/models")
    public ResponseEntity<?> models(HttpServletRequest request) {
        try {
            String userId = guard.userId(request);
            UserAccountQuotaEntity account = openAiRepository.queryUserAccount(userId);
            if (account == null || !UserAccountStatusVO.AVAILABLE.equals(account.getUserAccountStatusVO())) {
                throw new AgentBillingException(AgentBillingException.Code.FORBIDDEN,
                        "account cannot use Agent models");
            }
            List<String> allowed = account.getAllowModelTypeList();
            List<Map<String, Object>> models = new ArrayList<>();
            for (ChatModel model : providers.models()) {
                if (allowed == null || !allowed.contains(model.getId())) continue;
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", model.getId());
                value.put("provider", model.getProvider());
                value.put("upstream_model", model.getUpstreamModel());
                value.put("input_price", money(model.getInputPrice()));
                value.put("cached_input_price", money(model.getCachedInputPrice()));
                value.put("output_price", money(model.getOutputPrice()));
                value.put("max_output_tokens", model.getMaxOutputTokens());
                models.add(value);
            }
            return ResponseEntity.ok(Map.of("models", models));
        } catch (RuntimeException error) {
            return errorResponse(error);
        }
    }

    @PostMapping("/billing/runs")
    public ResponseEntity<?> createRun(@RequestBody AgentBillingRequestDTO.RunRequest request,
                                       HttpServletRequest servletRequest) {
        try {
            String userId = guard.userId(servletRequest);
            return ResponseEntity.ok(runBody(billingService.createOrGetRun(userId,
                    request == null ? null : request.getRunId(),
                    request == null ? null : request.getBudgetLimit())));
        } catch (RuntimeException error) {
            return errorResponse(error);
        }
    }

    @PostMapping("/billing/reserve")
    public ResponseEntity<?> reserve(@RequestBody AgentBillingRequestDTO.ReserveRequest request,
                                     HttpServletRequest servletRequest) {
        try {
            String userId = guard.userId(servletRequest);
            AgentBillingCall result = billingService.reserveCall(userId,
                    request == null ? null : request.getRunId(),
                    request == null ? null : request.getCallId(),
                    request == null ? null : request.getModel(),
                    request == null || request.getInputTokensEstimate() == null ? -1 : request.getInputTokensEstimate(),
                    request == null || request.getMaxOutputTokens() == null ? -1 : request.getMaxOutputTokens(),
                    request == null ? null : request.getPurpose());
            return ResponseEntity.ok(callBody(result));
        } catch (RuntimeException error) {
            return errorResponse(error);
        }
    }

    @PostMapping("/billing/settle")
    public ResponseEntity<?> settle(@RequestBody AgentBillingRequestDTO.SettleRequest request,
                                    HttpServletRequest servletRequest) {
        try {
            String userId = guard.userId(servletRequest);
            AgentBillingCall result = billingService.settleCall(userId,
                    request == null ? null : request.getCallId(),
                    request == null || request.getPromptTokens() == null ? -1 : request.getPromptTokens(),
                    request == null || request.getCompletionTokens() == null ? -1 : request.getCompletionTokens(),
                    request == null || request.getCachedPromptTokens() == null ? -1 : request.getCachedPromptTokens());
            return ResponseEntity.ok(callBody(result));
        } catch (RuntimeException error) {
            return errorResponse(error);
        }
    }

    @PostMapping("/billing/release")
    public ResponseEntity<?> release(@RequestBody AgentBillingRequestDTO.ReleaseRequest request,
                                     HttpServletRequest servletRequest) {
        try {
            String userId = guard.userId(servletRequest);
            AgentBillingCall result = billingService.releaseCall(userId,
                    request == null ? null : request.getCallId(),
                    request == null ? null : request.getReason());
            return ResponseEntity.ok(callBody(result));
        } catch (RuntimeException error) {
            return errorResponse(error);
        }
    }

    @GetMapping("/billing/runs/{runId}")
    public ResponseEntity<?> run(@PathVariable String runId, HttpServletRequest servletRequest) {
        try {
            String userId = guard.userId(servletRequest);
            return ResponseEntity.ok(runBody(billingService.queryRun(userId, runId)));
        } catch (RuntimeException error) {
            return errorResponse(error);
        }
    }

    private static Map<String, Object> callBody(AgentBillingCall call) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("call_id", call.getCallId());
        body.put("run_id", call.getRunId());
        body.put("status", call.getStatus());
        body.put("reserved_amount", money(call.getReservedAmount()));
        body.put("settled_amount", money(call.getSettledAmount()));
        body.put("observed_amount", money(call.getObservedAmount()));
        body.put("observed_excess_amount", money(call.getObservedExcessAmount()));
        body.put("max_output_tokens", call.getMaxOutputTokens());
        body.put("review_required", call.isReviewRequired());
        return body;
    }

    private static Map<String, Object> runBody(AgentBillingRun run) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", run.getRunId());
        body.put("status", run.getStatus());
        body.put("budget_limit", money(run.getBudgetLimit()));
        body.put("reserved_amount", money(run.getReservedAmount()));
        body.put("settled_amount", money(run.getSettledAmount()));
        body.put("review_required", run.isReviewRequired());
        return body;
    }

    private static String money(BigDecimal value) {
        return value == null ? "0.00000000" : value.setScale(8).toPlainString();
    }

    private static ResponseEntity<Map<String, Object>> errorResponse(RuntimeException error) {
        int status = HttpStatus.INTERNAL_SERVER_ERROR.value();
        String code = "internal_error";
        String message = "agent billing unavailable";
        if (error instanceof AgentInternalRequestGuard.AgentHttpException internal) {
            status = internal.getStatus();
            code = status == 401 ? "unauthorized" : "bad_request";
            message = internal.getMessage();
        } else if (error instanceof AgentBillingException billing) {
            code = billing.getCode().name().toLowerCase();
            message = billing.getMessage();
            status = switch (billing.getCode()) {
                case BAD_REQUEST -> 400;
                case UNAUTHORIZED -> 401;
                case FORBIDDEN -> 403;
                case NOT_FOUND -> 404;
                case CONFLICT, CAP_REACHED -> 409;
                case INSUFFICIENT_FUNDS -> 402;
                case UNAVAILABLE -> 503;
            };
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
