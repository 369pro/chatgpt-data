package cn.bugstack.chatgpt.data.test.infrastructure;

import cn.bugstack.chatgpt.data.infrastructure.adapter.repository.OpenAiRepository;
import cn.bugstack.chatgpt.data.infrastructure.dao.IChatQuotaDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IUserAccountDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.ChatQuotaPO;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assume;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Collections;
import java.util.Date;
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
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused tests for repository transaction boundaries and conditional ledger
 * transitions. Database-level uniqueness and SQL shape are covered by the
 * accompanying migration and mapper.
 */
public class OpenAiRepositoryTest {

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
    public void reserveRollsBackLedgerWhenAccountHasNoQuota() {
        when(chatQuotaDao.insertReservation(any(ChatQuotaPO.class))).thenReturn(1);
        when(chatQuotaDao.decrementUserQuota("user-1")).thenReturn(0);

        assertFalse(repository.reserveChatQuota("user-1", "request-1"));

        verify(chatQuotaDao).insertReservation(any(ChatQuotaPO.class));
        verify(chatQuotaDao).decrementUserQuota("user-1");
        assertTrue(transactionManager.rollbackCount.get() == 1);
        assertTrue(transactionManager.commitCount.get() == 0);
    }

    @Test
    public void reserveConvertsDuplicateRequestIntoDistinctException() {
        doThrow(new DuplicateKeyException("duplicate request"))
                .when(chatQuotaDao).insertReservation(any(ChatQuotaPO.class));

        try {
            repository.reserveChatQuota("user-1", "request-1");
            fail("Expected duplicate request to fail explicitly");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("already exists"));
        }

