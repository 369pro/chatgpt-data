package cn.bugstack.chatgpt.data.test.agent;

import cn.bugstack.chatgpt.data.domain.agent.model.AgentBillingCall;
import cn.bugstack.chatgpt.data.domain.agent.model.AgentBillingRun;
import cn.bugstack.chatgpt.data.domain.agent.service.AgentBillingException;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatModel;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.UserAccountQuotaEntity;
import cn.bugstack.chatgpt.data.domain.openai.model.valobj.UserAccountStatusVO;
import cn.bugstack.chatgpt.data.domain.openai.repository.IOpenAiRepository;
import cn.bugstack.chatgpt.data.domain.openai.service.ChatProviderRegistry;
import cn.bugstack.chatgpt.data.infrastructure.adapter.service.AgentBillingService;
import cn.bugstack.chatgpt.data.infrastructure.dao.IAgentBillingDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.AgentCallChargePO;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.AgentRunBudgetPO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Focused invariants for the wallet/cap state machine. */
@RunWith(MockitoJUnitRunner.class)
public class AgentBillingServiceTest {

    @Mock
    private IAgentBillingDao billingDao;
    @Mock
    private IOpenAiRepository openAiRepository;
    @Mock
    private ChatProviderRegistry providers;
    @Mock
    private PlatformTransactionManager transactionManager;

    private AgentBillingService service;
    private ChatModel model;

    @Before
    public void setUp() {
        service = new AgentBillingService();
        ReflectionTestUtils.setField(service, "billingDao", billingDao);
        ReflectionTestUtils.setField(service, "openAiRepository", openAiRepository);
        ReflectionTestUtils.setField(service, "providers", providers);
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
        ReflectionTestUtils.setField(service, "maxBudget", new BigDecimal("10.00000000"));
        ReflectionTestUtils.setField(service, "leaseMinutes", 5L);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        model = new ChatModel();
        model.setId("gpt-test");
        model.setDisplayName("Test model");
        model.setProvider("test");
        model.setUpstreamModel("upstream-test");
        model.setInputPrice(new BigDecimal("1.00000000"));
        model.setCachedInputPrice(new BigDecimal("0.50000000"));
        model.setOutputPrice(new BigDecimal("1.00000000"));
        model.setMaxOutputTokens(4096);
        when(providers.model("gpt-test")).thenReturn(model);
        when(openAiRepository.queryUserAccount("user-1"))
                .thenReturn(UserAccountQuotaEntity.builder()
                        .openid("user-1")
                        .userAccountStatusVO(UserAccountStatusVO.AVAILABLE)
                        .allowModelTypeList(List.of("gpt-test"))
                        .build());
        when(billingDao.findWalletAvailableForUpdate(anyString()))
                .thenReturn(new BigDecimal("10.00000000"));
    }

    @Test
    public void reserveRetryReturnsTheOriginalHoldWithoutAnotherWalletDebit() {
        AgentRunBudgetPO run = run("run-1", "0.00022000", "0.00000000", "0.00000000");
        when(billingDao.findRunForUpdate("user-1", "run-1")).thenReturn(run);

        AgentCallChargePO[] persistedCall = new AgentCallChargePO[1];
        when(billingDao.findCall("user-1", "call-1"))
                .thenAnswer(invocation -> persistedCall[0]);
        when(billingDao.findCallForUpdate("user-1", "call-1"))
                .thenAnswer(invocation -> persistedCall[0]);
        doAnswer(invocation -> {
            persistedCall[0] = invocation.getArgument(0);
            return 1;
        }).when(billingDao).insertCall(any(AgentCallChargePO.class));
        when(billingDao.debitWallet(anyString(), any(BigDecimal.class))).thenReturn(1);
        when(billingDao.updateRun(any(AgentRunBudgetPO.class))).thenReturn(1);

        AgentBillingCall first = service.reserveCall("user-1", "run-1", "call-1",
                "gpt-test", 100, 1000, "chat");
        AgentBillingCall retry = service.reserveCall("user-1", "run-1", "call-1",
                "gpt-test", 100, 1000, "chat");

        assertEquals("reserved", first.getStatus());
        assertEquals(100, first.getMaxOutputTokens());
        assertEquals(first.getReservedAmount(), retry.getReservedAmount());
        assertEquals(first.getMaxOutputTokens(), retry.getMaxOutputTokens());
        verify(billingDao, times(1)).debitWallet(anyString(), any(BigDecimal.class));
        verify(billingDao, times(1)).findWalletAvailableForUpdate("user-1");
        verify(billingDao, times(1)).insertCall(any(AgentCallChargePO.class));

        try {
            service.reserveCall("user-1", "run-1", "call-1", "gpt-test", 100, 999, "chat");
            fail("expected original request payload conflict");
        } catch (AgentBillingException exception) {
            assertEquals(AgentBillingException.Code.CONFLICT, exception.getCode());
        }
    }

