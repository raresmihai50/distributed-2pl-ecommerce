package ro.distr2pl.app.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Verifies connectivity to both PostgreSQL and MySQL on startup,
 * initializes schema tables if they do not exist, and seeds initial test data.
 */
@Component
public class DatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final DataSource postgresDataSource;
    private final DataSource mysqlDataSource;

    @Value("${app.transaction.initialize-schema:true}")
    private boolean initializeSchema;

    public DatabaseInitializer(
            @Qualifier("postgresDataSource") DataSource postgresDataSource,
            @Qualifier("mysqlDataSource") DataSource mysqlDataSource) {
        this.postgresDataSource = postgresDataSource;
        this.mysqlDataSource = mysqlDataSource;
    }

    @PostConstruct
    public void init() {
        if (!initializeSchema) {
            return;
        }
        initPostgres();
        initMysql();
        seedInitialDataIfEmpty();
    }

    public void initPostgres() {
        try (Connection conn = postgresDataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id SERIAL PRIMARY KEY,
                    username VARCHAR(100) NOT NULL UNIQUE,
                    email VARCHAR(150) NOT NULL UNIQUE,
                    full_name VARCHAR(150) NOT NULL,
                    balance NUMERIC(12, 2) NOT NULL DEFAULT 1000.00,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS products (
                    id SERIAL PRIMARY KEY,
                    name VARCHAR(150) NOT NULL,
                    description TEXT,
                    price NUMERIC(12, 2) NOT NULL,
                    category VARCHAR(100) NOT NULL DEFAULT 'Electronics',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS inventory (
                    product_id INTEGER PRIMARY KEY REFERENCES products(id) ON DELETE CASCADE,
                    quantity INTEGER NOT NULL CHECK (quantity >= 0),
                    reserved_quantity INTEGER NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    id SERIAL PRIMARY KEY,
                    user_id INTEGER NOT NULL REFERENCES users(id),
                    total_amount NUMERIC(12, 2) NOT NULL,
                    status VARCHAR(50) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);

            log.info("PostgreSQL schema verified successfully.");
        } catch (Exception e) {
            log.error("Failed to initialize PostgreSQL schema: {}", e.getMessage(), e);
            throw new RuntimeException("PostgreSQL initialization failed", e);
        }
    }

    public void initMysql() {
        try (Connection conn = mysqlDataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS payments (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    order_id INT NOT NULL,
                    user_id INT NOT NULL,
                    amount DECIMAL(12, 2) NOT NULL,
                    payment_method VARCHAR(50) NOT NULL DEFAULT 'CARD',
                    status VARCHAR(50) NOT NULL,
                    transaction_id VARCHAR(100),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS order_items (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    order_id INT NOT NULL,
                    product_id INT NOT NULL,
                    quantity INT NOT NULL,
                    unit_price DECIMAL(12, 2) NOT NULL,
                    subtotal DECIMAL(12, 2) NOT NULL
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS transaction_logs (
                    transaction_id VARCHAR(100) PRIMARY KEY,
                    operation_name VARCHAR(150) NOT NULL,
                    status VARCHAR(50) NOT NULL,
                    phase_2pc VARCHAR(50) NOT NULL DEFAULT 'NONE',
                    postgres_prepared BOOLEAN NOT NULL DEFAULT FALSE,
                    mysql_prepared BOOLEAN NOT NULL DEFAULT FALSE,
                    participants VARCHAR(100) NOT NULL DEFAULT 'POSTGRESQL,MYSQL',
                    sql_operations TEXT,
                    locks_held TEXT,
                    undo_operations TEXT,
                    error_message TEXT,
                    started_at TIMESTAMP NULL,
                    completed_at TIMESTAMP NULL,
                    duration_ms BIGINT DEFAULT 0
                )
            """);

            log.info("MySQL schema verified successfully.");
        } catch (Exception e) {
            log.error("Failed to initialize MySQL schema: {}", e.getMessage(), e);
            throw new RuntimeException("MySQL initialization failed", e);
        }
    }

    public void seedInitialDataIfEmpty() {
        try (Connection conn = postgresDataSource.getConnection()) {
            boolean hasUsers = false;
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM users");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    hasUsers = true;
                }
            }

            if (!hasUsers) {
                insertUser(conn, "alex_popescu", "alex.popescu@student.ro", "Alexandru Popescu", new BigDecimal("5000.00"));
                insertUser(conn, "maria_ionescu", "maria.ionescu@student.ro", "Maria Ionescu", new BigDecimal("3500.00"));
                insertUser(conn, "andrei_radulescu", "andrei.radulescu@student.ro", "Andrei Radulescu", new BigDecimal("2000.00"));
            }

            boolean hasProducts = false;
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM products");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    hasProducts = true;
                }
            }

            if (!hasProducts) {
                int p1 = insertProduct(conn, "Laptop Pro 16\"", "High-performance developer laptop with 32GB RAM", new BigDecimal("1200.00"), "Computers");
                int p2 = insertProduct(conn, "Mechanical Keyboard RGB", "Tactile mechanical keyboard for programming", new BigDecimal("150.00"), "Peripherals");
                int p3 = insertProduct(conn, "Wireless Noise-Canceling Headphones", "Studio-grade wireless headphones", new BigDecimal("250.00"), "Audio");
                int p4 = insertProduct(conn, "Limited Edition GPU RTX", "Last item in stock - ideal for concurrency race tests", new BigDecimal("800.00"), "Hardware");

                insertInventory(conn, p1, 10);
                insertInventory(conn, p2, 25);
                insertInventory(conn, p3, 15);
                insertInventory(conn, p4, 1);
            }
        } catch (Exception e) {
            log.warn("Could not seed initial data: {}", e.getMessage());
        }
    }

    private void insertUser(Connection conn, String username, String email, String fullName, BigDecimal balance) throws Exception {
        String sql = "INSERT INTO users (username, email, full_name, balance) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, email);
            ps.setString(3, fullName);
            ps.setBigDecimal(4, balance);
            ps.executeUpdate();
        }
    }

    private int insertProduct(Connection conn, String name, String description, BigDecimal price, String category) throws Exception {
        String sql = "INSERT INTO products (name, description, price, category) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setBigDecimal(3, price);
            ps.setString(4, category);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        throw new IllegalStateException("Failed to retrieve generated product ID");
    }

    private void insertInventory(Connection conn, int productId, int quantity) throws Exception {
        String sql = "INSERT INTO inventory (product_id, quantity, reserved_quantity) VALUES (?, ?, 0)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, productId);
            ps.setInt(2, quantity);
            ps.executeUpdate();
        }
    }
}

