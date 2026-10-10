package cn.bugstack.chatgpt.data.infrastructure.adapter.service;

import cn.bugstack.chatgpt.data.domain.agent.model.AgentBillingCall;
import cn.bugstack.chatgpt.data.domain.agent.model.AgentBillingRun;
import cn.bugstack.chatgpt.data.domain.agent.service.AgentBillingException;
import cn.bugstack.chatgpt.data.domain.agent.service.IAgentBillingService;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatBillingQuote;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatModel;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatUsage;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.UserAccountQuotaEntity;
import cn.bugstack.chatgpt.data.domain.openai.model.valobj.UserAccountStatusVO;
import cn.bugstack.chatgpt.data.domain.openai.repository.IOpenAiRepository;
import cn.bugstack.chatgpt.data.domain.openai.service.ChatProviderRegistry;
import cn.bugstack.chatgpt.data.infrastructure.dao.IAgentBillingDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.AgentCallChargePO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.AgentRunBudgetPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Wallet-backed billing for independent Agent runs. The run row is locked
 * before its wallet and call rows on every mutation, which serializes the cap
 * and avoids charging two concurrent calls past the same run budget.
 */
@Service
public class AgentBillingService implements IAgentBillingService {

    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(8);
    private static final BigInteger FIVE = BigInteger.valueOf(5L);
    private static final BigInteger SIX = BigInteger.valueOf(6L);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final int STALE_BATCH_SIZE = 100;

    @Resource
    private IAgentBillingDao billingDao;
    @Resource
    private IOpenAiRepository openAiRepository;
    @Resource
    private ChatProviderRegistry providers;
    @Resource
    private PlatformTransactionManager transactionManager;

    @Value("${agent.billing.max-budget:100.00000000}")
    private BigDecimal maxBudget;
    @Value("${agent.billing.lease-minutes:5}")
    private long leaseMinutes;