    @Test
    public void duplicateCallInsertReturnsExistingStateBeforeWalletDebit() {
        AgentRunBudgetPO run = run("run-duplicate", "1.00000000", "0.00000000", "0.00000000");
        when(billingDao.findRunForUpdate("user-1", "run-duplicate")).thenReturn(run);
        AgentCallChargePO[] persistedCall = new AgentCallChargePO[1];
        when(billingDao.findCallForUpdate("user-1", "call-duplicate"))
                .thenAnswer(invocation -> persistedCall[0]);
        doAnswer(invocation -> {
            persistedCall[0] = invocation.getArgument(0);
            throw new DuplicateKeyException("duplicate call id");
        }).when(billingDao).insertCall(any(AgentCallChargePO.class));

        AgentBillingCall result = service.reserveCall("user-1", "run-duplicate", "call-duplicate",
                "gpt-test", 100, 100, "chat");

        assertEquals("reserved", result.getStatus());
        verify(billingDao, never()).debitWallet(anyString(), any(BigDecimal.class));
    }

    @Test
    public void budgetRemainingShrinksRequestedOutputAndHoldsExactlyTheBudgetCap() {
        AgentRunBudgetPO run = run("run-budget-adaptive", "0.00022000", "0.00000000", "0.00000000");
        when(billingDao.findRunForUpdate("user-1", "run-budget-adaptive")).thenReturn(run);
        AgentCallChargePO[] persistedCall = new AgentCallChargePO[1];
        when(billingDao.findCallForUpdate("user-1", "call-budget-adaptive"))
                .thenAnswer(invocation -> persistedCall[0]);
        doAnswer(invocation -> {
            persistedCall[0] = invocation.getArgument(0);
            return 1;
        }).when(billingDao).insertCall(any(AgentCallChargePO.class));
        when(billingDao.debitWallet(anyString(), any(BigDecimal.class))).thenReturn(1);
        when(billingDao.updateRun(any(AgentRunBudgetPO.class))).thenReturn(1);

        AgentBillingCall result = service.reserveCall("user-1", "run-budget-adaptive",
                "call-budget-adaptive", "gpt-test", 100, 1000, "chat");

        // ceil(100 * 1.20) = 120; 120 input + 100 output tokens costs 0.00022000.
        assertEquals(100, result.getMaxOutputTokens());
        assertEquals(new BigDecimal("0.00022000"), result.getReservedAmount());
        assertEquals(Integer.valueOf(100), persistedCall[0].getMaxOutputTokens());
        assertTrue(result.getReservedAmount().compareTo(run.getBudgetLimit()) <= 0);
    }

