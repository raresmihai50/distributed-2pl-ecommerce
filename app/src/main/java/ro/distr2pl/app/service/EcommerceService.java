package ro.distr2pl.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ro.distr2pl.app.dto.CreateProductRequest;
import ro.distr2pl.app.dto.CreateUserRequest;
import ro.distr2pl.app.dto.PlaceOrderRequest;
import ro.distr2pl.app.model.Inventory;
import ro.distr2pl.app.model.Order;
import ro.distr2pl.app.model.OrderItem;
import ro.distr2pl.app.model.Payment;
import ro.distr2pl.app.model.Product;
import ro.distr2pl.app.model.User;
import ro.distr2pl.app.repository.mysql.OrderItemRepository;
import ro.distr2pl.app.repository.mysql.PaymentRepository;
import ro.distr2pl.app.repository.postgres.InventoryRepository;
import ro.distr2pl.app.repository.postgres.OrderRepository;
import ro.distr2pl.app.repository.postgres.ProductRepository;
import ro.distr2pl.app.repository.postgres.UserRepository;
import ro.distr2pl.app.transaction.TransactionManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Core Business Layer Service implementing all 8 required E-Commerce use cases.
 *
 * All operations execute through the custom {@link TransactionManager}, enforcing
 * Strict Two-Phase Locking (Strict 2PL), Wait-For Graph Deadlock Detection,
 * Before-Image Undo Log Rollback, and Two-Phase Commit (2PC).
 */
@Service
public class EcommerceService {

    private static final Logger log = LoggerFactory.getLogger(EcommerceService.class);

    private final TransactionManager transactionManager;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;

    public EcommerceService(TransactionManager transactionManager,
                            UserRepository userRepository,
                            ProductRepository productRepository,
                            InventoryRepository inventoryRepository,
                            OrderRepository orderRepository,
                            OrderItemRepository orderItemRepository,
                            PaymentRepository paymentRepository) {
        this.transactionManager = transactionManager;
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.paymentRepository = paymentRepository;
    }

    // =========================================================================
    // OPERATION 1: Create User (PostgreSQL)
    // =========================================================================
    public User createUser(CreateUserRequest request) {
        return transactionManager.execute("CREATE_USER", ctx -> {
            BigDecimal initialBalance = request.getBalance() != null ? request.getBalance() : new BigDecimal("1000.00");
            return userRepository.create(ctx, request.getUsername(), request.getEmail(), request.getFullName(), initialBalance);
        });
    }

    // =========================================================================
    // OPERATION 2: Add Product + Initial Inventory (PostgreSQL)
    // =========================================================================
    public Product addProduct(CreateProductRequest request) {
        return transactionManager.execute("ADD_PRODUCT", ctx -> {
            Product product = productRepository.create(
                    ctx,
                    request.getName(),
                    request.getDescription(),
                    request.getPrice(),
                    request.getCategory()
            );
            int qty = Math.max(0, request.getInitialQuantity());
            inventoryRepository.create(ctx, product.getId(), qty);
            product.setAvailableQuantity(qty);
            return product;
        });
    }

    // =========================================================================
    // OPERATION 3: Update Product Price (PostgreSQL under Strict 2PL X-Lock)
    // =========================================================================
    public Product updateProductPrice(int productId, BigDecimal newPrice) {
        return transactionManager.execute("UPDATE_PRODUCT_PRICE", ctx ->
                productRepository.updatePrice(ctx, productId, newPrice)
        );
    }

    // =========================================================================
    // OPERATION 4: Check Product Stock (PostgreSQL under Strict 2PL S-Lock)
    // =========================================================================
    public Inventory checkStock(int productId) {
        return transactionManager.execute("CHECK_PRODUCT_STOCK", ctx -> {
            Product product = productRepository.findById(ctx, productId)
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
            Inventory inv = inventoryRepository.findByProductId(ctx, productId)
                    .orElseThrow(() -> new IllegalArgumentException("Inventory not found for product: " + productId));
            inv.setProductName(product.getName());
            return inv;
        });
    }

