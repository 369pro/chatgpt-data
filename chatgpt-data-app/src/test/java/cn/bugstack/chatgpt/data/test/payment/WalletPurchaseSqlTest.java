package cn.bugstack.chatgpt.data.test.payment;

import cn.bugstack.chatgpt.data.infrastructure.adapter.repository.OrderRepository;
import cn.bugstack.chatgpt.data.infrastructure.dao.IAccountWalletDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IBalanceGrantDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IOpenAIOrderDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IOpenAIProductDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IUserAccountDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.po.OpenAIOrderPO;
import org.junit.After;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

/**
 * Opt-in MySQL coverage for wallet purchase delivery. This class deliberately
 * builds only the MyBatis and transaction infrastructure needed by the order
 * repository; it does not start the application or call a payment provider.
 */
public class WalletPurchaseSqlTest {

    private static final String PAYMENT_APP_ID = "payment";
    private static final BigDecimal WALLET_AMOUNT = new BigDecimal("9.90");
    private static final BigDecimal MAX_WALLET_AMOUNT = new BigDecimal("999999999999.99999999");
    private static final AtomicInteger PRODUCT_SEQUENCE = new AtomicInteger(1800000000);

    private SqlFixture fixture;

    @Before
    public void setUp() throws Exception {
        assumeSqlIntegration();
        fixture = SqlFixture.open();
    }

    @After
    public void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    public void paidWalletOrderIsGrantedOnceAcrossConcurrentAndRepeatedDelivery() throws Exception {
        String openid = uniqueOpenid();
        String orderId = uniqueOrderId();
        try {
            fixture.insertPaidOrder(openid, orderId, 19001, 0, WALLET_AMOUNT, WALLET_AMOUNT);

            fixture.deliverConcurrently(orderId);
            assertWalletGrant(openid, orderId, WALLET_AMOUNT);
            assertEquals(Integer.valueOf(2), fixture.queryInt(
                    "SELECT order_status FROM openai_order WHERE order_id = ?", orderId));

            fixture.deliver(orderId);
            fixture.deliverConcurrently(orderId);

            assertWalletGrant(openid, orderId, WALLET_AMOUNT);
            assertEquals(Integer.valueOf(1), fixture.queryInt(
                    "SELECT COUNT(*) FROM balance_grant WHERE app_id = ? AND request_id = ?",
                    PAYMENT_APP_ID, orderId));
            assertEquals(Integer.valueOf(1), fixture.queryInt(
                    "SELECT COUNT(*) FROM account_wallet WHERE openid = ?", openid));
            assertAmount(WALLET_AMOUNT, fixture.queryDecimal(
                    "SELECT available_amount FROM account_wallet WHERE openid = ?", openid));
            assertAmount(WALLET_AMOUNT, fixture.queryDecimal(
                    "SELECT total_amount FROM account_wallet WHERE openid = ?", openid));
            assertEquals(Integer.valueOf(0), fixture.queryInt(
                    "SELECT total_quota FROM user_account WHERE openid = ?", openid));
            assertEquals(Integer.valueOf(0), fixture.queryInt(
                    "SELECT surplus_quota FROM user_account WHERE openid = ?", openid));
        } finally {
            fixture.deleteFixture(openid, orderId, null);
        }
    }

    @Test
    public void legacyPaidOrderDeliversQuotaWithoutWalletGrant() {
        String openid = uniqueOpenid();
        String orderId = uniqueOrderId();
        try {
            fixture.insertPaidOrder(openid, orderId, 19002, 7, new BigDecimal("7.00"), null);

            fixture.deliver(orderId);

            assertEquals(Integer.valueOf(2), fixture.queryInt(
                    "SELECT order_status FROM openai_order WHERE order_id = ?", orderId));
            assertEquals(Integer.valueOf(7), fixture.queryInt(
                    "SELECT total_quota FROM user_account WHERE openid = ?", openid));
            assertEquals(Integer.valueOf(7), fixture.queryInt(
                    "SELECT surplus_quota FROM user_account WHERE openid = ?", openid));
            assertEquals(Integer.valueOf(0), fixture.queryInt(
                    "SELECT COUNT(*) FROM account_wallet WHERE openid = ?", openid));
            assertEquals(Integer.valueOf(0), fixture.queryInt(
                    "SELECT COUNT(*) FROM balance_grant WHERE app_id = ? AND request_id = ?",
                    PAYMENT_APP_ID, orderId));
        } finally {
            fixture.deleteFixture(openid, orderId, null);
        }
    }

