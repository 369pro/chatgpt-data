package cn.bugstack.chatgpt.data.test.payment;

import cn.bugstack.chatgpt.data.domain.order.model.aggregates.CreateOrderAggregate;
import cn.bugstack.chatgpt.data.domain.order.model.entity.OrderEntity;
import cn.bugstack.chatgpt.data.domain.order.model.entity.ProductEntity;
import cn.bugstack.chatgpt.data.domain.order.model.entity.ShopCartEntity;
import cn.bugstack.chatgpt.data.domain.order.model.entity.UnpaidOrderEntity;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.OrderStatusVO;
import cn.bugstack.chatgpt.data.domain.order.model.valobj.PayTypeVO;
import cn.bugstack.chatgpt.data.infrastructure.adapter.repository.OrderRepository;
import cn.bugstack.chatgpt.data.infrastructure.dao.IAccountWalletDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IBalanceGrantDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IOpenAIOrderDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IOpenAIProductDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IUserAccountDao;
import cn.bugstack.chatgpt.data.infrastructure.dao.IPaymentEventOutboxDao;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Opt-in MySQL coverage for wallet purchase delivery. This class deliberately
 * builds only the MyBatis and transaction infrastructure needed by the order
 * repository; it does not start the application or call a payment provider.
 */
public class WalletPurchaseSqlTest {