    @Test
    public void walletRemainingShrinksRequestedOutputEvenWhenRunBudgetIsLarger() {
        AgentRunBudgetPO run = run("run-wallet-adaptive", "1.00000000", "0.00000000", "0.00000000");
        when(billingDao.findRunForUpdate("user-1", "run-wallet-adaptive")).thenReturn(run);
        when(billingDao.findWalletAvailableForUpdate("user-1"))
                .thenReturn(new BigDecimal("0.00015000"));
        AgentCallChargePO[] persistedCall = new AgentCallChargePO[1];
        when(billingDao.findCallForUpdate("user-1", "call-wallet-adaptive"))
                .thenAnswer(invocation -> persistedCall[0]);
        doAnswer(invocation -> {
            persistedCall[0] = invocation.getArgument(0);
            return 1;
        }).when(billingDao).insertCall(any(AgentCallChargePO.class));
        when(billingDao.debitWallet(anyString(), any(BigDecimal.class))).thenReturn(1);
        when(billingDao.updateRun(any(AgentRunBudgetPO.class))).thenReturn(1);

        AgentBillingCall result = service.reserveCall("user-1", "run-wallet-adaptive",
                "call-wallet-adaptive", "gpt-test", 100, 1000, "chat");

        assertEquals(30, result.getMaxOutputTokens());
        assertEquals(new BigDecimal("0.00015000"), result.getReservedAmount());
        assertEquals(Integer.valueOf(30), persistedCall[0].getMaxOutputTokens());
    }

    @Test
    public void cumulativeReservedAndSettledAmountsLimitTheNextHold() {
        AgentRunBudgetPO run = run("run-cumulative", "0.00050000", "0.00010000", "0.00020000");
        when(billingDao.findRunForUpdate("user-1", "run-cumulative")).thenReturn(run);
        AgentCallChargePO[] persistedCall = new AgentCallChargePO[1];
        when(billingDao.findCallForUpdate("user-1", "call-cumulative"))
                .thenAnswer(invocation -> persistedCall[0]);
        doAnswer(invocation -> {
            persistedCall[0] = invocation.getArgument(0);
            return 1;
        }).when(billingDao).insertCall(any(AgentCallChargePO.class));
        when(billingDao.debitWallet(anyString(), any(BigDecimal.class))).thenReturn(1);
        when(billingDao.updateRun(any(AgentRunBudgetPO.class))).thenReturn(1);

        AgentBillingCall result = service.reserveCall("user-1", "run-cumulative",
                "call-cumulative", "gpt-test", 100, 1000, "chat");

        // The run has only 0.00020000 left after the existing hold and spend.
        assertEquals(80, result.getMaxOutputTokens());
        assertEquals(new BigDecimal("0.00020000"), result.getReservedAmount());
    }

    @Test
    public void eightDecimalCeilingKeepsHoldAtLedgerPrecision() {
        model.setInputPrice(new BigDecimal("0.00000000"));
        model.setCachedInputPrice(new BigDecimal("0.00000000"));
        model.setOutputPrice(new BigDecimal("0.00000001"));
        AgentRunBudgetPO run = run("run-rounding", "0.00000001", "0.00000000", "0.00000000");
        when(billingDao.findRunForUpdate("user-1", "run-rounding")).thenReturn(run);
        when(billingDao.findWalletAvailableForUpdate("user-1"))
                .thenReturn(new BigDecimal("0.00000001"));
        AgentCallChargePO[] persistedCall = new AgentCallChargePO[1];
        when(billingDao.findCallForUpdate("user-1", "call-rounding"))
                .thenAnswer(invocation -> persistedCall[0]);
        doAnswer(invocation -> {
            persistedCall[0] = invocation.getArgument(0);
            return 1;
        }).when(billingDao).insertCall(any(AgentCallChargePO.class));
        when(billingDao.debitWallet(anyString(), any(BigDecimal.class))).thenReturn(1);
        when(billingDao.updateRun(any(AgentRunBudgetPO.class))).thenReturn(1);

        AgentBillingCall result = service.reserveCall("user-1", "run-rounding",
                "call-rounding", "gpt-test", 0, 2, "chat");

        // The raw formula admits 2 output tokens, and the ledger rounds their
        // combined sub-cent cost up to the exact eight-decimal unit.
        assertEquals(2, result.getMaxOutputTokens());
        assertEquals(new BigDecimal("0.00000001"), result.getReservedAmount());
    }

