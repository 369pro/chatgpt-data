package cn.bugstack.chatgpt.data.test.infrastructure;

import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatBillingQuote;
import cn.bugstack.chatgpt.data.domain.openai.model.entity.ChatUsage;
import cn.bugstack.chatgpt.data.infrastructure.adapter.repository.OpenAiRepository;
import cn.bugstack.chatgpt.data.infrastructure.dao.IChatQuotaDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IUserAccountDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.ChatQuotaPO;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests wallet authorization and token usage settlement while keeping the
 * historical one-unit quota path covered by OpenAiRepositoryTest.
 */
public class TokenBillingRepositoryTest {

    private static final BigDecimal INPUT_PRICE = new BigDecimal("3.00000000");
    private static final BigDecimal CACHED_INPUT_PRICE = new BigDecimal("1.00000000");
    private static final BigDecimal OUTPUT_PRICE = new BigDecimal("5.00000000");

    private IChatQuotaDao chatQuotaDao;
    private OpenAiRepository repository;
    private RecordingTransactionManager transactionManager;

    @Before
    public void setUp() {
        chatQuotaDao = mock(IChatQuotaDao.class);
        transactionManager = new RecordingTransactionManager();
        repository = new OpenAiRepository();
        setField(repository, "chatQuotaDao", chatQuotaDao);
        setField(repository, "transactionManager", transactionManager);
    }

    @Test
    public void legacyEntitlementIsConsumedBeforeWalletAuthorization() {
        ChatBillingQuote quote = quote("0.01000000");
        when(chatQuotaDao.insertReservation(any(ChatQuotaPO.class))).thenReturn(1);
        when(chatQuotaDao.decrementUserQuota("user-1")).thenReturn(1);

        assertTrue(repository.reserveChatQuota("user-1", "request-1", quote));

        verify(chatQuotaDao).decrementUserQuota("user-1");
        verify(chatQuotaDao, never()).debitWallet(anyString(), any(BigDecimal.class));
        verify(chatQuotaDao, never()).recordWalletCharge(anyString(), anyString(), any(BigDecimal.class));
    }

    @Test
    public void tokenReservationPreauthorizesWalletAndSnapshotsRatesAfterLegacyMiss() {
        ChatBillingQuote quote = quote("0.01000000");
        when(chatQuotaDao.insertReservation(any(ChatQuotaPO.class))).thenReturn(1);
        when(chatQuotaDao.decrementUserQuota("user-1")).thenReturn(0);
        when(chatQuotaDao.debitWallet("user-1", quote.getReservedAmount())).thenReturn(1);
        when(chatQuotaDao.recordWalletCharge("user-1", "request-1", quote.getReservedAmount()))
                .thenReturn(1);

        assertTrue(repository.reserveChatQuota("user-1", "request-1", quote));

        org.mockito.ArgumentCaptor<ChatQuotaPO> reservation =
                org.mockito.ArgumentCaptor.forClass(ChatQuotaPO.class);
        verify(chatQuotaDao).insertReservation(reservation.capture());
        assertEquals("snapshot-model", reservation.getValue().getModel());
        assertMoney(INPUT_PRICE, reservation.getValue().getInputPrice());
        assertMoney(CACHED_INPUT_PRICE, reservation.getValue().getCachedInputPrice());
        assertMoney(OUTPUT_PRICE, reservation.getValue().getOutputPrice());
        verify(chatQuotaDao).debitWallet("user-1", quote.getReservedAmount());
        verify(chatQuotaDao).recordWalletCharge("user-1", "request-1", quote.getReservedAmount());
        assertEquals(1, transactionManager.commitCount.get());
    }

    @Test
    public void tokenReservationRollsBackWhenWalletCannotCoverAuthorization() {
        ChatBillingQuote quote = quote("0.01000000");
        when(chatQuotaDao.insertReservation(any(ChatQuotaPO.class))).thenReturn(1);
        when(chatQuotaDao.decrementUserQuota("user-1")).thenReturn(0);
        when(chatQuotaDao.debitWallet("user-1", quote.getReservedAmount())).thenReturn(0);

        assertFalse(repository.reserveChatQuota("user-1", "request-1", quote));

        verify(chatQuotaDao).insertReservation(any(ChatQuotaPO.class));
        verify(chatQuotaDao).decrementUserQuota("user-1");
        verify(chatQuotaDao).debitWallet("user-1", quote.getReservedAmount());
        verify(chatQuotaDao, never()).recordWalletCharge(anyString(), anyString(), any(BigDecimal.class));
        assertEquals(1, transactionManager.rollbackCount.get());
        assertEquals(0, transactionManager.commitCount.get());
    }