    @Test
    public void walletUpdateFailureRollsBackDeliveryStateAndGrantLedger() {
        String openid = uniqueOpenid();
        String orderId = uniqueOrderId();
        try {
            fixture.insertOverflowWallet(openid);
            fixture.insertPaidOrder(openid, orderId, 19003, 0, WALLET_AMOUNT, WALLET_AMOUNT);

            RuntimeException failure = fixture.deliverWithStrictDecimalFailure(orderId);
            assertNotNull("wallet update should fail in strict decimal mode", failure);
            assertEquals(Integer.valueOf(1), fixture.queryInt(
                    "SELECT order_status FROM openai_order WHERE order_id = ?", orderId));
            assertEquals(Integer.valueOf(0), fixture.queryInt(
                    "SELECT COUNT(*) FROM balance_grant WHERE app_id = ? AND request_id = ?",
                    PAYMENT_APP_ID, orderId));
            assertAmount(MAX_WALLET_AMOUNT, fixture.queryDecimal(
                    "SELECT available_amount FROM account_wallet WHERE openid = ?", openid));
            assertAmount(MAX_WALLET_AMOUNT, fixture.queryDecimal(
                    "SELECT total_amount FROM account_wallet WHERE openid = ?", openid));
            assertEquals(Integer.valueOf(0), fixture.queryInt(
                    "SELECT COUNT(*) FROM user_account WHERE openid = ?", openid));
        } finally {
            fixture.deleteFixture(openid, orderId, null);
        }
    }

    @Test
    public void walletOrderUsesCreditSnapshotAfterProductPriceChanges() {
        String openid = uniqueOpenid();
        String orderId = uniqueOrderId();
        int productId = PRODUCT_SEQUENCE.getAndIncrement();
        try {
            fixture.insertProduct(productId, WALLET_AMOUNT, WALLET_AMOUNT);
            fixture.insertPaidOrder(openid, orderId, productId, 0, WALLET_AMOUNT, WALLET_AMOUNT);
            fixture.updateProduct(productId, new BigDecimal("99.90"), new BigDecimal("99.90"));

            fixture.deliver(orderId);

            assertWalletGrant(openid, orderId, WALLET_AMOUNT);
            assertAmount(WALLET_AMOUNT, fixture.queryDecimal(
                    "SELECT product_credit_amount FROM openai_order WHERE order_id = ?", orderId));
            assertEquals(Integer.valueOf(2), fixture.queryInt(
                    "SELECT order_status FROM openai_order WHERE order_id = ?", orderId));
        } finally {
            fixture.deleteFixture(openid, orderId, productId);
        }
    }

    private void assertWalletGrant(String openid, String orderId, BigDecimal amount) {
        assertEquals(Integer.valueOf(1), fixture.queryInt(
                "SELECT COUNT(*) FROM balance_grant WHERE app_id = ? AND request_id = ?",
                PAYMENT_APP_ID, orderId));
        assertEquals(openid, fixture.queryString(
                "SELECT openid FROM balance_grant WHERE app_id = ? AND request_id = ?",
                PAYMENT_APP_ID, orderId));
        assertAmount(amount, fixture.queryDecimal(
                "SELECT amount FROM balance_grant WHERE app_id = ? AND request_id = ?",
                PAYMENT_APP_ID, orderId));
    }