    @Test
    public void zeroRatesAllowTheCatalogMaximumWithoutDivisionByZero() {
        model.setInputPrice(new BigDecimal("0.00000000"));
        model.setCachedInputPrice(new BigDecimal("0.00000000"));
        model.setOutputPrice(new BigDecimal("0.00000000"));
        AgentRunBudgetPO run = run("run-zero-rate", "0.00000001", "0.00000000", "0.00000000");
        when(billingDao.findRunForUpdate("user-1", "run-zero-rate")).thenReturn(run);
        AgentCallChargePO[] persistedCall = new AgentCallChargePO[1];
        when(billingDao.findCallForUpdate("user-1", "call-zero-rate"))
                .thenAnswer(invocation -> persistedCall[0]);
        doAnswer(invocation -> {
            persistedCall[0] = invocation.getArgument(0);
            return 1;
        }).when(billingDao).insertCall(any(AgentCallChargePO.class));
        when(billingDao.debitWallet(anyString(), any(BigDecimal.class))).thenReturn(1);
        when(billingDao.updateRun(any(AgentRunBudgetPO.class))).thenReturn(1);

        AgentBillingCall result = service.reserveCall("user-1", "run-zero-rate",
                "call-zero-rate", "gpt-test", 100, 4096, "chat");

        assertEquals(4096, result.getMaxOutputTokens());
        assertEquals(new BigDecimal("0.00000000"), result.getReservedAmount());
    }

    @Test
    public void budgetAndWalletShortfallsAreReportedSeparatelyBeforeInsertOrDebit() {
        AgentRunBudgetPO budgetRun = run("run-budget-short", "0.00011999", "0.00000000", "0.00000000");
        when(billingDao.findRunForUpdate("user-1", "run-budget-short")).thenReturn(budgetRun);
        try {
            service.reserveCall("user-1", "run-budget-short", "call-budget-short", "gpt-test", 100, 10, "chat");
            fail("expected budget cap error");
        } catch (AgentBillingException exception) {
            assertEquals(AgentBillingException.Code.CAP_REACHED, exception.getCode());
        }

        AgentRunBudgetPO walletRun = run("run-wallet-short", "1.00000000", "0.00000000", "0.00000000");
        when(billingDao.findRunForUpdate("user-1", "run-wallet-short")).thenReturn(walletRun);
        when(billingDao.findWalletAvailableForUpdate("user-1"))
                .thenReturn(new BigDecimal("0.00011999"));
        try {
            service.reserveCall("user-1", "run-wallet-short", "call-wallet-short", "gpt-test", 100, 10, "chat");
            fail("expected wallet funds error");
        } catch (AgentBillingException exception) {
            assertEquals(AgentBillingException.Code.INSUFFICIENT_FUNDS, exception.getCode());
        }
        verify(billingDao, never()).insertCall(any(AgentCallChargePO.class));
        verify(billingDao, never()).debitWallet(anyString(), any(BigDecimal.class));
    }

    @Test
    public void inputPaddingRejectsLongOverflowBeforeWalletLock() {
        AgentRunBudgetPO run = run("run-overflow", "1.00000000", "0.00000000", "0.00000000");
        when(billingDao.findRunForUpdate("user-1", "run-overflow")).thenReturn(run);
        try {
            service.reserveCall("user-1", "run-overflow", "call-overflow", "gpt-test",
                    Long.MAX_VALUE, 10, "chat");
            fail("expected input estimate validation error");
        } catch (AgentBillingException exception) {
            assertEquals(AgentBillingException.Code.BAD_REQUEST, exception.getCode());
        }
        verify(billingDao, never()).findWalletAvailableForUpdate(anyString());
        verify(billingDao, never()).insertCall(any(AgentCallChargePO.class));
        verify(billingDao, never()).debitWallet(anyString(), any(BigDecimal.class));
    }