    @Test
    public void tokenSettlementUsesSnapshottedRatesAndRefundsAuthorizationDifference() {
        ChatQuotaPO reservation = walletReservation("0.01000000");
        when(chatQuotaDao.queryReservation("user-1", "request-1")).thenReturn(reservation);
        when(chatQuotaDao.refundWallet("user-1", new BigDecimal("0.00730000"))).thenReturn(1);
        when(chatQuotaDao.recordSettlement(any(ChatQuotaPO.class))).thenReturn(1);

        repository.settleChatUsage("user-1", "request-1", new ChatUsage(1000, 100, 400));

        org.mockito.ArgumentCaptor<ChatQuotaPO> settlement =
                org.mockito.ArgumentCaptor.forClass(ChatQuotaPO.class);
        verify(chatQuotaDao).recordSettlement(settlement.capture());
        ChatQuotaPO settled = settlement.getValue();
        assertEquals(Integer.valueOf(ChatQuotaPO.CONFIRMED), settled.getStatus());
        assertMoney(new BigDecimal("0.00270000"), settled.getSettledAmount());
        assertEquals(Long.valueOf(1000L), settled.getPromptTokens());
        assertEquals(Long.valueOf(100L), settled.getCompletionTokens());
        assertEquals(Long.valueOf(400L), settled.getCachedPromptTokens());
        verify(chatQuotaDao).refundWallet("user-1", new BigDecimal("0.00730000"));
    }

    @Test
    public void legacyReservationSettlesWithZeroMoney() {
        ChatQuotaPO reservation = ChatQuotaPO.builder()
                .openid("user-1")
                .requestId("request-1")
                .status(ChatQuotaPO.RESERVED)
                .chargeAmount(null)
                .build();
        when(chatQuotaDao.queryReservation("user-1", "request-1")).thenReturn(reservation);
        when(chatQuotaDao.recordSettlement(any(ChatQuotaPO.class))).thenReturn(1);

        repository.settleChatUsage("user-1", "request-1", new ChatUsage(1000, 100, 400));

        org.mockito.ArgumentCaptor<ChatQuotaPO> settlement =
                org.mockito.ArgumentCaptor.forClass(ChatQuotaPO.class);
        verify(chatQuotaDao).recordSettlement(settlement.capture());
        assertEquals(Integer.valueOf(ChatQuotaPO.CONFIRMED), settlement.getValue().getStatus());
        assertMoney(BigDecimal.ZERO, settlement.getValue().getSettledAmount());
        verify(chatQuotaDao, never()).refundWallet(anyString(), any(BigDecimal.class));
        verify(chatQuotaDao, never()).debitWallet(anyString(), any(BigDecimal.class));
    }

    @Test
    public void usageOverAuthorizationDebitsAdditionalWalletAmount() {
        ChatQuotaPO reservation = walletReservation("0.00100000");
        when(chatQuotaDao.queryReservation("user-1", "request-1")).thenReturn(reservation);
        when(chatQuotaDao.debitWallet("user-1", new BigDecimal("0.00170000"))).thenReturn(1);
        when(chatQuotaDao.recordSettlement(any(ChatQuotaPO.class))).thenReturn(1);

        repository.settleChatUsage("user-1", "request-1", new ChatUsage(1000, 100, 400));

        verify(chatQuotaDao).debitWallet("user-1", new BigDecimal("0.00170000"));
        org.mockito.ArgumentCaptor<ChatQuotaPO> settlement =
                org.mockito.ArgumentCaptor.forClass(ChatQuotaPO.class);
        verify(chatQuotaDao).recordSettlement(settlement.capture());
        assertEquals(Integer.valueOf(ChatQuotaPO.CONFIRMED), settlement.getValue().getStatus());
        assertMoney(new BigDecimal("0.00270000"), settlement.getValue().getSettledAmount());
    }

