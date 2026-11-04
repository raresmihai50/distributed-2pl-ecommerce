package ro.distr2pl.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.distr2pl.app.dto.PlaceOrderRequest;
import ro.distr2pl.app.model.Inventory;
import ro.distr2pl.app.model.Order;
import ro.distr2pl.app.model.Product;
import ro.distr2pl.app.repository.postgres.InventoryRepository;
import ro.distr2pl.app.repository.postgres.ProductRepository;
import ro.distr2pl.app.transaction.TransactionContext;
import ro.distr2pl.app.transaction.TransactionManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides interactive concurrency, deadlock, rollback, and 2PC simulations
 * that can be triggered from the Web UI or REST API to demonstrate the system's guarantees.
 */
@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final EcommerceService ecommerceService;
    private final TransactionManager transactionManager;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    public SimulationService(EcommerceService ecommerceService,
                             TransactionManager transactionManager,
                             ProductRepository productRepository,
                             InventoryRepository inventoryRepository) {
        this.ecommerceService = ecommerceService;
        this.transactionManager = transactionManager;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Scenario 1: Two concurrent transactions try to buy the last available unit of a product.
     * Guarantees: Exactly 1 succeeds, 1 aborts, and stock becomes 0 (never negative).
     */
    public Map<String, Object> simulateLastProductRace(int productId, int userId1, int userId2) throws Exception {
        // Ensure stock is set to 1 before starting the race
        transactionManager.execute("RESET_STOCK_TO_1", ctx -> {
            inventoryRepository.updateQuantity(ctx, productId, 1);
            return null;
        });

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch readyLatch = new CountDownLatch(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger committedCount = new AtomicInteger(0);
        AtomicInteger abortedCount = new AtomicInteger(0);
        List<String> logs = new ArrayList<>();

        CallableTask task1 = () -> {
            readyLatch.countDown();
            startLatch.await(5, TimeUnit.SECONDS);
            try {
                PlaceOrderRequest req = new PlaceOrderRequest(userId1, "CARD",
                        List.of(new PlaceOrderRequest.OrderItemDto(productId, 1)));
                Order o = ecommerceService.placeOrder(req);
                committedCount.incrementAndGet();
                return "User " + userId1 + " COMMITTED Order #" + o.getId();
            } catch (Exception e) {
                abortedCount.incrementAndGet();
                return "User " + userId1 + " ABORTED: " + e.getMessage();
            }
        };

        CallableTask task2 = () -> {
            readyLatch.countDown();
            startLatch.await(5, TimeUnit.SECONDS);
            try {
                PlaceOrderRequest req = new PlaceOrderRequest(userId2, "CARD",
                        List.of(new PlaceOrderRequest.OrderItemDto(productId, 1)));
                Order o = ecommerceService.placeOrder(req);
                committedCount.incrementAndGet();
                return "User " + userId2 + " COMMITTED Order #" + o.getId();
            } catch (Exception e) {
                abortedCount.incrementAndGet();
                return "User " + userId2 + " ABORTED: " + e.getMessage();
            }
        };

        Future<String> f1 = executor.submit(task1::call);
        Future<String> f2 = executor.submit(task2::call);

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        logs.add(f1.get(15, TimeUnit.SECONDS));
        logs.add(f2.get(15, TimeUnit.SECONDS));
        executor.shutdownNow();

        Inventory finalInv = inventoryRepository.findByProductIdDirect(productId).orElseThrow();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", "LAST_PRODUCT_CONCURRENCY_RACE");
        result.put("productId", productId);
        result.put("committedTransactions", committedCount.get());
        result.put("abortedTransactions", abortedCount.get());
        result.put("finalStock", finalInv.getQuantity());
        result.put("outcomes", logs);
        return result;
    }

    /**
     * Scenario 2: Classic Deadlock Simulation between two concurrent distributed transactions.
     * - T1 acquires X-lock on Product A (productId1), then requests X-lock on Product B (productId2).
     * - T2 acquires X-lock on Product B (productId2), then requests X-lock on Product A (productId1).
     * - Wait-For Graph detects cycle T1 -> T2 -> T1, selects victim, rolls back victim via UndoLog,
     *   and allows survivor transaction to complete and commit.
     */
    public Map<String, Object> simulateDeadlock(int productId1, int productId2) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstLocksAcquired = new CountDownLatch(2);

        Product p1Before = productRepository.findByIdDirect(productId1).orElseThrow();
        Product p2Before = productRepository.findByIdDirect(productId2).orElseThrow();

        AtomicInteger committedCount = new AtomicInteger(0);
        AtomicInteger deadlockAbortedCount = new AtomicInteger(0);
        List<String> outcomes = new ArrayList<>();

        Future<String> f1 = executor.submit(() -> {
            TransactionContext ctx = transactionManager.begin("DEADLOCK_SIM_TX1");
            try {
                // Step 1: T1 modifies Product A (acquires X-lock on POSTGRESQL:products:productId1)
                productRepository.updatePrice(ctx, productId1, p1Before.getPrice().add(new BigDecimal("10.00")));
                firstLocksAcquired.countDown();
                firstLocksAcquired.await(5, TimeUnit.SECONDS);
                Thread.sleep(50);

                // Step 2: T1 requests X-lock on Product B (held by T2!)
                productRepository.updatePrice(ctx, productId2, p2Before.getPrice().add(new BigDecimal("10.00")));

                // Restore original prices before committing so repeated runs stay clean
                productRepository.updatePrice(ctx, productId1, p1Before.getPrice());
                productRepository.updatePrice(ctx, productId2, p2Before.getPrice());

                transactionManager.commit(ctx);
                committedCount.incrementAndGet();
                return "T1 [" + ctx.getTransactionId() + "] SURVIVED and COMMITTED";
            } catch (Exception e) {
                transactionManager.rollback(ctx, e.getMessage());
                deadlockAbortedCount.incrementAndGet();
                return "T1 [" + ctx.getTransactionId() + "] SELECTED AS VICTIM & ROLLED BACK: " + e.getMessage();
            }
        });

        Future<String> f2 = executor.submit(() -> {
            // Slight stagger so T2 has a more recent timestamp (deterministic youngest victim)
            Thread.sleep(15);
            TransactionContext ctx = transactionManager.begin("DEADLOCK_SIM_TX2");
            try {
                // Step 1: T2 modifies Product B (acquires X-lock on POSTGRESQL:products:productId2)
                productRepository.updatePrice(ctx, productId2, p2Before.getPrice().add(new BigDecimal("20.00")));
                firstLocksAcquired.countDown();
                firstLocksAcquired.await(5, TimeUnit.SECONDS);
                Thread.sleep(50);

                // Step 2: T2 requests X-lock on Product A (held by T1!) -> DEADLOCK!
                productRepository.updatePrice(ctx, productId1, p1Before.getPrice().add(new BigDecimal("20.00")));

                productRepository.updatePrice(ctx, productId2, p2Before.getPrice());
                productRepository.updatePrice(ctx, productId1, p1Before.getPrice());

                transactionManager.commit(ctx);
                committedCount.incrementAndGet();
                return "T2 [" + ctx.getTransactionId() + "] SURVIVED and COMMITTED";
            } catch (Exception e) {
                transactionManager.rollback(ctx, e.getMessage());
                deadlockAbortedCount.incrementAndGet();
                return "T2 [" + ctx.getTransactionId() + "] SELECTED AS VICTIM & ROLLED BACK: " + e.getMessage();
            }
        });

        outcomes.add(f1.get(15, TimeUnit.SECONDS));
        outcomes.add(f2.get(15, TimeUnit.SECONDS));
        executor.shutdownNow();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", "WAIT_FOR_GRAPH_DEADLOCK");
        result.put("committedTransactions", committedCount.get());
        result.put("deadlockAbortedTransactions", deadlockAbortedCount.get());
        result.put("outcomes", outcomes);
        return result;
    }

    /**
     * Scenario 3: Distributed Rollback Simulation across PostgreSQL and MySQL.
     */
    public Map<String, Object> simulateDistributedRollback(int userId, int productId) throws Exception {
        Inventory invBefore = inventoryRepository.findByProductIdDirect(productId).orElseThrow();
        int ordersBefore = ecommerceService.getAllOrders().size();
        int paymentsBefore = ecommerceService.getAllPayments().size();

        PlaceOrderRequest req = new PlaceOrderRequest(userId, "CARD",
                List.of(new PlaceOrderRequest.OrderItemDto(productId, 1)));
        req.setSimulateFailureAfterWrites(true);

        String abortMessage = null;
        try {
            ecommerceService.placeOrder(req);
        } catch (Exception e) {
            abortMessage = e.getMessage();
        }

        Inventory invAfter = inventoryRepository.findByProductIdDirect(productId).orElseThrow();
        int ordersAfter = ecommerceService.getAllOrders().size();
        int paymentsAfter = ecommerceService.getAllPayments().size();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", "DISTRIBUTED_ROLLBACK_UNDO_LOG");
        result.put("stockBefore", invBefore.getQuantity());
        result.put("stockAfterRollback", invAfter.getQuantity());
        result.put("postgresOrdersBefore", ordersBefore);
        result.put("postgresOrdersAfterRollback", ordersAfter);
        result.put("mysqlPaymentsBefore", paymentsBefore);
        result.put("mysqlPaymentsAfterRollback", paymentsAfter);
        result.put("restoredCleanly", invBefore.getQuantity().equals(invAfter.getQuantity())
                && ordersBefore == ordersAfter && paymentsBefore == paymentsAfter);
        result.put("abortReason", abortMessage);
        return result;
    }

    @FunctionalInterface
    private interface CallableTask {
        String call() throws Exception;
    }
}