    // =========================================================================
    // OPERATION 5: Place Order (DISTRIBUTED TRANSACTION: PostgreSQL + MySQL)
    // =========================================================================
    /**
     * Executes a full Distributed Transaction across PostgreSQL and MySQL:
     * 1. SELECT user in PostgreSQL (S-Lock)
     * 2. SELECT product & inventory in PostgreSQL (S-Lock)
     * 3. UPDATE inventory quantity in PostgreSQL (X-Lock + UndoLog)
     * 4. INSERT order in PostgreSQL with status PENDING (X-Lock + UndoLog)
     * 5. INSERT order_items in MySQL (X-Lock + UndoLog)
     * 6. INSERT payment in MySQL with status PENDING (X-Lock + UndoLog)
     * 7. UPDATE order status in PostgreSQL to CONFIRMED (X-Lock + UndoLog)
     * 8. COMMIT via Two-Phase Commit (2PC) or ROLLBACK via UndoLog on any failure
     */
    public Order placeOrder(PlaceOrderRequest request) {
        return transactionManager.execute("PLACE_ORDER", ctx -> {
            if (request.getItems() == null || request.getItems().isEmpty()) {
                throw new IllegalArgumentException("Order must contain at least one item");
            }
            ctx.setSimulateMysqlPrepareFailure(request.isSimulateMysqlPrepareFailure());

            // 1. Verify User exists in PostgreSQL (acquires SHARED lock on POSTGRESQL:users:{userId})
            User user = userRepository.findById(ctx, request.getUserId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found with id: " + request.getUserId()));

            // Sort items by productId to maintain deterministic lock ordering for multi-item orders
            List<PlaceOrderRequest.OrderItemDto> sortedItems = new ArrayList<>(request.getItems());
            sortedItems.sort(Comparator.comparingInt(PlaceOrderRequest.OrderItemDto::getProductId));

            BigDecimal totalAmount = BigDecimal.ZERO;
            List<OrderItem> preparedItems = new ArrayList<>();

            // 2 & 3. Check stock and deduct inventory in PostgreSQL for each item
            for (PlaceOrderRequest.OrderItemDto itemDto : sortedItems) {
                int productId = itemDto.getProductId();
                int requestedQty = itemDto.getQuantity();
                if (requestedQty <= 0) {
                    throw new IllegalArgumentException("Item quantity must be positive");
                }

                // Acquire EXCLUSIVE lock on inventory upfront (SELECT ... FOR UPDATE semantics in Strict 2PL)
                // to serialize concurrent buyers of the same product without S->X upgrade deadlocks
                Product product = productRepository.findById(ctx, productId)
                        .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
                ctx.lockExclusive(ro.distr2pl.app.model.DatabaseType.POSTGRESQL, "inventory", productId);
                Inventory inv = inventoryRepository.findByProductId(ctx, productId)
                        .orElseThrow(() -> new IllegalArgumentException("Inventory not found for product: " + productId));

                if (inv.getQuantity() < requestedQty) {
                    throw new IllegalStateException("Insufficient stock for product '" + product.getName()
                            + "' (id=" + productId + "). Available=" + inv.getQuantity() + ", Requested=" + requestedQty);
                }

                int newStock = inv.getQuantity() - requestedQty;
                inventoryRepository.updateQuantity(ctx, productId, newStock);

                BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(requestedQty));
                totalAmount = totalAmount.add(subtotal);

                OrderItem oi = new OrderItem();
                oi.setProductId(productId);
                oi.setQuantity(requestedQty);
                oi.setUnitPrice(product.getPrice());
                oi.setSubtotal(subtotal);
                preparedItems.add(oi);
            }

            // 4. Create Order in PostgreSQL (status = PENDING)
            Order order = orderRepository.create(ctx, user.getId(), totalAmount, "PENDING");

            // 5. Insert Order Items into MySQL (order_items table)
            List<OrderItem> savedItems = new ArrayList<>();
            for (OrderItem oi : preparedItems) {
                OrderItem createdItem = orderItemRepository.create(
                        ctx,
                        order.getId(),
                        oi.getProductId(),
                        oi.getQuantity(),
                        oi.getUnitPrice(),
                        oi.getSubtotal()
                );
                savedItems.add(createdItem);
            }

            // 6. Create Payment record in MySQL (payments table, status = PENDING)
            Payment payment = paymentRepository.create(
                    ctx,
                    order.getId(),
                    user.getId(),
                    totalAmount,
                    request.getPaymentMethod(),
                    "PENDING"
            );

            // Optional fault injection AFTER modifying both PostgreSQL and MySQL (to test Distributed Rollback)
            if (request.isSimulateFailureAfterWrites()) {
                throw new RuntimeException("Simulated failure after modifying PostgreSQL (inventory, orders) and MySQL (order_items, payments #"
                        + payment.getId() + ")! Triggering full distributed UndoLog Rollback.");
            }

            // 7. Update Order status to CONFIRMED in PostgreSQL
            orderRepository.updateStatus(ctx, order.getId(), "CONFIRMED");
            order.setStatus("CONFIRMED");
            order.setItems(savedItems);

            return order;
        });
    }