    @Test
    public void staleRecoveryUsesAnIndependentTransactionForEachCandidate() {
        AgentRunBudgetPO firstRun = run("run-stale-one", "1.00000000", "0.10000000", "0.00000000");
        AgentRunBudgetPO secondRun = run("run-stale-two", "1.00000000", "0.10000000", "0.00000000");
        AgentCallChargePO firstCall = call("run-stale-one", "call-stale-one", "0.10000000", AgentCallChargePO.RESERVED);
        AgentCallChargePO secondCall = call("run-stale-two", "call-stale-two", "0.10000000", AgentCallChargePO.RESERVED);
        firstCall.setLeaseUntil(new Date(System.currentTimeMillis() - 1_000L));
        secondCall.setLeaseUntil(new Date(System.currentTimeMillis() - 1_000L));
        when(billingDao.findStaleCalls(any(Date.class), anyInt())).thenReturn(List.of(firstCall, secondCall));
        when(billingDao.findRunForUpdate("user-1", "run-stale-one")).thenReturn(firstRun);
        when(billingDao.findRunForUpdate("user-1", "run-stale-two")).thenReturn(secondRun);
        when(billingDao.findCallForUpdate("user-1", "call-stale-one")).thenReturn(firstCall);
        when(billingDao.findCallForUpdate("user-1", "call-stale-two")).thenReturn(secondCall);
        when(billingDao.refundWallet("user-1", new BigDecimal("0.10000000"))).thenReturn(1);
        when(billingDao.updateCall(any(AgentCallChargePO.class))).thenReturn(1);
        when(billingDao.updateRun(any(AgentRunBudgetPO.class))).thenReturn(1);

        assertEquals(2, service.releaseStaleCalls());
        verify(transactionManager, times(2)).getTransaction(any(TransactionDefinition.class));
        verify(transactionManager, times(2)).commit(any(TransactionStatus.class));
    }

    @Test
    public void settleCapsUserChargeAndRecordsObservedExcess() {
        AgentRunBudgetPO run = run("run-2", "1.00000000", "0.10000000", "0.00000000");
        AgentCallChargePO call = call("run-2", "call-2", "0.10000000", AgentCallChargePO.RESERVED);
        when(billingDao.findCall("user-1", "call-2")).thenReturn(call);
        when(billingDao.findRunForUpdate("user-1", "run-2")).thenReturn(run);
        when(billingDao.findCallForUpdate("user-1", "call-2")).thenReturn(call);
        when(billingDao.updateCall(any(AgentCallChargePO.class))).thenReturn(1);
        when(billingDao.updateRun(any(AgentRunBudgetPO.class))).thenReturn(1);

        AgentBillingCall settled = service.settleCall("user-1", "call-2",
                1_000_000, 0, 0);

        assertEquals("settled", settled.getStatus());
        assertEquals(new BigDecimal("0.10000000"), settled.getSettledAmount());
        assertEquals(new BigDecimal("0.90000000"), settled.getObservedExcessAmount());
        assertTrue(settled.isReviewRequired());
        verify(billingDao, never()).debitWallet(anyString(), any(BigDecimal.class));
        verify(billingDao, never()).refundWallet(anyString(), any(BigDecimal.class));
    }