    @Test
    public void usageOverAuthorizationMarksReviewWhenAdditionalDebitFails() {
        ChatQuotaPO reservation = walletReservation("0.00100000");
        when(chatQuotaDao.queryReservation("user-1", "request-1")).thenReturn(reservation);
        when(chatQuotaDao.debitWallet("user-1", new BigDecimal("0.00170000"))).thenReturn(0);
        when(chatQuotaDao.recordSettlement(any(ChatQuotaPO.class))).thenReturn(1);

        repository.settleChatUsage("user-1", "request-1", new ChatUsage(1000, 100, 400));

        org.mockito.ArgumentCaptor<ChatQuotaPO> settlement =
                org.mockito.ArgumentCaptor.forClass(ChatQuotaPO.class);
        verify(chatQuotaDao).recordSettlement(settlement.capture());
        assertEquals(Integer.valueOf(ChatQuotaPO.REVIEW), settlement.getValue().getStatus());
        assertMoney(new BigDecimal("0.00270000"), settlement.getValue().getSettledAmount());
    }

    @Test
    public void failedTokenSettlementQueriesChargeRowAndRefundsExactAuthorizationOnce() {
        ChatQuotaPO reservation = walletReservation("0.01000000");
        when(chatQuotaDao.releaseReservation("user-1", "request-1")).thenReturn(1);
        when(chatQuotaDao.queryReservation("user-1", "request-1")).thenReturn(reservation);
        when(chatQuotaDao.refundWallet("user-1", new BigDecimal("0.01000000"))).thenReturn(1);

        repository.settleChatQuota("user-1", "request-1", false);

        verify(chatQuotaDao).releaseReservation("user-1", "request-1");
        verify(chatQuotaDao).queryReservation("user-1", "request-1");
        verify(chatQuotaDao).refundWallet("user-1", new BigDecimal("0.01000000"));
        verify(chatQuotaDao, never()).refundUserQuota(anyString());
    }

    @Test
    public void settledTokenUsageRetryDoesNotRefundOrSettleAgain() {
        ChatQuotaPO settled = walletReservation("0.01000000");
        settled.setStatus(ChatQuotaPO.CONFIRMED);
        when(chatQuotaDao.queryReservation("user-1", "request-1")).thenReturn(settled);

        repository.settleChatUsage("user-1", "request-1", new ChatUsage(1000, 100, 400));

        verify(chatQuotaDao).queryReservation("user-1", "request-1");
        verify(chatQuotaDao, never()).refundWallet(anyString(), any(BigDecimal.class));
        verify(chatQuotaDao, never()).debitWallet(anyString(), any(BigDecimal.class));
        verify(chatQuotaDao, never()).recordSettlement(any(ChatQuotaPO.class));
    }

    /**
     * These tests are opt-in because they require the billing migration and a
     * local MySQL database. Use the same CHAT_QUOTA_* or MYSQL_* variables as
     * OpenAiRepositoryTest; credentials are consumed only by the fixture.
     */
    @Test
    public void sqlTokenPreauthorizationSnapshotsRatesAndSettlesCachedInput() throws Exception {
        assumeSqlIntegration();
        try (SqlFixture fixture = SqlFixture.open()) {
            String openid = uniqueValue("user");
            String requestId = uniqueValue("request");
            try {
                fixture.insertAccount(openid, 0, 0);
                fixture.insertWallet(openid, "0.02000000");
                ChatBillingQuote quote = quote("0.01000000");

                assertTrue(fixture.repository.reserveChatQuota(openid, requestId, quote));
                assertMoney(new BigDecimal("0.01000000"), fixture.walletAmount(openid));
                assertMoney(quote.getReservedAmount(), fixture.decimal(
                        "SELECT charge_amount FROM chat_quota_request WHERE openid = ? AND request_id = ?",
                        openid, requestId));
                assertEquals("snapshot-model", fixture.string(
                        "SELECT model FROM chat_quota_request WHERE openid = ? AND request_id = ?",
                        openid, requestId));
                assertMoney(INPUT_PRICE, fixture.decimal(
                        "SELECT input_price FROM chat_quota_request WHERE openid = ? AND request_id = ?",
                        openid, requestId));
                assertMoney(CACHED_INPUT_PRICE, fixture.decimal(
                        "SELECT cached_input_price FROM chat_quota_request WHERE openid = ? AND request_id = ?",
                        openid, requestId));
                assertMoney(OUTPUT_PRICE, fixture.decimal(
                        "SELECT output_price FROM chat_quota_request WHERE openid = ? AND request_id = ?",
                        openid, requestId));

                fixture.inTransaction(() -> fixture.repository.settleChatUsage(
                        openid, requestId, new ChatUsage(1000, 100, 400)));

                // (1000 - 400) * 3 + 400 * 1 + 100 * 5 = 2700 / 1,000,000 CNY.
                assertMoney(new BigDecimal("0.01730000"), fixture.walletAmount(openid));
                assertMoney(new BigDecimal("0.00270000"), fixture.decimal(
                        "SELECT settled_amount FROM chat_quota_request WHERE openid = ? AND request_id = ?",
                        openid, requestId));
                assertEquals(Integer.valueOf(ChatQuotaPO.CONFIRMED), fixture.integer(
                        "SELECT status FROM chat_quota_request WHERE openid = ? AND request_id = ?",
                        openid, requestId));
            } finally {
                fixture.deleteAccount(openid);
            }
        }
    }

