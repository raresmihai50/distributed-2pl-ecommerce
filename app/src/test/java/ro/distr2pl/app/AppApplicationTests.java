package ro.distr2pl.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ro.distr2pl.app.concurrency.DeadlockDetector;
import ro.distr2pl.app.concurrency.LockManager;
import ro.distr2pl.app.concurrency.LockType;
import ro.distr2pl.app.dto.CreateProductRequest;
import ro.distr2pl.app.dto.CreateUserRequest;
import ro.distr2pl.app.dto.PlaceOrderRequest;
import ro.distr2pl.app.model.DatabaseType;
import ro.distr2pl.app.model.Inventory;
import ro.distr2pl.app.model.Payment;
import ro.distr2pl.app.model.Product;
import ro.distr2pl.app.model.User;
import ro.distr2pl.app.repository.mysql.OrderItemRepository;
import ro.distr2pl.app.repository.mysql.PaymentRepository;
import ro.distr2pl.app.repository.postgres.InventoryRepository;
import ro.distr2pl.app.repository.postgres.OrderRepository;
import ro.distr2pl.app.repository.postgres.ProductRepository;
import ro.distr2pl.app.repository.postgres.UserRepository;
import ro.distr2pl.app.service.EcommerceService;
import ro.distr2pl.app.service.SimulationService;
import ro.distr2pl.app.transaction.SqlOperation;
import ro.distr2pl.app.transaction.TransactionContext;
import ro.distr2pl.app.transaction.TransactionManager;
import ro.distr2pl.app.transaction.TwoPhaseCommitJournal;
import ro.distr2pl.app.transaction.UndoLogEntry;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive Automated JUnit 5 Test Suite verifying all 8 required Concurrency,
 * Strict 2PL, Wait-For Graph Deadlock Detection, UndoLog Rollback, and Two-Phase Commit (2PC) scenarios.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppApplicationTests {

    @Autowired
    private EcommerceService ecommerceService;

    @Autowired
    private SimulationService simulationService;

    @Autowired
    private TransactionManager transactionManager;

    @Autowired
    private LockManager lockManager;

    @Autowired
    private DeadlockDetector deadlockDetector;

    @Autowired
    private TwoPhaseCommitJournal twoPhaseCommitJournal;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @BeforeEach
    void ensureCleanLocks() {
        assertTrue(lockManager.getActiveLocksSnapshot().isEmpty(),
                "Strict 2PL violation: No locks should remain held between completed transactions");
    }

    // =========================================================================
    // TEST 1: Two concurrent transactions try to buy the last available product
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("Test 1: Two transactions simultaneously buy the last available product -> only 1 succeeds, stock = 0")
    void testLastProductConcurrencyRace() throws Exception {
        // Set Product #4 stock to 1
        Map<String, Object> result = simulationService.simulateLastProductRace(4, 1, 2);

        assertEquals(1, result.get("committedTransactions"), "Exactly 1 transaction must commit");
        assertEquals(1, result.get("abortedTransactions"), "Exactly 1 transaction must abort due to insufficient stock");
        assertEquals(0, result.get("finalStock"), "Final stock must be 0 and never negative");
    }

    // =========================================================================
    // TEST 2: Two transactions simultaneously modify the same resource under Strict 2PL
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("Test 2: Two concurrent transactions modifying the same resource serialize strictly under Strict 2PL")
    void testConcurrentModificationRespectsStrict2PL() throws Exception {
        Product product = ecommerceService.addProduct(new CreateProductRequest(
                "Strict2PL Test Monitor", "Concurrency test item", new BigDecimal("100.00"), "Displays", 10));
        int productId = product.getId();

        CountDownLatch t1HasLock = new CountDownLatch(1);
        CountDownLatch t2StartedWaiting = new CountDownLatch(1);
        List<String> executionTimeline = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> f1 = executor.submit(() -> {
            transactionManager.execute("T1_MODIFY_PRICE", ctx -> {
                productRepository.updatePrice(ctx, productId, new BigDecimal("150.00"));
                executionTimeline.add("T1_UPDATED_TO_150");
                t1HasLock.countDown();

                // Hold Strict 2PL lock while T2 attempts to acquire X-lock on the same product
                t2StartedWaiting.await(3, TimeUnit.SECONDS);
                Thread.sleep(150);
                executionTimeline.add("T1_ABOUT_TO_COMMIT");
                return null;
            });
            executionTimeline.add("T1_COMMITTED");
        });

        Future<?> f2 = executor.submit(() -> {
            try {
                t1HasLock.await(5, TimeUnit.SECONDS);
                t2StartedWaiting.countDown();
                // T2 requests X-lock on the same product -> must block until T1 commits and releases lock!
                transactionManager.execute("T2_MODIFY_PRICE", ctx -> {
                    ctx.lockExclusive(DatabaseType.POSTGRESQL, "products", productId);
                    executionTimeline.add("T2_ACQUIRED_LOCK_AFTER_T1");
                    Product p = productRepository.findById(ctx, productId).orElseThrow();
                    assertEquals(0, p.getPrice().compareTo(new BigDecimal("150.00")),
                            "T2 must see T1's committed price of 150.00");
                    productRepository.updatePrice(ctx, productId, p.getPrice().add(new BigDecimal("50.00")));
                    return null;
                });
                executionTimeline.add("T2_COMMITTED");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        // Verify T2 only acquired the lock AFTER T1 was about to commit / released its lock
        int idxT1Commit = executionTimeline.indexOf("T1_ABOUT_TO_COMMIT");
        int idxT2Acquire = executionTimeline.indexOf("T2_ACQUIRED_LOCK_AFTER_T1");
        assertTrue(idxT1Commit < idxT2Acquire,
                "Strict 2PL violation: T2 acquired lock before T1 finished! Timeline: " + executionTimeline);

        Product finalProduct = productRepository.findByIdDirect(productId).orElseThrow();
        assertEquals(0, finalProduct.getPrice().compareTo(new BigDecimal("200.00")));
    }

    // =========================================================================
    // TEST 3: Deadlock simulation (T1 locks A -> wants B, T2 locks B -> wants A)
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("Test 3: Wait-For Graph detects cycle (T1 -> T2 -> T1), aborts victim via UndoLog, survivor commits")
    void testDeadlockDetectionAndResolution() throws Exception {
        int deadlocksBefore = deadlockDetector.getDeadlockHistory().size();

        Map<String, Object> result = simulationService.simulateDeadlock(1, 2);

        assertEquals(1, result.get("committedTransactions"), "Survivor transaction must commit");
        assertEquals(1, result.get("deadlockAbortedTransactions"), "Victim transaction must be aborted and rolled back");
        assertTrue(deadlockDetector.getDeadlockHistory().size() > deadlocksBefore,
                "DeadlockDetector must record the detected Wait-For Graph cycle");
        assertTrue(lockManager.getActiveLocksSnapshot().isEmpty(),
                "All locks must be released after deadlock resolution");
    }

    // =========================================================================
    // TEST 4: Distributed Rollback simulation across PostgreSQL and MySQL
    // =========================================================================
    @Test
    @Order(4)
    @DisplayName("Test 4: Distributed Rollback restores modified records in both PostgreSQL and MySQL via UndoLog")
    void testDistributedRollbackRestoresBothDatabases() throws Exception {
        Inventory invBefore = inventoryRepository.findByProductIdDirect(1).orElseThrow();
        int ordersBefore = orderRepository.findAllDirect().size();
        int orderItemsBefore = orderItemRepository.findAllDirect().size();
        int paymentsBefore = paymentRepository.findAllDirect().size();

        PlaceOrderRequest failingRequest = new PlaceOrderRequest(
                1, "CARD", List.of(new PlaceOrderRequest.OrderItemDto(1, 2)));
        failingRequest.setSimulateFailureAfterWrites(true);

        assertThrows(Exception.class, () -> ecommerceService.placeOrder(failingRequest),
                "Order with simulated failure after writes must throw and trigger RollbackManager");

        Inventory invAfter = inventoryRepository.findByProductIdDirect(1).orElseThrow();
        int ordersAfter = orderRepository.findAllDirect().size();
        int orderItemsAfter = orderItemRepository.findAllDirect().size();
        int paymentsAfter = paymentRepository.findAllDirect().size();

        assertEquals(invBefore.getQuantity(), invAfter.getQuantity(),
                "PostgreSQL inventory quantity must be restored by UndoLog");
        assertEquals(ordersBefore, ordersAfter,
                "PostgreSQL created order row must be deleted by UndoLog");
        assertEquals(orderItemsBefore, orderItemsAfter,
                "MySQL created order_items rows must be deleted by UndoLog");
        assertEquals(paymentsBefore, paymentsAfter,
                "MySQL created payment row must be deleted by UndoLog");

        // Also test 2PC Phase 1 Prepare failure rollback
        PlaceOrderRequest prepareFailRequest = new PlaceOrderRequest(
                1, "CARD", List.of(new PlaceOrderRequest.OrderItemDto(1, 1)));
        prepareFailRequest.setSimulateMysqlPrepareFailure(true);

        assertThrows(Exception.class, () -> ecommerceService.placeOrder(prepareFailRequest),
                "2PC Phase 1 Prepare failure on MySQL must trigger global abort and UndoLog rollback");

        assertEquals(invBefore.getQuantity(), inventoryRepository.findByProductIdDirect(1).orElseThrow().getQuantity(),
                "Stock must remain untouched after 2PC Phase 1 abort");
    }

    // =========================================================================
    // TEST 5: Successful Distributed Commit across PostgreSQL & MySQL (All 8 Operations)
    // =========================================================================
    @Test
    @Order(5)
    @DisplayName("Test 5: Distributed 2PC Commit persists changes across PostgreSQL and MySQL for all E-Commerce operations")
    void testDistributedCommitAndAllEightOperations() throws Exception {
        // Op 1: Create User
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 6);
        User user = ecommerceService.createUser(new CreateUserRequest(
                "user_" + uniqueSuffix, "u_" + uniqueSuffix + "@test.ro", "Test User", new BigDecimal("5000.00")));
        assertNotNull(user.getId());

        // Op 2: Add Product
        Product product = ecommerceService.addProduct(new CreateProductRequest(
                "SSD NVMe 2TB " + uniqueSuffix, "Fast storage", new BigDecimal("200.00"), "Storage", 10));
        assertNotNull(product.getId());

        // Op 3: Update Product Price
        Product updatedProduct = ecommerceService.updateProductPrice(product.getId(), new BigDecimal("250.00"));
        assertEquals(0, updatedProduct.getPrice().compareTo(new BigDecimal("250.00")));

        // Op 4: Check Product Stock
        Inventory stock = ecommerceService.checkStock(product.getId());
        assertEquals(10, stock.getQuantity());

        // Op 5: Place Order (Distributed: PG + MySQL)
        ro.distr2pl.app.model.Order order = ecommerceService.placeOrder(new PlaceOrderRequest(
                user.getId(), "CARD", List.of(new PlaceOrderRequest.OrderItemDto(product.getId(), 2))));
        assertEquals("CONFIRMED", order.getStatus());
        assertEquals(8, ecommerceService.checkStock(product.getId()).getQuantity());

        // Op 6: Process Payment (Distributed: PG + MySQL)
        Payment payment = ecommerceService.processPayment(order.getId());
        assertEquals("PAID", payment.getStatus());
        User userAfterPay = userRepository.findByIdDirect(user.getId()).orElseThrow();
        assertEquals(0, userAfterPay.getBalance().compareTo(new BigDecimal("4500.00")));

        // Op 8: Refund Payment & Restock (Distributed: PG + MySQL)
        Payment refunded = ecommerceService.refundPaymentAndRestock(order.getId());
        assertEquals("REFUNDED", refunded.getStatus());
        assertEquals(10, ecommerceService.checkStock(product.getId()).getQuantity());
        User userAfterRefund = userRepository.findByIdDirect(user.getId()).orElseThrow();
        assertEquals(0, userAfterRefund.getBalance().compareTo(new BigDecimal("5000.00")));

        // Op 7: Cancel an unpaid Order (Distributed: PG + MySQL)
        ro.distr2pl.app.model.Order order2 = ecommerceService.placeOrder(new PlaceOrderRequest(
                user.getId(), "CARD", List.of(new PlaceOrderRequest.OrderItemDto(product.getId(), 3))));
        assertEquals(7, ecommerceService.checkStock(product.getId()).getQuantity());
        ro.distr2pl.app.model.Order cancelled = ecommerceService.cancelOrder(order2.getId());
        assertEquals("CANCELLED", cancelled.getStatus());
        assertEquals(10, ecommerceService.checkStock(product.getId()).getQuantity());
    }

    // =========================================================================
    // TEST 6: High Concurrency - Multiple concurrent distributed transactions
    // =========================================================================
    @Test
    @Order(6)
    @DisplayName("Test 6: 8 concurrent distributed transactions competing for 5 items in stock -> 5 commit, 3 abort, stock = 0")
    void testMultipleConcurrentDistributedTransactions() throws Exception {
        Product product = ecommerceService.addProduct(new CreateProductRequest(
                "Concurrent Stress GPU", "Stress test product", new BigDecimal("100.00"), "Hardware", 5));

        int totalThreads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch ready = new CountDownLatch(totalThreads);
        CountDownLatch start = new CountDownLatch(1);

        AtomicInteger committed = new AtomicInteger(0);
        AtomicInteger aborted = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < totalThreads; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    ecommerceService.placeOrder(new PlaceOrderRequest(
                            1, "CARD", List.of(new PlaceOrderRequest.OrderItemDto(product.getId(), 1))));
                    committed.incrementAndGet();
                } catch (Exception e) {
                    aborted.incrementAndGet();
                }
            }));
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertEquals(5, committed.get(), "Exactly 5 orders should commit for 5 available items");
        assertEquals(3, aborted.get(), "Exactly 3 orders should abort when stock reaches 0");
        assertEquals(0, ecommerceService.checkStock(product.getId()).getQuantity(), "Final stock must be 0");
    }

    // =========================================================================
    // TEST 7: Verification of Isolation - No Dirty Reads, No Lost Updates, No Inconsistent Reads
    // =========================================================================
    @Test
    @Order(7)
    @DisplayName("Test 7: Strict 2PL prevents Dirty Reads, Lost Updates, and Inconsistent (Non-Repeatable) Reads")
    void testNoDirtyReadsNoLostUpdatesNoInconsistentReads() throws Exception {
        Product product = ecommerceService.addProduct(new CreateProductRequest(
                "Isolation Verification Item", "Isolation test", new BigDecimal("300.00"), "Test", 20));
        int productId = product.getId();

        // Part A: No Dirty Reads (T1 modifies price to 999.00 and then aborts; T2 reading concurrently must NEVER see 999.00)
        CountDownLatch t1ModifiedUncommitted = new CountDownLatch(1);
        AtomicReference<BigDecimal> priceReadByT2 = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> writerAborts = executor.submit(() -> {
            assertThrows(RuntimeException.class, () ->
                    transactionManager.execute("DIRTY_WRITE_TX", ctx -> {
                        productRepository.updatePrice(ctx, productId, new BigDecimal("999.00"));
                        t1ModifiedUncommitted.countDown();
                        Thread.sleep(200);
                        throw new RuntimeException("Abort writer transaction! UndoLog must restore 300.00 before releasing X-lock");
                    })
            );
        });

        Future<?> readerTx = executor.submit(() -> {
            try {
                t1ModifiedUncommitted.await(5, TimeUnit.SECONDS);
                // Reader requests S-lock on product -> blocked by T1's X-lock until T1 finishes UndoLog rollback!
                BigDecimal readVal = transactionManager.execute("CLEAN_READ_TX", ctx ->
                        productRepository.findById(ctx, productId).orElseThrow().getPrice()
                );
                priceReadByT2.set(readVal);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        writerAborts.get(10, TimeUnit.SECONDS);
        readerTx.get(10, TimeUnit.SECONDS);

        assertEquals(0, priceReadByT2.get().compareTo(new BigDecimal("300.00")),
                "Dirty Read detected! T2 saw uncommitted/aborted value instead of 300.00");

        // Part B: No Inconsistent (Non-Repeatable) Reads (T1 reads price twice while holding S-lock; T2 cannot modify in between)
        CountDownLatch t1FirstReadDone = new CountDownLatch(1);
        Future<?> repeatableReader = executor.submit(() -> {
            transactionManager.execute("REPEATABLE_READ_TX", ctx -> {
                BigDecimal firstRead = productRepository.findById(ctx, productId).orElseThrow().getPrice();
                t1FirstReadDone.countDown();
                Thread.sleep(200);
                BigDecimal secondRead = productRepository.findById(ctx, productId).orElseThrow().getPrice();
                assertEquals(0, firstRead.compareTo(secondRead),
                        "Inconsistent Read detected! First read=" + firstRead + ", second read=" + secondRead);
                return null;
            });
        });

        Future<?> concurrentWriter = executor.submit(() -> {
            try {
                t1FirstReadDone.await(5, TimeUnit.SECONDS);
                // T2 tries to update price to 400.00, must wait until T1's S-lock is released at T1 commit
                ecommerceService.updateProductPrice(productId, new BigDecimal("400.00"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        repeatableReader.get(10, TimeUnit.SECONDS);
        concurrentWriter.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(0, productRepository.findByIdDirect(productId).orElseThrow().getPrice().compareTo(new BigDecimal("400.00")));
    }

    // =========================================================================
    // TEST 8: 2PC Coordinator Crash Recovery (Presumed Abort & Forward Commit)
    // =========================================================================
    @Test
    @Order(8)
    @DisplayName("Test 8: 2PC Coordinator Journal recovers both in-doubt PREPARING (Undo) and GLOBAL_COMMIT (Redo) transactions")
    void testTwoPhaseCommitCrashRecovery() throws Exception {
        Product product = ecommerceService.addProduct(new CreateProductRequest(
                "2PC Recovery Product", "Recovery test", new BigDecimal("100.00"), "Recovery", 10));
        int productId = product.getId();

        // Case A: Simulate a crash in PREPARE_PHASE_1 (before GLOBAL_COMMIT) after inventory was modified from 10 -> 7
        try (java.sql.Connection conn = productRepository != null
                ? transactionManager.begin("TEMP").getPostgresConnection() : null) {
            if (conn != null) {
                try (java.sql.PreparedStatement ps = conn.prepareStatement("UPDATE inventory SET quantity = 7 WHERE product_id = ?")) {
                    ps.setInt(1, productId);
                    ps.executeUpdate();
                }
            }
        }
        // Release any temp lock
        lockManager.getActiveLocksSnapshot().forEach(l -> l.getOwners().forEach(lockManager::releaseAllLocks));

        String crashedPrepareTxId = "TX-CRASH-PREPARE-" + UUID.randomUUID().toString().substring(0, 4);
        UndoLogEntry undoEntry = new UndoLogEntry(
                1, DatabaseType.POSTGRESQL, "inventory", String.valueOf(productId),
                "quantity", "10", "7", "UPDATE",
                "UPDATE inventory SET quantity = ? WHERE product_id = ?",
                List.of(10, productId)
        );
        twoPhaseCommitJournal.appendRecord(crashedPrepareTxId, "CRASHED_ORDER", "PREPARE_PHASE_1",
                List.of(undoEntry), Collections.emptyList());

        // Case B: Simulate a crash in Phase 2 AFTER GLOBAL_COMMIT ("POSTGRES_COMMITTED") with a pending MySQL redo
        String crashedCommitTxId = "TX-CRASH-COMMIT-" + UUID.randomUUID().toString().substring(0, 4);
        SqlOperation pendingMysqlInsert = new SqlOperation(
                1, DatabaseType.MYSQL, "INSERT", "payments", "99999",
                "INSERT INTO payments (order_id, user_id, amount, payment_method, status, transaction_id) VALUES (?, ?, ?, ?, ?, ?)",
                List.of(9999, 1, new BigDecimal("100.00"), "CARD", "RECOVERED_PAID", crashedCommitTxId)
        );
        twoPhaseCommitJournal.appendRecord(crashedCommitTxId, "PARTIAL_COMMIT_ORDER", "GLOBAL_COMMIT",
                Collections.emptyList(), List.of(pendingMysqlInsert));
        twoPhaseCommitJournal.appendRecord(crashedCommitTxId, "PARTIAL_COMMIT_ORDER", "POSTGRES_COMMITTED",
                Collections.emptyList(), List.of(pendingMysqlInsert));

        // Trigger Coordinator Recovery (simulating application restart)
        List<String> recoveryActions = twoPhaseCommitJournal.recoverInDoubtTransactions();

        assertTrue(recoveryActions.size() >= 2, "Recovery must resolve both in-doubt transactions: " + recoveryActions);

        // Verify Case A was rolled back to stock = 10 via UndoLog
        Inventory recoveredInv = inventoryRepository.findByProductIdDirect(productId).orElseThrow();
        assertEquals(10, recoveredInv.getQuantity(),
                "In-doubt transaction without GLOBAL_COMMIT must be rolled back to 10 via UndoLog");

        // Verify Case B was forward-committed to MySQL payments table
        Payment recoveredPayment = paymentRepository.findByOrderIdDirect(9999).orElse(null);
        assertNotNull(recoveredPayment, "In-doubt transaction with GLOBAL_COMMIT must be forward-recovered in MySQL");
        assertEquals("RECOVERED_PAID", recoveredPayment.getStatus());
    }
}