        verify(chatQuotaDao).insertReservation(any(ChatQuotaPO.class));
        org.mockito.Mockito.verify(chatQuotaDao, org.mockito.Mockito.never())
                .decrementUserQuota(anyString());
        assertTrue(transactionManager.rollbackCount.get() == 1);
    }

    @Test
    public void failedSettlementRefundsOnlyWinningRelease() {
        when(chatQuotaDao.queryReservation("user-1", "request-1"))
                .thenReturn(ChatQuotaPO.builder()
                        .openid("user-1")
                        .requestId("request-1")
                        .status(ChatQuotaPO.RELEASED)
                        .build());
        when(chatQuotaDao.releaseReservation("user-1", "request-1")).thenReturn(1);
        when(chatQuotaDao.refundUserQuota("user-1")).thenReturn(1);

        repository.settleChatQuota("user-1", "request-1", false);

        verify(chatQuotaDao).releaseReservation("user-1", "request-1");
        verify(chatQuotaDao).queryReservation("user-1", "request-1");
        verify(chatQuotaDao).refundUserQuota("user-1");
    }

    @Test
    public void successfulSettlementConfirmsWithoutRefund() {
        when(chatQuotaDao.confirmReservation("user-1", "request-1")).thenReturn(1);

        repository.settleChatQuota("user-1", "request-1", true);

        verify(chatQuotaDao).confirmReservation("user-1", "request-1");
        org.mockito.Mockito.verify(chatQuotaDao, org.mockito.Mockito.never())
                .releaseReservation(anyString(), anyString());
        org.mockito.Mockito.verify(chatQuotaDao, org.mockito.Mockito.never())
                .refundUserQuota(anyString());
    }

    @Test
    public void alreadySettledFailureDoesNotRefundAgain() {
        when(chatQuotaDao.releaseReservation("user-1", "request-1")).thenReturn(0);

        repository.settleChatQuota("user-1", "request-1", false);

        verify(chatQuotaDao).releaseReservation("user-1", "request-1");
        verifyNoInteractionsExceptRelease();
    }

    @Test
    public void nonDuplicatePersistenceFailureRemainsDataAccessException() {
        when(chatQuotaDao.queryReservation("user-1", "request-1"))
                .thenReturn(ChatQuotaPO.builder()
                        .openid("user-1")
                        .requestId("request-1")
                        .status(ChatQuotaPO.RELEASED)
                        .build());
        when(chatQuotaDao.releaseReservation("user-1", "request-1")).thenReturn(1);
        when(chatQuotaDao.refundUserQuota("user-1")).thenReturn(0);

        try {
            repository.settleChatQuota("user-1", "request-1", false);
            fail("Expected a data access failure when the refund update affects no row");
        } catch (DataAccessException e) {
            assertTrue(e.getMessage().contains("Unable to refund"));
        }
    }

    @Test
    public void expiryReleaseUsesBoundedBatchAndRefundsReleasedRows() {
        ChatQuotaPO expired = ChatQuotaPO.builder()
                .id(11L)
                .openid("user-1")
                .requestId("request-1")
                .status(ChatQuotaPO.RESERVED)
                .build();
        when(chatQuotaDao.findExpiredReservations(any(Date.class), anyInt()))
                .thenReturn(Collections.singletonList(expired));
        when(chatQuotaDao.releaseReservationById(11L)).thenReturn(1);
        when(chatQuotaDao.refundUserQuota("user-1")).thenReturn(1);

        repository.releaseExpiredChatQuotas();

        verify(chatQuotaDao).findExpiredReservations(any(Date.class), eq(100));
        verify(chatQuotaDao).releaseReservationById(11L);
        verify(chatQuotaDao).refundUserQuota("user-1");
    }

    /**
     * These tests are opt-in because they require the migration and a local
     * MySQL database. Run with CHAT_QUOTA_SQL_INTEGRATION=true and provide
     * CHAT_QUOTA_JDBC_URL, CHAT_QUOTA_DB_USER, and CHAT_QUOTA_DB_PASSWORD
     * (or the MYSQL_* equivalents).
     */
    @Test
    public void sqlInsufficientQuotaRollsBackLedgerInsert() throws Exception {
        assumeSqlIntegration();
        try (SqlFixture fixture = SqlFixture.open()) {
            String openid = uniqueValue("user");
            String requestId = uniqueValue("request");
            try {
                fixture.insertAccount(openid, 0, 0);

                assertFalse(fixture.repository.reserveChatQuota(openid, requestId));
                assertEquals(0, fixture.countLedger(openid, requestId));
                assertEquals(Integer.valueOf(0), fixture.queryInt(
                        "SELECT surplus_quota FROM user_account WHERE openid = ?", openid));
            } finally {
                fixture.deleteAccount(openid);
            }
        }
    }

    @Test
    public void sqlConcurrentDuplicateRequestHasOneReservation() throws Exception {
        assumeSqlIntegration();
        try (SqlFixture fixture = SqlFixture.open()) {
            String openid = uniqueValue("user");
            String requestId = uniqueValue("request");
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                fixture.insertAccount(openid, 2, 2);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Callable<Attempt> attempt = () -> {
                    ready.countDown();
                    start.await(30, TimeUnit.SECONDS);
                    try {
                        return Attempt.success(fixture.repository.reserveChatQuota(openid, requestId));
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
                assertEquals(Integer.valueOf(1), fixture.queryInt(
                        "SELECT surplus_quota FROM user_account WHERE openid = ?", openid));
            } finally {
                executor.shutdownNow();
                fixture.deleteAccount(openid);
            }
        }
    }

    @Test
    public void sqlFailedSettlementTwiceRefundsOnlyOnceAndKeepsTotalQuota() throws Exception {
        assumeSqlIntegration();
        try (SqlFixture fixture = SqlFixture.open()) {
            String openid = uniqueValue("user");
            String requestId = uniqueValue("request");
            try {
                fixture.insertAccount(openid, 7, 1);
                assertTrue(fixture.repository.reserveChatQuota(openid, requestId));
                assertEquals(Integer.valueOf(0), fixture.queryInt(
                        "SELECT surplus_quota FROM user_account WHERE openid = ?", openid));

                fixture.inTransaction(() -> fixture.repository.settleChatQuota(openid, requestId, false));
                fixture.inTransaction(() -> fixture.repository.settleChatQuota(openid, requestId, false));

                assertEquals(Integer.valueOf(7), fixture.queryInt(
                        "SELECT total_quota FROM user_account WHERE openid = ?", openid));
                assertEquals(Integer.valueOf(1), fixture.queryInt(
                        "SELECT surplus_quota FROM user_account WHERE openid = ?", openid));
                assertEquals(Integer.valueOf(ChatQuotaPO.RELEASED), fixture.queryInt(
                        "SELECT status FROM chat_quota_request WHERE openid = ? AND request_id = ?",
                        openid, requestId));
            } finally {
                fixture.deleteAccount(openid);
            }
        }
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
        return "quota-it-" + prefix + "-" + UUID.randomUUID().toString().replace("-", "");
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

    private void verifyNoInteractionsExceptRelease() {
        org.mockito.Mockito.verify(chatQuotaDao, org.mockito.Mockito.never()).refundUserQuota(anyString());
        org.mockito.Mockito.verify(chatQuotaDao, org.mockito.Mockito.never())
                .queryReservation(anyString(), anyString());
        org.mockito.Mockito.verify(chatQuotaDao, org.mockito.Mockito.never()).confirmReservation(anyString(), anyString());
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

        private int countLedger(String openid, String requestId) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_quota_request WHERE openid = ? AND request_id = ?",
                    Integer.class,
                    openid,
                    requestId
            );
            return count == null ? 0 : count;
        }

        private Integer queryInt(String sql, Object... args) {
            return jdbcTemplate.queryForObject(sql, Integer.class, args);
        }

        private void inTransaction(Runnable action) {
            new TransactionTemplate(transactionManager).execute(status -> {
                action.run();
                return null;
            });
        }

        private void deleteAccount(String openid) {
            jdbcTemplate.update("DELETE FROM chat_quota_request WHERE openid = ?", openid);
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