    private static void assertAmount(BigDecimal expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, expected.compareTo(actual));
    }

    private static void assumeSqlIntegration() {
        String enabled = System.getProperty(
                "chat.quota.sql.integration",
                System.getenv("CHAT_QUOTA_SQL_INTEGRATION")
        );
        Assume.assumeTrue("Set CHAT_QUOTA_SQL_INTEGRATION=true to run MySQL tests",
                Boolean.parseBoolean(enabled));
    }

    private static String uniqueOpenid() {
        return "wallet-it-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String uniqueOrderId() {
        // openai_order.order_id is varchar(12), so keep the fixture UUID compact.
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private static final class SqlFixture implements AutoCloseable {
        private final JdbcTemplate jdbcTemplate;
        private final TransactionTemplate transactionTemplate;
        private final OrderRepository repository;
        private final IOpenAIOrderDao orderDao;

        private SqlFixture(JdbcTemplate jdbcTemplate,
                           TransactionTemplate transactionTemplate,
                           OrderRepository repository,
                           IOpenAIOrderDao orderDao) {
            this.jdbcTemplate = jdbcTemplate;
            this.transactionTemplate = transactionTemplate;
            this.repository = repository;
            this.orderDao = orderDao;
        }

        private static SqlFixture open() throws Exception {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setUrl(setting(
                    "chat.quota.jdbc.url",
                    "CHAT_QUOTA_JDBC_URL",
                    setting("mysql.url", "MYSQL_URL",
                            "jdbc:mysql://127.0.0.1:3306/openai?useUnicode=true"
                                    + "&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                                    + "&useSSL=false&allowPublicKeyRetrieval=true")
            ));
            dataSource.setUsername(setting(
                    "chat.quota.jdbc.user",
                    "CHAT_QUOTA_DB_USER",
                    setting("mysql.username", "MYSQL_USERNAME", "root")
            ));
            dataSource.setPassword(setting(
                    "chat.quota.jdbc.password",
                    "CHAT_QUOTA_DB_PASSWORD",
                    setting("mysql.password", "MYSQL_PASSWORD", "")
            ));

            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setMapperLocations(new Resource[]{
                    resolver.getResource("classpath:/mybatis/mapper/openai_order_mapper.xml"),
                    resolver.getResource("classpath:/mybatis/mapper/user_account_mapper.xml"),
                    resolver.getResource("classpath:/mybatis/mapper/account_wallet_mapper.xml"),
                    resolver.getResource("classpath:/mybatis/mapper/account_balance_mapper.xml")
            });
            factoryBean.afterPropertiesSet();

            SqlSessionTemplate sqlSession = new SqlSessionTemplate(factoryBean.getObject());
            IOpenAIOrderDao orderDao = sqlSession.getMapper(IOpenAIOrderDao.class);
            IUserAccountDao userAccountDao = sqlSession.getMapper(IUserAccountDao.class);
            IAccountWalletDao accountWalletDao = sqlSession.getMapper(IAccountWalletDao.class);
            IBalanceGrantDao balanceGrantDao = sqlSession.getMapper(IBalanceGrantDao.class);

            OrderRepository repository = new OrderRepository();
            setField(repository, "openAIOrderDao", orderDao);
            setField(repository, "openAIProductDao", mock(IOpenAIProductDao.class));
            setField(repository, "userAccountDao", userAccountDao);
            setField(repository, "accountWalletDao", accountWalletDao);
            setField(repository, "balanceGrantDao", balanceGrantDao);
            setField(repository, "modelTypes", "wallet-sql-test-model");

            DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
            return new SqlFixture(
                    new JdbcTemplate(dataSource),
                    new TransactionTemplate(transactionManager),
                    repository,
                    orderDao
            );
        }

        private void insertPaidOrder(String openid,
                                     String orderId,
                                     int productId,
                                     int productQuota,
                                     BigDecimal price,
                                     BigDecimal creditAmount) {
            OpenAIOrderPO order = OpenAIOrderPO.builder()
                    .openid(openid)
                    .productId(productId)
                    .productName("wallet-it")
                    .productQuota(productQuota)
                    .productCreditAmount(creditAmount)
                    .orderId(orderId)
                    .orderTime(new Date())
                    .orderStatus(1)
                    .totalAmount(price)
                    .payType(0)
                    .payStatus(1)
                    .build();
            transactionTemplate.execute(status -> {
                orderDao.insert(order);
                return null;
            });
        }

        private void insertProduct(int productId, BigDecimal price, BigDecimal creditAmount) {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update(
                        "INSERT INTO openai_product "
                                + "(product_id, product_name, product_desc, product_model_types, "
                                + "quota, price, credit_amount, sort, is_enabled) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        productId, "Wallet SQL Test", "Wallet SQL fixture", "wallet-sql-test-model",
                        0, price, creditAmount, 999, 1
                );
                return null;
            });
        }

        private void updateProduct(int productId, BigDecimal price, BigDecimal creditAmount) {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update(
                        "UPDATE openai_product SET price = ?, credit_amount = ? WHERE product_id = ?",
                        price, creditAmount, productId
                );
                return null;
            });
        }

        private void insertOverflowWallet(String openid) {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update(
                        "INSERT INTO account_wallet "
                                + "(openid, currency, available_amount, total_amount, create_time, update_time) "
                                + "VALUES (?, 'CNY', ?, ?, NOW(), NOW())",
                        openid, MAX_WALLET_AMOUNT, MAX_WALLET_AMOUNT
                );
                return null;
            });
        }

        private void deliver(String orderId) {
            transactionTemplate.execute(status -> {
                repository.deliverGoods(orderId);
                return null;
            });
        }

        private RuntimeException deliverWithStrictDecimalFailure(String orderId) {
            try {
                transactionTemplate.execute(status -> {
                    jdbcTemplate.execute("SET SESSION sql_mode = 'STRICT_TRANS_TABLES'");
                    repository.deliverGoods(orderId);
                    return null;
                });
                fail("Expected the overflowing wallet update to fail");
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        }

        private void deliverConcurrently(String orderId) throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Void> delivery = () -> {
                ready.countDown();
                assertTrue("delivery workers did not start", start.await(30, TimeUnit.SECONDS));
                deliver(orderId);
                return null;
            };
            try {
                Future<Void> first = executor.submit(delivery);
                Future<Void> second = executor.submit(delivery);
                assertTrue("delivery workers were not ready", ready.await(30, TimeUnit.SECONDS));
                start.countDown();
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(30, TimeUnit.SECONDS);
            }
        }

        private Integer queryInt(String sql, Object... args) {
            return jdbcTemplate.queryForObject(sql, Integer.class, args);
        }

        private String queryString(String sql, Object... args) {
            return jdbcTemplate.queryForObject(sql, String.class, args);
        }

        private BigDecimal queryDecimal(String sql, Object... args) {
            return jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
        }

        private void deleteFixture(String openid, String orderId, Integer productId) {
            transactionTemplate.execute(status -> {
                jdbcTemplate.update(
                        "DELETE FROM balance_grant WHERE app_id = ? AND request_id = ?",
                        PAYMENT_APP_ID, orderId
                );
                jdbcTemplate.update("DELETE FROM openai_order WHERE order_id = ?", orderId);
                jdbcTemplate.update("DELETE FROM account_wallet WHERE openid = ?", openid);
                jdbcTemplate.update("DELETE FROM user_account WHERE openid = ?", openid);
                if (productId != null) {
                    jdbcTemplate.update("DELETE FROM openai_product WHERE product_id = ?", productId);
                }
                return null;
            });
        }

        private static String setting(String property, String environment, String fallback) {
            String propertyValue = System.getProperty(property);
            if (propertyValue != null && !propertyValue.trim().isEmpty()) return propertyValue;
            String environmentValue = System.getenv(environment);
            return environmentValue == null || environmentValue.trim().isEmpty()
                    ? fallback
                    : environmentValue;
        }

        @Override
        public void close() {
            // DriverManagerDataSource owns no pool or closeable resource.
        }
    }
}