    private static final String PAYMENT_APP_ID = "payment";
    private static final BigDecimal GROSS_AMOUNT = new BigDecimal("29.90");
    private static final BigDecimal FEE_RATE = new BigDecimal("0.20");
    private static final BigDecimal FEE_AMOUNT = new BigDecimal("5.98");
    private static final BigDecimal NET_AMOUNT = new BigDecimal("23.92");
    private static final BigDecimal WALLET_AMOUNT = NET_AMOUNT;
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
            fixture.insertPaidOrder(openid, orderId, 19001, GROSS_AMOUNT, NET_AMOUNT,
                    FEE_RATE, FEE_AMOUNT);

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
            assertEquals(Integer.valueOf(1), fixture.queryInt(
                    "SELECT COUNT(*) FROM payment_event_outbox WHERE event_type = 'DELIVERED' AND order_id = ?",
                    orderId));
            assertAmount(WALLET_AMOUNT, fixture.queryDecimal(
                    "SELECT available_amount FROM account_wallet WHERE openid = ?", openid));
            assertAmount(WALLET_AMOUNT, fixture.queryDecimal(
                    "SELECT total_amount FROM account_wallet WHERE openid = ?", openid));
        } finally {
            fixture.deleteFixture(openid, orderId, null);
        }
    }

    @Test
    public void historicalPaidOrderWithNullCreditSnapshotStaysPendingForManualHandling() {
        String openid = uniqueOpenid();
        String orderId = uniqueOrderId();
        try {
            fixture.insertPaidOrder(openid, orderId, 19002, new BigDecimal("7.00"), null);

            fixture.deliver(orderId);

            assertEquals(Integer.valueOf(1), fixture.queryInt(
                    "SELECT order_status FROM openai_order WHERE order_id = ?", orderId));
            assertEquals(Integer.valueOf(0), fixture.queryInt(
                    "SELECT COUNT(*) FROM user_account WHERE openid = ?", openid));
            assertEquals(Integer.valueOf(0), fixture.queryInt(
                    "SELECT COUNT(*) FROM account_wallet WHERE openid = ?", openid));
            assertEquals(Integer.valueOf(0), fixture.queryInt(
                    "SELECT COUNT(*) FROM balance_grant WHERE app_id = ? AND request_id = ?",
                    PAYMENT_APP_ID, orderId));
            assertEquals(Integer.valueOf(0), fixture.queryInt(
                    "SELECT COUNT(*) FROM payment_event_outbox WHERE order_id = ?", orderId));
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
            fixture.insertPaidOrder(openid, orderId, 19003, GROSS_AMOUNT, NET_AMOUNT,
                    FEE_RATE, FEE_AMOUNT);

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
            fixture.insertProduct(productId, GROSS_AMOUNT, NET_AMOUNT);
            fixture.insertPaidOrder(openid, orderId, productId, GROSS_AMOUNT, NET_AMOUNT,
                    FEE_RATE, FEE_AMOUNT);
            fixture.updateProduct(productId, new BigDecimal("99.90"), new BigDecimal("89.91"));

            fixture.deliver(orderId);

            assertWalletGrant(openid, orderId, WALLET_AMOUNT);
            assertAmount(WALLET_AMOUNT, fixture.queryDecimal(
                "SELECT product_credit_amount FROM openai_order WHERE order_id = ?", orderId));
            assertAmount(FEE_RATE, fixture.queryDecimal(
                    "SELECT product_fee_rate FROM openai_order WHERE order_id = ?", orderId));
            assertAmount(FEE_AMOUNT, fixture.queryDecimal(
                    "SELECT product_fee_amount FROM openai_order WHERE order_id = ?", orderId));
            assertEquals(Integer.valueOf(2), fixture.queryInt(
                    "SELECT order_status FROM openai_order WHERE order_id = ?", orderId));
        } finally {
            fixture.deleteFixture(openid, orderId, productId);
        }
    }

    @Test
    public void repositorySaveOrderValidatesGrossPaymentAndDeliversNetOnce() throws Exception {
        String openid = uniqueOpenid();
        String orderId = uniqueOrderId();
        int productId = PRODUCT_SEQUENCE.getAndIncrement();
        try {
            ProductEntity product = ProductEntity.builder()
                    .productId(productId)
                    .productName("wallet-sql-save")
                    .productDesc("Wallet SQL fixture")
                    .price(GROSS_AMOUNT)
                    .creditAmount(GROSS_AMOUNT)
                    .build();
            product.applyRechargeFee();
            OrderEntity order = OrderEntity.builder()
                    .orderId(orderId)
                    .orderTime(new Date())
                    .orderStatus(OrderStatusVO.CREATE)
                    .totalAmount(GROSS_AMOUNT)
                    .payTypeVO(PayTypeVO.ALIPAY_SANDBOX)
                    .build();

            fixture.saveOrder(CreateOrderAggregate.builder()
                    .openid(openid)
                    .product(product)
                    .order(order)
                    .build());

            assertAmount(GROSS_AMOUNT, fixture.queryDecimal(
                    "SELECT total_amount FROM openai_order WHERE order_id = ?", orderId));
            assertAmount(NET_AMOUNT, fixture.queryDecimal(
                    "SELECT product_credit_amount FROM openai_order WHERE order_id = ?", orderId));
            assertAmount(FEE_RATE, fixture.queryDecimal(
                    "SELECT product_fee_rate FROM openai_order WHERE order_id = ?", orderId));
            assertAmount(FEE_AMOUNT, fixture.queryDecimal(
                    "SELECT product_fee_amount FROM openai_order WHERE order_id = ?", orderId));
            CreateOrderAggregate saved = fixture.repository.queryOrder(orderId);
            assertAmount(FEE_RATE, saved.getProduct().getFeeRate());
            assertAmount(FEE_AMOUNT, saved.getProduct().getFeeAmount());

            assertFalse(fixture.markPaid(orderId, "underpaid-" + orderId, NET_AMOUNT));
            assertEquals(Integer.valueOf(0), fixture.queryInt(
                    "SELECT order_status FROM openai_order WHERE order_id = ?", orderId));
            assertTrue(fixture.markPaid(orderId, "paid-" + orderId, GROSS_AMOUNT));
            assertEquals(Integer.valueOf(1), fixture.queryInt(
                    "SELECT COUNT(*) FROM payment_event_outbox WHERE event_type = 'PAID' AND order_id = ?",
                    orderId));
            assertFalse(fixture.markPaid(orderId, "paid-again-" + orderId, GROSS_AMOUNT));
            assertEquals(Integer.valueOf(1), fixture.queryInt(
                    "SELECT COUNT(*) FROM payment_event_outbox WHERE event_type = 'PAID' AND order_id = ?",
                    orderId));

            fixture.deliverConcurrently(orderId);
            fixture.deliver(orderId);
            assertWalletGrant(openid, orderId, NET_AMOUNT);
            assertAmount(NET_AMOUNT, fixture.queryDecimal(
                    "SELECT available_amount FROM account_wallet WHERE openid = ?", openid));
            assertEquals(Integer.valueOf(1), fixture.queryInt(
                    "SELECT COUNT(*) FROM balance_grant WHERE app_id = ? AND request_id = ?",
                    PAYMENT_APP_ID, orderId));
            assertEquals(Integer.valueOf(2), fixture.queryInt(
                    "SELECT order_status FROM openai_order WHERE order_id = ?", orderId));
        } finally {
            fixture.deleteFixture(openid, orderId, productId);
        }
    }

    @Test
    public void legacyWalletOrderWithNullFeeSnapshotKeepsOriginalCredit() {
        String openid = uniqueOpenid();
        String orderId = uniqueOrderId();
        try {
            fixture.insertPaidOrder(openid, orderId, 19004, GROSS_AMOUNT, GROSS_AMOUNT,
                    null, null);

            fixture.deliver(orderId);

            assertWalletGrant(openid, orderId, GROSS_AMOUNT);
            assertEquals(Integer.valueOf(1), fixture.queryInt(
                    "SELECT COUNT(*) FROM openai_order "
                            + "WHERE order_id = ? AND product_fee_rate IS NULL AND product_fee_amount IS NULL",
                    orderId));
        } finally {
            fixture.deleteFixture(openid, orderId, null);
        }
    }

    @Test
    public void legacyUnpaidOrderWithNullFeeSnapshotIsNotReusedAfterRequote() {
        String openid = uniqueOpenid();
        String orderId = uniqueOrderId();
        int productId = PRODUCT_SEQUENCE.getAndIncrement();
        try {
            fixture.insertProduct(productId, GROSS_AMOUNT, NET_AMOUNT);
            fixture.insertUnpaidOrder(openid, orderId, productId, GROSS_AMOUNT, GROSS_AMOUNT,
                    null, null);

            ShopCartEntity cart = ShopCartEntity.builder()
                    .openid(openid)
                    .productId(productId)
                    .payType(PayTypeVO.ALIPAY_SANDBOX)
                    .build();
            assertNull(fixture.repository.queryUnpaidOrder(cart));
        } finally {
            fixture.deleteFixture(openid, orderId, productId);
        }
    }

    @Test
    public void legacyUnpaidOrderIsIgnoredAndWalletUnpaidOrderIsReusable() {
        String openid = uniqueOpenid();
        String legacyOrderId = uniqueOrderId();
        String walletOrderId = uniqueOrderId();
        int productId = PRODUCT_SEQUENCE.getAndIncrement();
        try {
            fixture.insertProduct(productId, GROSS_AMOUNT, null);
            fixture.insertUnpaidOrder(openid, legacyOrderId, productId, GROSS_AMOUNT, null,
                    null, null);

            ShopCartEntity legacyCart = ShopCartEntity.builder()
                    .openid(openid)
                    .productId(productId)
                    .payType(PayTypeVO.ALIPAY_SANDBOX)
                    .build();
            assertNull(fixture.repository.queryUnpaidOrder(legacyCart));

            fixture.updateProduct(productId, GROSS_AMOUNT, NET_AMOUNT);
            fixture.insertUnpaidOrder(openid, walletOrderId, productId, GROSS_AMOUNT, NET_AMOUNT,
                    FEE_RATE, FEE_AMOUNT);

            UnpaidOrderEntity walletOrder = fixture.repository.queryUnpaidOrder(legacyCart);
            assertNotNull(walletOrder);
            assertEquals(walletOrderId, walletOrder.getOrderId());
            assertAmount(GROSS_AMOUNT, walletOrder.getTotalAmount());
        } finally {
            fixture.deleteFixture(openid, legacyOrderId, null);
            fixture.deleteFixture(openid, walletOrderId, productId);
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
        private final JdbcTemplate adminJdbcTemplate;
        private final TransactionTemplate transactionTemplate;
        private final OrderRepository repository;
        private final IOpenAIOrderDao orderDao;
        private final String schemaName;

        private SqlFixture(JdbcTemplate jdbcTemplate,
                           JdbcTemplate adminJdbcTemplate,
                           TransactionTemplate transactionTemplate,
                           OrderRepository repository,
                           IOpenAIOrderDao orderDao,
                           String schemaName) {
            this.jdbcTemplate = jdbcTemplate;
            this.adminJdbcTemplate = adminJdbcTemplate;
            this.transactionTemplate = transactionTemplate;
            this.repository = repository;
            this.orderDao = orderDao;
            this.schemaName = schemaName;
        }

        private static SqlFixture open() throws Exception {
            String configuredUrl = setting(
                    "chat.quota.jdbc.url",
                    "CHAT_QUOTA_JDBC_URL",
                    setting("mysql.url", "MYSQL_URL",
                            "jdbc:mysql://127.0.0.1:3306/openai?useUnicode=true"
                                    + "&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                                    + "&useSSL=false&allowPublicKeyRetrieval=true")
            );
            String username = setting(
                    "chat.quota.jdbc.user",
                    "CHAT_QUOTA_DB_USER",
                    setting("mysql.username", "MYSQL_USERNAME", "root")
            );
            String password = setting(
                    "chat.quota.jdbc.password",
                    "CHAT_QUOTA_DB_PASSWORD",
                    setting("mysql.password", "MYSQL_PASSWORD", "")
            );

            String schemaName = "chatgpt_payment_it_"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            DriverManagerDataSource adminDataSource = new DriverManagerDataSource();
            adminDataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            adminDataSource.setUrl(withDatabase(configuredUrl, "mysql"));
            adminDataSource.setUsername(username);
            adminDataSource.setPassword(password);
            JdbcTemplate adminJdbcTemplate = new JdbcTemplate(adminDataSource);
            adminJdbcTemplate.execute("CREATE DATABASE `" + schemaName
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");

            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setUrl(withDatabase(configuredUrl, schemaName));
            dataSource.setUsername(username);
            dataSource.setPassword(password);
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            createSchema(jdbcTemplate);

            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setMapperLocations(new Resource[]{
                    resolver.getResource("classpath:/mybatis/mapper/openai_order_mapper.xml"),
                    resolver.getResource("classpath:/mybatis/mapper/openai_product_mapper.xml"),
                    resolver.getResource("classpath:/mybatis/mapper/user_account_mapper.xml"),
                    resolver.getResource("classpath:/mybatis/mapper/account_wallet_mapper.xml"),
                    resolver.getResource("classpath:/mybatis/mapper/account_balance_mapper.xml"),
                    resolver.getResource("classpath:/mybatis/mapper/payment_event_outbox_mapper.xml")
            });
            factoryBean.afterPropertiesSet();

            SqlSessionTemplate sqlSession = new SqlSessionTemplate(factoryBean.getObject());
            IOpenAIOrderDao orderDao = sqlSession.getMapper(IOpenAIOrderDao.class);
            IOpenAIProductDao productDao = sqlSession.getMapper(IOpenAIProductDao.class);
            IUserAccountDao userAccountDao = sqlSession.getMapper(IUserAccountDao.class);
            IAccountWalletDao accountWalletDao = sqlSession.getMapper(IAccountWalletDao.class);
            IBalanceGrantDao balanceGrantDao = sqlSession.getMapper(IBalanceGrantDao.class);
            IPaymentEventOutboxDao paymentEventOutboxDao = sqlSession.getMapper(IPaymentEventOutboxDao.class);

            OrderRepository repository = new OrderRepository();
            setField(repository, "openAIOrderDao", orderDao);
            setField(repository, "openAIProductDao", productDao);
            setField(repository, "userAccountDao", userAccountDao);
            setField(repository, "accountWalletDao", accountWalletDao);
            setField(repository, "balanceGrantDao", balanceGrantDao);
            setField(repository, "paymentEventOutboxDao", paymentEventOutboxDao);
            setField(repository, "modelTypes", "wallet-sql-test-model");

            DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
            return new SqlFixture(
                    jdbcTemplate,
                    adminJdbcTemplate,
                    new TransactionTemplate(transactionManager),
                    repository,
                    orderDao,
                    schemaName
            );
        }

        private static void createSchema(JdbcTemplate jdbcTemplate) {
            jdbcTemplate.execute("""
                    CREATE TABLE openai_order (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      openid VARCHAR(128) NOT NULL,
                      product_id INT NOT NULL,
                      product_name VARCHAR(32) NOT NULL,
                      product_credit_amount DECIMAL(20,8) NULL,
                      product_fee_rate DECIMAL(5,4) NULL,
                      product_fee_amount DECIMAL(20,2) NULL,
                      order_id VARCHAR(12) NOT NULL,
                      order_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      order_status TINYINT NOT NULL,
                      total_amount DECIMAL(10,2) NOT NULL,
                      pay_type TINYINT NOT NULL DEFAULT 0,
                      pay_url TEXT NULL,
                      pay_amount DECIMAL(10,2) NULL,
                      transaction_id VARCHAR(32) NULL,
                      pay_status TINYINT NULL,
                      pay_time DATETIME NULL,
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (id), UNIQUE KEY uq_order_id (order_id),
                      KEY idx_order_status_pay_status_order_time (order_time, order_status, pay_status)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE openai_product (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      product_id INT NOT NULL,
                      product_name VARCHAR(32) NOT NULL,
                      product_desc VARCHAR(128) NOT NULL,
                      product_model_types VARCHAR(128) NULL,
                      price DECIMAL(10,2) NOT NULL,
                      credit_amount DECIMAL(20,8) NULL,
                      sort INT NOT NULL,
                      is_enabled TINYINT NOT NULL DEFAULT 1,
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (id), KEY idx_product_id_enabled (product_id, is_enabled)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE user_account (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      openid VARCHAR(64) NOT NULL,
                      model_types VARCHAR(128) NOT NULL,
                      status TINYINT NOT NULL DEFAULT 0,
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (id), UNIQUE KEY uq_openid (openid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE account_wallet (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      openid VARCHAR(64) NOT NULL,
                      currency CHAR(3) NOT NULL DEFAULT 'CNY',
                      available_amount DECIMAL(20,8) NOT NULL DEFAULT 0,
                      total_amount DECIMAL(20,8) NOT NULL DEFAULT 0,
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (id), UNIQUE KEY uq_account_wallet_openid (openid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE balance_grant (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      app_id VARCHAR(64) NOT NULL,
                      request_id VARCHAR(160) NOT NULL,
                      openid VARCHAR(64) NOT NULL,
                      amount DECIMAL(20,8) NOT NULL,
                      source VARCHAR(64) NOT NULL,
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY (id), UNIQUE KEY uq_balance_grant_app_request (app_id, request_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE payment_event_outbox (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      event_type VARCHAR(32) NOT NULL,
                      order_id VARCHAR(64) NOT NULL,
                      status TINYINT NOT NULL DEFAULT 0,
                      send_attempts INT NOT NULL DEFAULT 0,
                      consume_attempts INT NOT NULL DEFAULT 0,
                      next_attempt_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
                      sent_at DATETIME NULL,
                      done_at DATETIME NULL,
                      last_error VARCHAR(512) NULL,
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (id), UNIQUE KEY uk_payment_event_type_order (event_type, order_id),
                      KEY idx_payment_event_ready (status, next_attempt_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        }

        private void insertPaidOrder(String openid,
                                     String orderId,
                                     int productId,
                                     BigDecimal price,
                                     BigDecimal creditAmount) {
            insertPaidOrder(openid, orderId, productId, price, creditAmount, null, null);
        }

        private void insertPaidOrder(String openid,
                                     String orderId,
                                     int productId,
                                     BigDecimal price,
                                     BigDecimal creditAmount,
                                     BigDecimal productFeeRate,
                                     BigDecimal productFeeAmount) {
            OpenAIOrderPO order = OpenAIOrderPO.builder()
                    .openid(openid)
                    .productId(productId)
                    .productName("wallet-it")
                    .productCreditAmount(creditAmount)
                    .productFeeRate(productFeeRate)
                    .productFeeAmount(productFeeAmount)
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

        private void insertUnpaidOrder(String openid,
                                       String orderId,
                                       int productId,
                                       BigDecimal price,
                                       BigDecimal creditAmount,
                                       BigDecimal productFeeRate,
                                       BigDecimal productFeeAmount) {
            OpenAIOrderPO order = OpenAIOrderPO.builder()
                    .openid(openid)
                    .productId(productId)
                    .productName("wallet-it")
                    .productCreditAmount(creditAmount)
                    .productFeeRate(productFeeRate)
                    .productFeeAmount(productFeeAmount)
                    .orderId(orderId)
                    .orderTime(new Date())
                    .orderStatus(0)
                    .totalAmount(price)
                    .payType(PayTypeVO.ALIPAY_SANDBOX.getCode())
                    .payStatus(0)
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
                                + "price, credit_amount, sort, is_enabled) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        productId, "Wallet SQL Test", "Wallet SQL fixture", "wallet-sql-test-model",
                        price, creditAmount, 999, 1
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

        private void saveOrder(CreateOrderAggregate aggregate) {
            transactionTemplate.execute(status -> {
                repository.saveOrder(aggregate);
                return null;
            });
        }

        private boolean markPaid(String orderId, String transactionId, BigDecimal amount) {
            Boolean changed = transactionTemplate.execute(status ->
                    repository.changeOrderPaySuccess(orderId, transactionId, amount, new Date()));
            return Boolean.TRUE.equals(changed);
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
                jdbcTemplate.update("DELETE FROM payment_event_outbox WHERE order_id = ?", orderId);
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

        private static String withDatabase(String jdbcUrl, String database) {
            int queryStart = jdbcUrl.indexOf('?');
            String query = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
            String base = queryStart < 0 ? jdbcUrl : jdbcUrl.substring(0, queryStart);
            int slash = base.lastIndexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("JDBC URL must contain a database path");
            }
            return base.substring(0, slash + 1) + database + query;
        }

        @Override
        public void close() {
            // The test owns this schema and drops it after every test class.
            adminJdbcTemplate.execute("DROP DATABASE IF EXISTS `" + schemaName + "`");
        }
    }
}