    // =========================================================================
    // OPERATION 6: Process Payment (DISTRIBUTED TRANSACTION: PostgreSQL + MySQL)
    // =========================================================================
    /**
     * Executes a Distributed Transaction across PostgreSQL and MySQL:
     * 1. SELECT order in PostgreSQL (S-Lock)
     * 2. SELECT user in PostgreSQL (S-Lock)
     * 3. SELECT payment in MySQL (S-Lock)
     * 4. UPDATE user balance in PostgreSQL (X-Lock + UndoLog)
     * 5. UPDATE payment status to PAID in MySQL (X-Lock + UndoLog)
     * 6. UPDATE order status to PAID in PostgreSQL (X-Lock + UndoLog)
     */
    public Payment processPayment(int orderId) {
        return transactionManager.execute("PROCESS_PAYMENT", ctx -> {
            Order order = orderRepository.findById(ctx, orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

            if ("PAID".equalsIgnoreCase(order.getStatus())) {
                throw new IllegalStateException("Order #" + orderId + " is already PAID");
            }
            if ("CANCELLED".equalsIgnoreCase(order.getStatus()) || "REFUNDED".equalsIgnoreCase(order.getStatus())) {
                throw new IllegalStateException("Cannot process payment for order in status: " + order.getStatus());
            }

            User user = userRepository.findById(ctx, order.getUserId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + order.getUserId()));

            Payment payment = paymentRepository.findByOrderId(ctx, orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Payment record not found in MySQL for order: " + orderId));

            if (user.getBalance().compareTo(order.getTotalAmount()) < 0) {
                throw new IllegalStateException("Insufficient user balance (" + user.getBalance()
                        + ") for order total (" + order.getTotalAmount() + ")");
            }

            // Deduct user balance in PostgreSQL
            BigDecimal newBalance = user.getBalance().subtract(order.getTotalAmount());
            userRepository.updateBalance(ctx, user.getId(), newBalance);

            // Mark payment as PAID in MySQL
            paymentRepository.updateStatus(ctx, payment.getId(), "PAID");
            payment.setStatus("PAID");

            // Mark order as PAID in PostgreSQL
            orderRepository.updateStatus(ctx, orderId, "PAID");

            return payment;
        });
    }

    // =========================================================================
    // OPERATION 7: Cancel Order (DISTRIBUTED TRANSACTION: PostgreSQL + MySQL)
    // =========================================================================
    /**
     * Cancels an unpaid/confirmed order across PostgreSQL and MySQL:
     * 1. SELECT order in PostgreSQL (S-Lock)
     * 2. SELECT order_items in MySQL (S-Lock)
     * 3. SELECT & UPDATE inventory in PostgreSQL to restore stock (X-Lock + UndoLog)
     * 4. SELECT & UPDATE payment status to CANCELLED in MySQL (X-Lock + UndoLog)
     * 5. UPDATE order status to CANCELLED in PostgreSQL (X-Lock + UndoLog)
     */
    public Order cancelOrder(int orderId) {
        return transactionManager.execute("CANCEL_ORDER", ctx -> {
            Order order = orderRepository.findById(ctx, orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

            if ("CANCELLED".equalsIgnoreCase(order.getStatus()) || "REFUNDED".equalsIgnoreCase(order.getStatus())) {
                throw new IllegalStateException("Order #" + orderId + " is already " + order.getStatus());
            }
            if ("PAID".equalsIgnoreCase(order.getStatus())) {
                throw new IllegalStateException("Order #" + orderId + " is already PAID. Use Refund operation (#8) instead.");
            }

            // Read order items from MySQL
            List<OrderItem> items = orderItemRepository.findByOrderId(ctx, orderId);

            // Restore inventory quantities in PostgreSQL
            for (OrderItem item : items) {
                Inventory inv = inventoryRepository.findByProductId(ctx, item.getProductId())
                        .orElseThrow(() -> new IllegalStateException("Inventory missing for product " + item.getProductId()));
                inventoryRepository.updateQuantity(ctx, item.getProductId(), inv.getQuantity() + item.getQuantity());
            }

            // Update payment status in MySQL
            paymentRepository.findByOrderId(ctx, orderId).ifPresent(payment -> {
                try {
                    paymentRepository.updateStatus(ctx, payment.getId(), "CANCELLED");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // Update order status in PostgreSQL
            orderRepository.updateStatus(ctx, orderId, "CANCELLED");
            order.setStatus("CANCELLED");
            order.setItems(items);
            return order;
        });
    }

    // =========================================================================
    // OPERATION 8: Refund Payment & Restock Products (DISTRIBUTED TRANSACTION: PG + MySQL)
    // =========================================================================
    /**
     * Refunds a PAID order and restocks all items across PostgreSQL and MySQL:
     * 1. SELECT order in PostgreSQL (S-Lock)
     * 2. SELECT payment in MySQL (S-Lock)
     * 3. SELECT user in PostgreSQL (S-Lock)
     * 4. SELECT order_items in MySQL (S-Lock)
     * 5. UPDATE user balance in PostgreSQL (+refund amount) (X-Lock + UndoLog)
     * 6. UPDATE inventory in PostgreSQL (+returned quantities) (X-Lock + UndoLog)
     * 7. UPDATE payment status in MySQL to REFUNDED (X-Lock + UndoLog)
     * 8. UPDATE order status in PostgreSQL to REFUNDED (X-Lock + UndoLog)
     */
    public Payment refundPaymentAndRestock(int orderId) {
        return transactionManager.execute("REFUND_AND_RESTOCK", ctx -> {
            Order order = orderRepository.findById(ctx, orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

            Payment payment = paymentRepository.findByOrderId(ctx, orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Payment not found for order: " + orderId));

            if ("REFUNDED".equalsIgnoreCase(order.getStatus()) || "REFUNDED".equalsIgnoreCase(payment.getStatus())) {
                throw new IllegalStateException("Order #" + orderId + " has already been refunded");
            }
            if (!"PAID".equalsIgnoreCase(order.getStatus()) && !"PAID".equalsIgnoreCase(payment.getStatus())) {
                throw new IllegalStateException("Only PAID orders can be refunded (current status: " + order.getStatus() + ")");
            }

            User user = userRepository.findById(ctx, order.getUserId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + order.getUserId()));

            List<OrderItem> items = orderItemRepository.findByOrderId(ctx, orderId);

            // Refund balance to user in PostgreSQL
            BigDecimal refundedBalance = user.getBalance().add(payment.getAmount());
            userRepository.updateBalance(ctx, user.getId(), refundedBalance);

            // Restock each product in PostgreSQL inventory
            for (OrderItem item : items) {
                Inventory inv = inventoryRepository.findByProductId(ctx, item.getProductId())
                        .orElseThrow(() -> new IllegalStateException("Inventory missing for product " + item.getProductId()));
                inventoryRepository.updateQuantity(ctx, item.getProductId(), inv.getQuantity() + item.getQuantity());
            }

            // Update payment status in MySQL
            paymentRepository.updateStatus(ctx, payment.getId(), "REFUNDED");
            payment.setStatus("REFUNDED");

            // Update order status in PostgreSQL
            orderRepository.updateStatus(ctx, orderId, "REFUNDED");

            return payment;
        });
    }

    // =========================================================================
    // Read-Only Queries for UI Dashboard & State Inspection
    // =========================================================================
    public List<User> getAllUsers() throws Exception {
        return userRepository.findAllDirect();
    }

    public List<Product> getAllProducts() throws Exception {
        return productRepository.findAllDirect();
    }

    public List<Inventory> getAllInventory() throws Exception {
        return inventoryRepository.findAllDirect();
    }

    public List<Order> getAllOrders() throws Exception {
        List<Order> orders = orderRepository.findAllDirect();
        for (Order o : orders) {
            o.setItems(orderItemRepository.findByOrderIdDirect(o.getId()));
        }
        return orders;
    }

    public List<Payment> getAllPayments() throws Exception {
        return paymentRepository.findAllDirect();
    }
}

