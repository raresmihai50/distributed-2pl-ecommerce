package ro.distr2pl.app.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.distr2pl.app.dto.CreateProductRequest;
import ro.distr2pl.app.dto.CreateUserRequest;
import ro.distr2pl.app.dto.PlaceOrderRequest;
import ro.distr2pl.app.dto.UpdatePriceRequest;
import ro.distr2pl.app.model.Inventory;
import ro.distr2pl.app.model.Order;
import ro.distr2pl.app.model.Payment;
import ro.distr2pl.app.model.Product;
import ro.distr2pl.app.model.User;
import ro.distr2pl.app.service.EcommerceService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Presentation Layer REST Controller exposing all 8 E-Commerce operations
 * and data views across PostgreSQL and MySQL.
 */
@RestController
@RequestMapping("/api")
public class EcommerceController {

    private final EcommerceService ecommerceService;

    public EcommerceController(EcommerceService ecommerceService) {
        this.ecommerceService = ecommerceService;
    }

    // Operation 1: Create User
    @PostMapping("/users")
    public ResponseEntity<User> createUser(@RequestBody CreateUserRequest request) {
        return ResponseEntity.ok(ecommerceService.createUser(request));
    }

    @GetMapping("/users")
    public ResponseEntity<List<User>> getAllUsers() throws Exception {
        return ResponseEntity.ok(ecommerceService.getAllUsers());
    }

    // Operation 2: Add Product
    @PostMapping("/products")
    public ResponseEntity<Product> addProduct(@RequestBody CreateProductRequest request) {
        return ResponseEntity.ok(ecommerceService.addProduct(request));
    }

    @GetMapping("/products")
    public ResponseEntity<List<Product>> getAllProducts() throws Exception {
        return ResponseEntity.ok(ecommerceService.getAllProducts());
    }

    // Operation 3: Update Product Price
    @PutMapping("/products/{id}/price")
    public ResponseEntity<Product> updateProductPrice(@PathVariable int id, @RequestBody UpdatePriceRequest request) {
        return ResponseEntity.ok(ecommerceService.updateProductPrice(id, request.getNewPrice()));
    }

    // Operation 4: Check Product Stock
    @GetMapping("/inventory/{productId}")
    public ResponseEntity<Inventory> checkStock(@PathVariable int productId) {
        return ResponseEntity.ok(ecommerceService.checkStock(productId));
    }

    @GetMapping("/inventory")
    public ResponseEntity<List<Inventory>> getAllInventory() throws Exception {
        return ResponseEntity.ok(ecommerceService.getAllInventory());
    }

    // Operation 5: Place Order (Distributed Transaction: PostgreSQL + MySQL)
    @PostMapping("/orders")
    public ResponseEntity<Order> placeOrder(@RequestBody PlaceOrderRequest request) {
        return ResponseEntity.ok(ecommerceService.placeOrder(request));
    }

    @GetMapping("/orders")
    public ResponseEntity<List<Order>> getAllOrders() throws Exception {
        return ResponseEntity.ok(ecommerceService.getAllOrders());
    }

    // Operation 6: Process Payment (Distributed Transaction: PostgreSQL + MySQL)
    @PostMapping("/orders/{orderId}/pay")
    public ResponseEntity<Payment> processPayment(@PathVariable int orderId) {
        return ResponseEntity.ok(ecommerceService.processPayment(orderId));
    }

    // Operation 7: Cancel Order (Distributed Transaction: PostgreSQL + MySQL)
    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<Order> cancelOrder(@PathVariable int orderId) {
        return ResponseEntity.ok(ecommerceService.cancelOrder(orderId));
    }

    // Operation 8: Refund Payment & Restock (Distributed Transaction: PostgreSQL + MySQL)
    @PostMapping("/orders/{orderId}/refund")
    public ResponseEntity<Payment> refundOrder(@PathVariable int orderId) {
        return ResponseEntity.ok(ecommerceService.refundPaymentAndRestock(orderId));
    }

    @GetMapping("/payments")
    public ResponseEntity<List<Payment>> getAllPayments() throws Exception {
        return ResponseEntity.ok(ecommerceService.getAllPayments());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ERROR");
        body.put("errorType", ex.getClass().getSimpleName());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}