    @Test
    public void sqlConcurrentTokenDuplicateRequestPreauthorizesWalletOnce() throws Exception {
        assumeSqlIntegration();
        try (SqlFixture fixture = SqlFixture.open()) {
            String openid = uniqueValue("user");
            String requestId = uniqueValue("request");
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                fixture.insertAccount(openid, 0, 0);
                fixture.insertWallet(openid, "0.01000000");
                ChatBillingQuote quote = quote("0.00200000");
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Callable<Attempt> attempt = () -> {
                    ready.countDown();
                    start.await(30, TimeUnit.SECONDS);
                    try {
                        return Attempt.success(fixture.repository.reserveChatQuota(openid, requestId, quote));
                    } catch (RuntimeException e) {
                        return Attempt.failure(e);
                    }
                };
                Future<Attempt> first = executor.submit(attempt);
                Future<Attempt> second = executor.submit(attempt);
                assertTrue(ready.await(30, TimeUnit.SECONDS));
                start.countDown();

                List<Attempt> attempts = new ArrayList<>();
                attempts.add(first.get(30, TimeUnit.SECONDS));
                attempts.add(second.get(30, TimeUnit.SECONDS));
                int successes = 0;
                int duplicateFailures = 0;
                for (Attempt result : attempts) {
                    if (result.success) successes++;
                    if (result.failure instanceof IllegalStateException) duplicateFailures++;
                }
                assertEquals(1, successes);
                assertEquals(1, duplicateFailures);
                assertEquals(1, fixture.countLedger(openid, requestId));
                assertMoney(new BigDecimal("0.00800000"), fixture.walletAmount(openid));
            } finally {
                executor.shutdownNow();
                fixture.deleteAccount(openid);
            }
        }
    }

    @Test
    public void sqlInsufficientTokenWalletRollsBackReservationAndBalance() throws Exception {
        assumeSqlIntegration();
        try (SqlFixture fixture = SqlFixture.open()) {
            String openid = uniqueValue("user");
            String requestId = uniqueValue("request");
            try {
                fixture.insertAccount(openid, 0, 0);
                fixture.insertWallet(openid, "0.00100000");

                assertFalse(fixture.repository.reserveChatQuota(openid, requestId, quote("0.00200000")));
                assertEquals(0, fixture.countLedger(openid, requestId));
                assertMoney(new BigDecimal("0.00100000"), fixture.walletAmount(openid));
            } finally {
                fixture.deleteAccount(openid);
            }
        }
    }

    @Test
    public void sqlFailedTokenSettlementRefundsAuthorizationOnlyOnce() throws Exception {
        assumeSqlIntegration();
        try (SqlFixture fixture = SqlFixture.open()) {
            String openid = uniqueValue("user");
            String requestId = uniqueValue("request");
            try {
                fixture.insertAccount(openid, 0, 0);
                fixture.insertWallet(openid, "0.01000000");
                assertTrue(fixture.repository.reserveChatQuota(openid, requestId, quote("0.00200000")));
                assertMoney(new BigDecimal("0.00800000"), fixture.walletAmount(openid));

                fixture.inTransaction(() -> fixture.repository.settleChatQuota(openid, requestId, false));
                fixture.inTransaction(() -> fixture.repository.settleChatQuota(openid, requestId, false));

                assertMoney(new BigDecimal("0.01000000"), fixture.walletAmount(openid));
                assertEquals(Integer.valueOf(ChatQuotaPO.RELEASED), fixture.integer(
                        "SELECT status FROM chat_quota_request WHERE openid = ? AND request_id = ?",
                        openid, requestId));
            } finally {
                fixture.deleteAccount(openid);
            }
        }
    }

    @Test
    public void sqlTokenSettlementRetryDoesNotApplyRefundTwice() throws Exception {
        assumeSqlIntegration();
        try (SqlFixture fixture = SqlFixture.open()) {
            String openid = uniqueValue("user");
            String requestId = uniqueValue("request");
            try {
                fixture.insertAccount(openid, 0, 0);
                fixture.insertWallet(openid, "0.01000000");
                assertTrue(fixture.repository.reserveChatQuota(openid, requestId, quote("0.00400000")));

                ChatUsage usage = new ChatUsage(1000, 100, 400);
                fixture.inTransaction(() -> fixture.repository.settleChatUsage(openid, requestId, usage));
                BigDecimal balanceAfterFirstSettlement = fixture.walletAmount(openid);
                fixture.inTransaction(() -> fixture.repository.settleChatUsage(openid, requestId, usage));

                // (600 uncached * 3 + 400 cached * 1 + 100 output * 5) / 1M = 0.0027.
                assertMoney(new BigDecimal("0.00730000"), balanceAfterFirstSettlement);
                assertMoney(balanceAfterFirstSettlement, fixture.walletAmount(openid));
                assertEquals(Integer.valueOf(ChatQuotaPO.CONFIRMED), fixture.integer(
                        "SELECT status FROM chat_quota_request WHERE openid = ? AND request_id = ?",
                        openid, requestId));
            } finally {
                fixture.deleteAccount(openid);
            }
        }
    }

    private static ChatBillingQuote quote(String reservedAmount) {
        return new ChatBillingQuote("snapshot-model", INPUT_PRICE, CACHED_INPUT_PRICE,
                OUTPUT_PRICE, new BigDecimal(reservedAmount));
    }

    private static ChatQuotaPO walletReservation(String chargeAmount) {
        return ChatQuotaPO.builder()
                .openid("user-1")
                .requestId("request-1")
                .status(ChatQuotaPO.RESERVED)
                .chargeAmount(new BigDecimal(chargeAmount))
                .model("snapshot-model")
                .inputPrice(INPUT_PRICE)
                .cachedInputPrice(CACHED_INPUT_PRICE)
                .outputPrice(OUTPUT_PRICE)
                .build();
    }

    private static void assertMoney(BigDecimal expected, BigDecimal actual) {
        assertTrue("expected " + expected + " but was " + actual,
                actual != null && expected.compareTo(actual) == 0);
    }

    private static void assumeSqlIntegration() {
        String enabled = System.getProperty(
                "chat.quota.sql.integration",
                System.getenv("CHAT_QUOTA_SQL_INTEGRATION")
        );
        Assume.assumeTrue("Set CHAT_QUOTA_SQL_INTEGRATION=true to run MySQL tests",
                Boolean.parseBoolean(enabled));
    }

    private static String uniqueValue(String prefix) {
        return "token-it-" + prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to set test field: " + name, e);
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private final AtomicInteger commitCount = new AtomicInteger();
        private final AtomicInteger rollbackCount = new AtomicInteger();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commitCount.incrementAndGet();
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbackCount.incrementAndGet();
        }
    }

    private static final class Attempt {
        private final boolean success;
        private final RuntimeException failure;

        private Attempt(boolean success, RuntimeException failure) {
            this.success = success;
            this.failure = failure;
        }

        private static Attempt success(boolean success) {
            return new Attempt(success, null);
        }

        private static Attempt failure(RuntimeException failure) {
            return new Attempt(false, failure);
        }
    }

    private static final class SqlFixture implements AutoCloseable {
        private final JdbcTemplate jdbcTemplate;
        private final DataSourceTransactionManager transactionManager;
        private final OpenAiRepository repository;

        private SqlFixture(JdbcTemplate jdbcTemplate,
                           DataSourceTransactionManager transactionManager,
                           OpenAiRepository repository) {
            this.jdbcTemplate = jdbcTemplate;
            this.transactionManager = transactionManager;
            this.repository = repository;
        }

        private static SqlFixture open() throws Exception {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setUrl(setting(
                    "chat.quota.jdbc.url",
                    "CHAT_QUOTA_JDBC_URL",
                    setting("", "MYSQL_URL",
                            "jdbc:mysql://127.0.0.1:3306/openai?useUnicode=true&characterEncoding=utf8"
                                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true")
            ));
            dataSource.setUsername(setting(
                    "chat.quota.jdbc.user",
                    "CHAT_QUOTA_DB_USER",
                    setting("", "MYSQL_USERNAME", "root")
            ));
            dataSource.setPassword(setting(
                    "chat.quota.jdbc.password",
                    "CHAT_QUOTA_DB_PASSWORD",
                    setting("", "MYSQL_PASSWORD", "")
            ));

            SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource mapper = resolver.getResource("classpath:/mybatis/mapper/chat_quota_mapper.xml");
            factoryBean.setMapperLocations(mapper);
            factoryBean.afterPropertiesSet();

            SqlSessionTemplate sqlSession = new SqlSessionTemplate(factoryBean.getObject());
            IChatQuotaDao chatQuotaDao = sqlSession.getMapper(IChatQuotaDao.class);
            OpenAiRepository repository = new OpenAiRepository();
            setField(repository, "userAccountDao", mock(IUserAccountDao.class));
            setField(repository, "chatQuotaDao", chatQuotaDao);
            DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
            setField(repository, "transactionManager", transactionManager);
            return new SqlFixture(new JdbcTemplate(dataSource), transactionManager, repository);
        }

        private void insertAccount(String openid, int totalQuota, int surplusQuota) {
            jdbcTemplate.update(
                    "INSERT INTO user_account "
                            + "(openid, total_quota, surplus_quota, model_types, status) "
                            + "VALUES (?, ?, ?, ?, 0)",
                    openid, totalQuota, surplusQuota, "gpt-3.5-turbo"
            );
        }

        private void insertWallet(String openid, String amount) {
            BigDecimal value = new BigDecimal(amount);
            jdbcTemplate.update(
                    "INSERT INTO account_wallet (openid, currency, available_amount, total_amount) "
                            + "VALUES (?, 'CNY', ?, ?)",
                    openid, value, value
            );
        }

        private int countLedger(String openid, String requestId) {
            Integer count = integer(
                    "SELECT COUNT(*) FROM chat_quota_request WHERE openid = ? AND request_id = ?",
                    openid, requestId);
            return count == null ? 0 : count;
        }

        private BigDecimal walletAmount(String openid) {
            return decimal("SELECT available_amount FROM account_wallet WHERE openid = ?", openid);
        }

        private BigDecimal decimal(String sql, Object... args) {
            return jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
        }

        private Integer integer(String sql, Object... args) {
            return jdbcTemplate.queryForObject(sql, Integer.class, args);
        }

        private String string(String sql, Object... args) {
            return jdbcTemplate.queryForObject(sql, String.class, args);
        }

        private void inTransaction(Runnable action) {
            new TransactionTemplate(transactionManager).execute(status -> {
                action.run();
                return null;
            });
        }

        private void deleteAccount(String openid) {
            jdbcTemplate.update("DELETE FROM chat_quota_request WHERE openid = ?", openid);
            jdbcTemplate.update("DELETE FROM account_wallet WHERE openid = ?", openid);
            jdbcTemplate.update("DELETE FROM user_account WHERE openid = ?", openid);
        }

        private static String setting(String property, String environment, String fallback) {
            String propertyValue = property.isEmpty() ? null : System.getProperty(property);
            if (propertyValue != null) return propertyValue;
            String environmentValue = System.getenv(environment);
            return environmentValue == null ? fallback : environmentValue;
        }

        @Override
        public void close() {
            // DriverManagerDataSource owns no pool or closeable resource.
        }
    }
}