    @Test
    public void releaseIsIdempotentAndUnknownUsageRefundsTheHoldOnce() {
        AgentRunBudgetPO run = run("run-3", "1.00000000", "0.10000000", "0.00000000");
        AgentCallChargePO call = call("run-3", "call-3", "0.10000000", AgentCallChargePO.RESERVED);
        when(billingDao.findCall("user-1", "call-3")).thenReturn(call);
        when(billingDao.findRunForUpdate("user-1", "run-3")).thenReturn(run);
        when(billingDao.findCallForUpdate("user-1", "call-3")).thenReturn(call);
        when(billingDao.refundWallet("user-1", new BigDecimal("0.10000000"))).thenReturn(1);
        when(billingDao.updateCall(any(AgentCallChargePO.class))).thenReturn(1);
        when(billingDao.updateRun(any(AgentRunBudgetPO.class))).thenReturn(1);

        AgentBillingCall released = service.releaseCall("user-1", "call-3", "unknown_usage");
        AgentBillingCall retry = service.releaseCall("user-1", "call-3", "unknown_usage");

        assertEquals("released", released.getStatus());
        assertEquals("released", retry.getStatus());
        verify(billingDao).refundWallet("user-1", new BigDecimal("0.10000000"));
    }

    @Test
    public void racedRunInitializationCannotChangeItsBudget() {
        when(billingDao.findRunForUpdate("user-1", "run-4")).thenReturn(null);
        when(billingDao.insertRun(any(AgentRunBudgetPO.class))).thenReturn(0);
        when(billingDao.findRunForUpdate("user-1", "run-4"))
                .thenReturn(null, run("run-4", "2.00000000", "0.00000000", "0.00000000"));

        try {
            service.createOrGetRun("user-1", "run-4", new BigDecimal("1.00000000"));
            fail("expected budget conflict");
        } catch (AgentBillingException exception) {
            assertEquals(AgentBillingException.Code.CONFLICT, exception.getCode());
        }
    }

    @Test
    public void staleRecoveryRechecksTheLeaseAfterTakingLocks() {
        AgentRunBudgetPO run = run("run-stale", "1.00000000", "0.10000000", "0.00000000");
        AgentCallChargePO staleCandidate = call("run-stale", "call-stale", "0.10000000", AgentCallChargePO.RESERVED);
        staleCandidate.setLeaseUntil(new Date(System.currentTimeMillis() - 1_000L));
        AgentCallChargePO renewedCall = call("run-stale", "call-stale", "0.10000000", AgentCallChargePO.RESERVED);
        renewedCall.setLeaseUntil(new Date(System.currentTimeMillis() + 300_000L));
        when(billingDao.findStaleCalls(any(Date.class), anyInt())).thenReturn(List.of(staleCandidate));
        when(billingDao.findRunForUpdate("user-1", "run-stale")).thenReturn(run);
        when(billingDao.findCallForUpdate("user-1", "call-stale")).thenReturn(renewedCall);

        assertEquals(0, service.releaseStaleCalls());
        verify(billingDao, never()).refundWallet(anyString(), any(BigDecimal.class));
        verify(billingDao, never()).updateCall(any(AgentCallChargePO.class));
    }

    private static AgentRunBudgetPO run(String runId, String budget, String reserved, String settled) {
        return AgentRunBudgetPO.builder()
                .id(1L)
                .userId("user-1")
                .runId(runId)
                .status(AgentRunBudgetPO.ACTIVE)
                .budgetLimit(new BigDecimal(budget))
                .reservedAmount(new BigDecimal(reserved))
                .settledAmount(new BigDecimal(settled))
                .reviewRequired(false)
                .build();
    }

    private static AgentCallChargePO call(String runId, String callId, String reserved, int status) {
        return AgentCallChargePO.builder()
                .id(1L)
                .userId("user-1")
                .runId(runId)
                .callId(callId)
                .payloadHash("hash")
                .purpose("chat")
                .model("gpt-test")
                .inputPrice(new BigDecimal("1.00000000"))
                .cachedInputPrice(new BigDecimal("0.50000000"))
                .outputPrice(new BigDecimal("1.00000000"))
                .maxOutputTokens(100)
                .reservedAmount(new BigDecimal(reserved))
                .settledAmount(new BigDecimal("0.00000000"))
                .observedExcessAmount(new BigDecimal("0.00000000"))
                .status(status)
                .reviewRequired(false)
                .leaseUntil(new Date(System.currentTimeMillis() + 300_000L))
                .build();
    }
}