    @Override
    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.DEFAULT,
            timeout = 30
    )
    public AgentBillingRun createOrGetRun(String userId, String runId, BigDecimal budgetLimit) {
        requireId(userId, "userId", 64);
        requireId(runId, "runId", 96);
        BigDecimal budget = validateAmount(budgetLimit, "budgetLimit");
        if (maxBudget == null || maxBudget.signum() <= 0 || budget.compareTo(maxBudget) > 0) {
            throw error(AgentBillingException.Code.BAD_REQUEST, "budgetLimit exceeds configured maximum");
        }

        AgentRunBudgetPO existing = billingDao.findRunForUpdate(userId, runId);
        if (existing != null) {
            if (existing.getBudgetLimit().compareTo(budget) != 0) {
                throw error(AgentBillingException.Code.CONFLICT, "runId reused with a different budget");
            }
            return toRun(existing);
        }

        AgentRunBudgetPO run = AgentRunBudgetPO.builder()
                .userId(userId)
                .runId(runId)
                .status(AgentRunBudgetPO.ACTIVE)
                .budgetLimit(budget)
                .reservedAmount(ZERO)
                .settledAmount(ZERO)
                .reviewRequired(false)
                .build();
        // INSERT IGNORE makes a concurrent initializer harmless. The locked
        // read below is authoritative and also detects a different budget.
        billingDao.insertRun(run);
        AgentRunBudgetPO persisted = billingDao.findRunForUpdate(userId, runId);
        if (persisted == null) throw error(AgentBillingException.Code.UNAVAILABLE, "run could not be initialized");
        if (persisted.getBudgetLimit().compareTo(budget) != 0) {
            throw error(AgentBillingException.Code.CONFLICT, "runId reused with a different budget");
        }
        return toRun(persisted);
    }

    @Override
    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.DEFAULT,
            timeout = 30
    )
    public AgentBillingCall reserveCall(String userId, String runId, String callId, String model,
                                        long inputTokensEstimate, int maxOutputTokens, String purpose) {
        requireId(userId, "userId", 64);
        requireId(runId, "runId", 96);
        requireId(callId, "callId", 128);
        if (inputTokensEstimate < 0) throw error(AgentBillingException.Code.BAD_REQUEST, "input_tokens_estimate must be nonnegative");
        if (purpose == null || purpose.isBlank() || purpose.length() > 64) {
            throw error(AgentBillingException.Code.BAD_REQUEST, "purpose is required and bounded");
        }
        String requestedModel = requireId(model, "model", 64);
        String payloadHash = hashPayload(runId, callId, requestedModel, inputTokensEstimate, maxOutputTokens, purpose);

        AgentRunBudgetPO run = billingDao.findRunForUpdate(userId, runId);
        if (run == null) throw error(AgentBillingException.Code.NOT_FOUND, "run was not found");

        // All reserve paths lock the run first. The non-locking idempotency
        // read makes a retry return before touching the wallet. A missing
        // call is rechecked with a row lock after the wallet lock below;
        // this avoids holding an InnoDB gap lock while different runs for
        // one user contend for the same wallet row.
        AgentCallChargePO existing = billingDao.findCall(userId, callId);
        if (existing != null) {
            if (!runId.equals(existing.getRunId()) || !payloadHash.equals(existing.getPayloadHash())) {
                throw error(AgentBillingException.Code.CONFLICT, "callId reused with a different payload");
            }
            return toCall(existing);
        }
        if (Boolean.TRUE.equals(run.getReviewRequired())) {
            throw error(AgentBillingException.Code.CAP_REACHED, "run requires review");
        }

        ChatModel chatModel = resolveAuthorizedModel(userId, requestedModel);
        if (maxOutputTokens < 1 || maxOutputTokens > chatModel.getMaxOutputTokens()) {
            throw error(AgentBillingException.Code.BAD_REQUEST, "max_output_tokens exceeds model limit");
        }
        validateRates(chatModel);
        long paddedInputTokens = paddedInputTokens(inputTokensEstimate);
        BigDecimal used = amount(run.getReservedAmount()).add(amount(run.getSettledAmount()));
        BigDecimal remainingBudget = run.getBudgetLimit().subtract(used);

        // The locked wallet read serializes reservations for different runs
        // belonging to the same user. The conditional debit below remains a
        // defense against a wallet mutation that races outside this lock.
        BigDecimal walletAvailable = billingDao.findWalletAvailableForUpdate(userId);
        if (walletAvailable == null) {
            throw error(AgentBillingException.Code.INSUFFICIENT_FUNDS, "CNY wallet is unavailable");
        }
        existing = billingDao.findCallForUpdate(userId, callId);
        if (existing != null) {
            if (!runId.equals(existing.getRunId()) || !payloadHash.equals(existing.getPayloadHash())) {
                throw error(AgentBillingException.Code.CONFLICT, "callId reused with a different payload");
            }
            return toCall(existing);
        }
        ReservationPlan plan = planReservation(remainingBudget, walletAvailable, paddedInputTokens,
                maxOutputTokens, chatModel.getMaxOutputTokens(), chatModel.getInputPrice(),
                chatModel.getCachedInputPrice(), chatModel.getOutputPrice());
        BigDecimal hold = plan.hold();
        AgentCallChargePO call = AgentCallChargePO.builder()
                .userId(userId)
                .runId(runId)
                .callId(callId)
                .payloadHash(payloadHash)
                .purpose(purpose)
                .model(chatModel.getId())
                .inputPrice(chatModel.getInputPrice())
                .cachedInputPrice(chatModel.getCachedInputPrice())
                .outputPrice(chatModel.getOutputPrice())
                .maxOutputTokens(plan.effectiveOutputTokens())
                .reservedAmount(hold)
                .settledAmount(ZERO)
                .observedAmount(null)
                .observedExcessAmount(ZERO)
                .status(AgentCallChargePO.RESERVED)
                .reviewRequired(false)
                .leaseUntil(leaseUntil())
                .build();
        int inserted;
        try {
            // The call insert happens before the wallet debit. A duplicate
            // therefore has no debit to undo, even when another run owns the
            // same user-scoped call_id and commits while this transaction is
            // waiting on the unique index.
            inserted = billingDao.insertCall(call);
        } catch (DuplicateKeyException duplicate) {
            AgentCallChargePO raced = billingDao.findCallForUpdate(userId, callId);
            if (raced == null || !runId.equals(raced.getRunId()) || !payloadHash.equals(raced.getPayloadHash())) {
                throw error(AgentBillingException.Code.CONFLICT, "callId is already in use");
            }
            return toCall(raced);
        }
        if (inserted != 1) throw error(AgentBillingException.Code.UNAVAILABLE, "call reservation insert failed");
        if (billingDao.debitWallet(userId, hold) != 1) {
            throw error(AgentBillingException.Code.INSUFFICIENT_FUNDS, "wallet balance is insufficient");
        }

        run.setReservedAmount(used(run.getReservedAmount()).add(hold));
        run.setStatus(run.getReservedAmount().add(amount(run.getSettledAmount())).compareTo(run.getBudgetLimit()) >= 0
                ? AgentRunBudgetPO.CAP_REACHED : AgentRunBudgetPO.ACTIVE);
        if (billingDao.updateRun(run) != 1) throw error(AgentBillingException.Code.UNAVAILABLE, "run budget update failed");
        return toCall(call);
    }

    @Override
    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.DEFAULT,
            timeout = 30
    )
    public AgentBillingCall settleCall(String userId, String callId, long promptTokens,
                                       long completionTokens, long cachedPromptTokens) {
        requireId(userId, "userId", 64);
        requireId(callId, "callId", 128);
        ChatUsage usage = usage(promptTokens, completionTokens, cachedPromptTokens);
        AgentCallChargePO probe = billingDao.findCall(userId, callId);
        if (probe == null) throw error(AgentBillingException.Code.NOT_FOUND, "call was not found");
        AgentRunBudgetPO run = billingDao.findRunForUpdate(userId, probe.getRunId());
        if (run == null) throw error(AgentBillingException.Code.UNAVAILABLE, "run budget was not found");
        billingDao.findWalletAvailableForUpdate(userId);
        AgentCallChargePO call = billingDao.findCallForUpdate(userId, callId);
        if (call == null) throw error(AgentBillingException.Code.NOT_FOUND, "call was not found");
        if (call.getStatus() != AgentCallChargePO.RESERVED) {
            if (call.getStatus() == AgentCallChargePO.SETTLED
                    && Objects.equals(call.getPromptTokens(), usage.getPromptTokens())
                    && Objects.equals(call.getCompletionTokens(), usage.getCompletionTokens())
                    && Objects.equals(call.getCachedPromptTokens(), usage.getCachedPromptTokens())) {
                return toCall(call);
            }
            throw error(AgentBillingException.Code.CONFLICT, "call has already been finalized");
        }

        BigDecimal observed = ChatBillingQuote.cost(usage.getPromptTokens(), usage.getCompletionTokens(),
                usage.getCachedPromptTokens(), call.getInputPrice(), call.getCachedInputPrice(), call.getOutputPrice());
        BigDecimal authorized = amount(call.getReservedAmount());
        BigDecimal settled = observed.min(authorized);
        BigDecimal refund = authorized.subtract(settled);
        BigDecimal excess = observed.subtract(authorized).max(ZERO);
        if (refund.signum() > 0 && billingDao.refundWallet(userId, refund) != 1) {
            throw error(AgentBillingException.Code.UNAVAILABLE, "wallet refund failed");
        }

        call.setPromptTokens(usage.getPromptTokens());
        call.setCompletionTokens(usage.getCompletionTokens());
        call.setCachedPromptTokens(usage.getCachedPromptTokens());
        call.setObservedAmount(observed);
        call.setObservedExcessAmount(excess);
        call.setSettledAmount(settled);
        call.setStatus(AgentCallChargePO.SETTLED);
        call.setReviewRequired(excess.signum() > 0);
        call.setLeaseUntil(null);
        if (billingDao.updateCall(call) != 1) throw error(AgentBillingException.Code.UNAVAILABLE, "call settlement failed");

        run.setReservedAmount(amount(run.getReservedAmount()).subtract(authorized).max(ZERO));
        run.setSettledAmount(amount(run.getSettledAmount()).add(settled));
        run.setReviewRequired(Boolean.TRUE.equals(run.getReviewRequired()) || excess.signum() > 0);
        run.setStatus(run.getReviewRequired() ? AgentRunBudgetPO.REVIEW
                : run.getReservedAmount().add(run.getSettledAmount()).compareTo(run.getBudgetLimit()) >= 0
                ? AgentRunBudgetPO.CAP_REACHED : AgentRunBudgetPO.ACTIVE);
        if (billingDao.updateRun(run) != 1) throw error(AgentBillingException.Code.UNAVAILABLE, "run settlement failed");
        return toCall(call);
    }

    @Override
    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.DEFAULT,
            timeout = 30
    )
    public AgentBillingCall releaseCall(String userId, String callId, String reason) {
        requireId(userId, "userId", 64);
        requireId(callId, "callId", 128);
        String releaseReason = reason == null || reason.isBlank() ? "unknown_usage" : reason.trim();
        if (releaseReason.length() > 64) throw error(AgentBillingException.Code.BAD_REQUEST, "reason is too long");
        AgentCallChargePO probe = billingDao.findCall(userId, callId);
        if (probe == null) throw error(AgentBillingException.Code.NOT_FOUND, "call was not found");
        AgentRunBudgetPO run = billingDao.findRunForUpdate(userId, probe.getRunId());
        if (run == null) throw error(AgentBillingException.Code.UNAVAILABLE, "run budget was not found");
        billingDao.findWalletAvailableForUpdate(userId);
        AgentCallChargePO call = billingDao.findCallForUpdate(userId, callId);
        if (call == null) throw error(AgentBillingException.Code.NOT_FOUND, "call was not found");
        if (call.getStatus() != AgentCallChargePO.RESERVED) return toCall(call);

        BigDecimal held = amount(call.getReservedAmount());
        if (held.signum() > 0 && billingDao.refundWallet(userId, held) != 1) {
            throw error(AgentBillingException.Code.UNAVAILABLE, "wallet refund failed");
        }
        call.setStatus(AgentCallChargePO.RELEASED);
        call.setReleaseReason(releaseReason);
        call.setLeaseUntil(null);
        if (billingDao.updateCall(call) != 1) throw error(AgentBillingException.Code.UNAVAILABLE, "call release failed");
        run.setReservedAmount(amount(run.getReservedAmount()).subtract(held).max(ZERO));
        run.setStatus(Boolean.TRUE.equals(run.getReviewRequired()) ? AgentRunBudgetPO.REVIEW
                : run.getReservedAmount().add(run.getSettledAmount()).compareTo(run.getBudgetLimit()) >= 0
                ? AgentRunBudgetPO.CAP_REACHED : AgentRunBudgetPO.ACTIVE);
        if (billingDao.updateRun(run) != 1) throw error(AgentBillingException.Code.UNAVAILABLE, "run release failed");
        return toCall(call);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public AgentBillingRun queryRun(String userId, String runId) {
        requireId(userId, "userId", 64);
        requireId(runId, "runId", 96);
        AgentRunBudgetPO run = billingDao.findRun(userId, runId);
        if (run == null) throw error(AgentBillingException.Code.NOT_FOUND, "run was not found");
        return toRun(run);
    }

    @Override
    public int releaseStaleCalls() {
        int released = 0;
        Date now = new Date();
        List<AgentCallChargePO> stale = billingDao.findStaleCalls(now, STALE_BATCH_SIZE);
        TransactionTemplate transaction = recoveryTransactionTemplate();
        for (AgentCallChargePO candidate : stale) {
            Boolean recovered = transaction.execute(status -> releaseStaleCall(candidate, now));
            if (Boolean.TRUE.equals(recovered)) released++;
        }
        return released;
    }

    private TransactionTemplate recoveryTransactionTemplate() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        transaction.setTimeout(30);
        return transaction;
    }

    private Boolean releaseStaleCall(AgentCallChargePO candidate, Date now) {
        AgentRunBudgetPO run = billingDao.findRunForUpdate(candidate.getUserId(), candidate.getRunId());
        if (run == null) return false;
        billingDao.findWalletAvailableForUpdate(candidate.getUserId());
        AgentCallChargePO call = billingDao.findCallForUpdate(candidate.getUserId(), candidate.getCallId());
        if (call == null || call.getStatus() != AgentCallChargePO.RESERVED) return false;
        // The candidate query is intentionally unlocked. Recheck the lease
        // after acquiring the normal run -> wallet -> call lock order so a
        // renewed or concurrently finalized call is never refunded.
        if (call.getLeaseUntil() == null || !call.getLeaseUntil().before(now)) return false;
        BigDecimal held = amount(call.getReservedAmount());
        if (held.signum() > 0 && billingDao.refundWallet(call.getUserId(), held) != 1) {
            throw error(AgentBillingException.Code.UNAVAILABLE, "wallet refund failed");
        }
        call.setStatus(AgentCallChargePO.RELEASED);
        call.setReleaseReason("stale_recovery");
        call.setLeaseUntil(null);
        if (billingDao.updateCall(call) != 1) throw error(AgentBillingException.Code.UNAVAILABLE, "stale call release failed");
        run.setReservedAmount(amount(run.getReservedAmount()).subtract(held).max(ZERO));
        run.setStatus(Boolean.TRUE.equals(run.getReviewRequired()) ? AgentRunBudgetPO.REVIEW
                : run.getReservedAmount().add(run.getSettledAmount()).compareTo(run.getBudgetLimit()) >= 0
                ? AgentRunBudgetPO.CAP_REACHED : AgentRunBudgetPO.ACTIVE);
        if (billingDao.updateRun(run) != 1) throw error(AgentBillingException.Code.UNAVAILABLE, "stale run update failed");
        return true;
    }

    private static ReservationPlan planReservation(BigDecimal remainingBudget,
                                                   BigDecimal walletAvailable,
                                                   long paddedInputTokens,
                                                   int requestedOutputTokens,
                                                   int catalogMaxOutputTokens,
                                                   BigDecimal inputPrice,
                                                   BigDecimal cachedInputPrice,
                                                   BigDecimal outputPrice) {
        if (remainingBudget.compareTo(ZERO) < 0) {
            throw error(AgentBillingException.Code.CAP_REACHED, "run budget cap reached");
        }
        if (walletAvailable.compareTo(ZERO) < 0) {
            throw error(AgentBillingException.Code.INSUFFICIENT_FUNDS, "wallet balance is insufficient");
        }

        BigDecimal cap = remainingBudget.min(walletAvailable);
        BigDecimal minimumHold = ChatBillingQuote.cost(paddedInputTokens, 1, 0,
                inputPrice, cachedInputPrice, outputPrice);
        if (minimumHold.compareTo(cap) > 0) {
            throw insufficientForMinimum(remainingBudget, walletAvailable, minimumHold);
        }

        int effectiveOutputTokens = Math.min(requestedOutputTokens, catalogMaxOutputTokens);
        if (outputPrice.signum() > 0) {
            BigDecimal outputBudget = cap.multiply(MILLION)
                    .subtract(BigDecimal.valueOf(paddedInputTokens).multiply(inputPrice));
            BigDecimal affordableOutput = outputBudget.signum() < 0
                    ? ZERO
                    : outputBudget.divide(outputPrice, 0, RoundingMode.FLOOR);
            if (affordableOutput.compareTo(BigDecimal.valueOf(effectiveOutputTokens)) < 0) {
                effectiveOutputTokens = affordableOutput.signum() <= 0
                        ? 0 : affordableOutput.intValue();
            }
        }

        // ChatBillingQuote rounds upward to eight decimal places. The raw
        // floor above can therefore be one or more tokens too high when a
        // tariff is very small; trim until the persisted hold is guaranteed
        // to be no greater than the locked cap. Binary search keeps this
        // bounded even if a catalog temporarily exposes a very large max.
        BigDecimal hold = effectiveOutputTokens < 1
                ? ZERO
                : ChatBillingQuote.cost(paddedInputTokens, effectiveOutputTokens, 0,
                inputPrice, cachedInputPrice, outputPrice);
        if (effectiveOutputTokens > 0 && hold.compareTo(cap) > 0) {
            effectiveOutputTokens = maxOutputWithinCap(effectiveOutputTokens, cap, paddedInputTokens,
                    inputPrice, cachedInputPrice, outputPrice);
            hold = ChatBillingQuote.cost(paddedInputTokens, effectiveOutputTokens, 0,
                    inputPrice, cachedInputPrice, outputPrice);
        }
        if (effectiveOutputTokens < 1) {
            throw insufficientForMinimum(remainingBudget, walletAvailable, minimumHold);
        }
        return new ReservationPlan(effectiveOutputTokens, hold);
    }

    private static int maxOutputWithinCap(int upperBound,
                                          BigDecimal cap,
                                          long paddedInputTokens,
                                          BigDecimal inputPrice,
                                          BigDecimal cachedInputPrice,
                                          BigDecimal outputPrice) {
        int low = 0;
        int high = upperBound;
        while (low < high) {
            int middle = (int) ((low + (long) high + 1L) / 2L);
            BigDecimal hold = ChatBillingQuote.cost(paddedInputTokens, middle, 0,
                    inputPrice, cachedInputPrice, outputPrice);
            if (hold.compareTo(cap) <= 0) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    private static AgentBillingException insufficientForMinimum(BigDecimal remainingBudget,
                                                                BigDecimal walletAvailable,
                                                                BigDecimal minimumHold) {
        // Budget is the first limiting authority. If both sources are short,
        // report CAP_REACHED so a caller does not mistake a run-cap failure
        // for a transient wallet top-up race.
        if (remainingBudget.compareTo(minimumHold) < 0) {
            return error(AgentBillingException.Code.CAP_REACHED, "run budget cap reached");
        }
        if (walletAvailable.compareTo(minimumHold) < 0) {
            return error(AgentBillingException.Code.INSUFFICIENT_FUNDS, "wallet balance is insufficient");
        }
        return error(AgentBillingException.Code.CAP_REACHED, "run budget cap reached");
    }

    private static long paddedInputTokens(long inputTokensEstimate) {
        // The 20 percent safety margin is ceil(estimate * 1.20), exactly
        // floor((estimate * 6 + 4) / 5) for a nonnegative integer estimate.
        // BigInteger keeps the multiplication from overflowing long; the
        // quote and database fields still require the final value to fit.
        BigInteger padded = BigInteger.valueOf(inputTokensEstimate).multiply(SIX).add(BigInteger.valueOf(4L))
                .divide(FIVE);
        if (padded.compareTo(LONG_MAX) > 0) {
            throw error(AgentBillingException.Code.BAD_REQUEST, "input_tokens_estimate is too large");
        }
        return padded.longValue();
    }

    private static void validateRates(ChatModel model) {
        validateRate(model.getInputPrice(), "input_price");
        validateRate(model.getCachedInputPrice(), "cached_input_price");
        validateRate(model.getOutputPrice(), "output_price");
        if (model.getCachedInputPrice().compareTo(model.getInputPrice()) > 0) {
            throw error(AgentBillingException.Code.BAD_REQUEST, "cached_input_price exceeds input_price");
        }
    }

    private static void validateRate(BigDecimal value, String field) {
        if (value == null || value.signum() < 0 || value.scale() > 8
                || value.compareTo(new BigDecimal("1000000")) > 0) {
            throw error(AgentBillingException.Code.BAD_REQUEST, field + " is invalid");
        }
    }

    private record ReservationPlan(int effectiveOutputTokens, BigDecimal hold) {
    }

    private ChatModel resolveAuthorizedModel(String userId, String requestedModel) {
        ChatModel model;
        try {
            model = providers.model(requestedModel);
        } catch (IllegalArgumentException invalid) {
            throw error(AgentBillingException.Code.BAD_REQUEST, "unsupported model");
        }
        UserAccountQuotaEntity account = openAiRepository.queryUserAccount(userId);
        if (account == null || !UserAccountStatusVO.AVAILABLE.equals(account.getUserAccountStatusVO())) {
            throw error(AgentBillingException.Code.FORBIDDEN, "account cannot use Agent models");
        }
        List<String> allowed = account.getAllowModelTypeList();
        if (allowed == null || !allowed.contains(model.getId())) {
            throw error(AgentBillingException.Code.FORBIDDEN, "model is not enabled for this account");
        }
        return model;
    }

    private Date leaseUntil() {
        // Keep stale recovery shorter than the Agent provider request window.
        // A deployment may lower this value, but cannot accidentally extend
        // it beyond the five minute recovery bound.
        long minutes = Math.max(1L, Math.min(5L, leaseMinutes));
        return new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes));
    }

    private static ChatUsage usage(long prompt, long completion, long cached) {
        try {
            return new ChatUsage(prompt, completion, cached);
        } catch (IllegalArgumentException invalid) {
            throw error(AgentBillingException.Code.BAD_REQUEST, "invalid token usage");
        }
    }

    private static BigDecimal validateAmount(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0 || value.scale() > 8) {
            throw error(AgentBillingException.Code.BAD_REQUEST, field + " must be a positive decimal with at most 8 places");
        }
        return value.setScale(8);
    }

    private static String requireId(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw error(AgentBillingException.Code.BAD_REQUEST, field + " is required and bounded");
        }
        return value;
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static BigDecimal used(BigDecimal value) {
        return amount(value);
    }

    private static String hashPayload(String runId, String callId, String model,
                                      long inputTokens, int maxOutputTokens, String purpose) {
        String payload = runId.length() + ":" + runId + "|" + callId.length() + ":" + callId
                + "|" + model.length() + ":" + model + "|" + inputTokens + "|"
                + maxOutputTokens + "|" + purpose.length() + ":" + purpose;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static AgentBillingException error(AgentBillingException.Code code, String message) {
        return new AgentBillingException(code, message);
    }

    private static AgentBillingRun toRun(AgentRunBudgetPO run) {
        BigDecimal reserved = amount(run.getReservedAmount());
        BigDecimal settled = amount(run.getSettledAmount());
        String status;
        if (Boolean.TRUE.equals(run.getReviewRequired())) status = "review";
        else if (reserved.add(settled).compareTo(run.getBudgetLimit()) >= 0) status = "cap_reached";
        else status = "active";
        return new AgentBillingRun(run.getRunId(), status, run.getBudgetLimit(), reserved, settled,
                Boolean.TRUE.equals(run.getReviewRequired()));
    }

    private static AgentBillingCall toCall(AgentCallChargePO call) {
        String status;
        if (call.getStatus() != null && call.getStatus() == AgentCallChargePO.SETTLED) status = "settled";
        else if (call.getStatus() != null && call.getStatus() == AgentCallChargePO.RELEASED) status = "released";
        else status = "reserved";
        return new AgentBillingCall(call.getCallId(), call.getRunId(), status,
                amount(call.getReservedAmount()), amount(call.getSettledAmount()),
                call.getObservedAmount() == null ? ZERO : call.getObservedAmount(),
                amount(call.getObservedExcessAmount()),
                call.getMaxOutputTokens() == null ? 0 : call.getMaxOutputTokens(),
                Boolean.TRUE.equals(call.getReviewRequired()));
    }
}
