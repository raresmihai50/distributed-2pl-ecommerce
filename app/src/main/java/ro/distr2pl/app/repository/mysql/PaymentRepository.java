package ro.distr2pl.app.repository.mysql;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import ro.distr2pl.app.model.DatabaseType;
import ro.distr2pl.app.model.Payment;
import ro.distr2pl.app.transaction.TransactionContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MySQL JDBC Repository for the {@code payments} table.
 */
@Repository
public class PaymentRepository {

    private static final String TABLE = "payments";
    private final DataSource mysqlDataSource;

    public PaymentRepository(@Qualifier("mysqlDataSource") DataSource mysqlDataSource) {
        this.mysqlDataSource = mysqlDataSource;
    }

    /**
     * Transactional INSERT: Creates a payment record in MySQL, acquires EXCLUSIVE lock,
     * and records an UndoLog DELETE entry.
     */
    public Payment create(TransactionContext ctx, int orderId, int userId,
                          BigDecimal amount, String paymentMethod, String status) throws Exception {
        Connection conn = ctx.getMysqlConnection();
        String sql = "INSERT INTO payments (order_id, user_id, amount, payment_method, status, transaction_id) VALUES (?, ?, ?, ?, ?, ?)";
        int generatedId;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, orderId);
            ps.setInt(2, userId);
            ps.setBigDecimal(3, amount);
            ps.setString(4, paymentMethod != null ? paymentMethod : "CARD");
            ps.setString(5, status);
            ps.setString(6, ctx.getTransactionId());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Failed to generate payment ID in MySQL");
                }
                generatedId = rs.getInt(1);
            }
        }

        ctx.lockExclusive(DatabaseType.MYSQL, TABLE, generatedId);
        String inverseSql = "DELETE FROM payments WHERE id = ?";
        ctx.recordUndo(DatabaseType.MYSQL, TABLE, generatedId, "ALL_COLUMNS",
                "NONE", "order=" + orderId + ",amount=" + amount + ",status=" + status,
                "INSERT", inverseSql, List.of(generatedId));
        ctx.recordSql(DatabaseType.MYSQL, "INSERT", TABLE, generatedId, sql,
                List.of(orderId, userId, amount, paymentMethod != null ? paymentMethod : "CARD", status, ctx.getTransactionId()));

        return new Payment(generatedId, orderId, userId, amount,
                paymentMethod != null ? paymentMethod : "CARD", status, ctx.getTransactionId(), LocalDateTime.now());
    }

    /**
     * Transactional SELECT by order_id: Acquires SHARED (S) lock on MYSQL:payments:{id}.
     */
    public Optional<Payment> findByOrderId(TransactionContext ctx, int orderId) throws Exception {
        String sql = "SELECT id, order_id, user_id, amount, payment_method, status, transaction_id, created_at FROM payments WHERE order_id = ? ORDER BY id DESC LIMIT 1";
        Connection conn = ctx.getMysqlConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Payment payment = mapRow(rs);
                    ctx.lockShared(DatabaseType.MYSQL, TABLE, payment.getId());
                    ctx.recordSql(DatabaseType.MYSQL, "SELECT", TABLE, payment.getId(), sql, List.of(orderId));
                    return Optional.of(payment);
                }
            }
        }
        ctx.recordSql(DatabaseType.MYSQL, "SELECT", TABLE, "order:" + orderId, sql, List.of(orderId));
        return Optional.empty();
    }

    /**
     * Transactional SELECT by paymentId: Acquires SHARED (S) lock on MYSQL:payments:{id}.
     */
    public Optional<Payment> findById(TransactionContext ctx, int paymentId) throws Exception {
        ctx.lockShared(DatabaseType.MYSQL, TABLE, paymentId);
        String sql = "SELECT id, order_id, user_id, amount, payment_method, status, transaction_id, created_at FROM payments WHERE id = ?";
        Connection conn = ctx.getMysqlConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, paymentId);
            ctx.recordSql(DatabaseType.MYSQL, "SELECT", TABLE, paymentId, sql, List.of(paymentId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Transactional UPDATE: Acquires EXCLUSIVE (X) lock on MYSQL:payments:{paymentId},
     * captures old status in UndoLog, and updates payment status.
     */
    public void updateStatus(TransactionContext ctx, int paymentId, String newStatus) throws Exception {
        ctx.lockExclusive(DatabaseType.MYSQL, TABLE, paymentId);
        Connection conn = ctx.getMysqlConnection();

        String oldStatus;
        String selectSql = "SELECT status FROM payments WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setInt(1, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Payment not found with id: " + paymentId);
                }
                oldStatus = rs.getString("status");
            }
        }

        String inverseSql = "UPDATE payments SET status = ? WHERE id = ?";
        ctx.recordUndo(DatabaseType.MYSQL, TABLE, paymentId, "status",
                oldStatus, newStatus, "UPDATE", inverseSql, List.of(oldStatus, paymentId));

        String updateSql = "UPDATE payments SET status = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, newStatus);
            ps.setInt(2, paymentId);
            ps.executeUpdate();
            ctx.recordSql(DatabaseType.MYSQL, "UPDATE", TABLE, paymentId, updateSql, List.of(newStatus, paymentId));
        }
    }

    public Optional<Payment> findByIdDirect(int paymentId) throws Exception {
        String sql = "SELECT id, order_id, user_id, amount, payment_method, status, transaction_id, created_at FROM payments WHERE id = ?";
        try (Connection conn = mysqlDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        }
    }

    public Optional<Payment> findByOrderIdDirect(int orderId) throws Exception {
        String sql = "SELECT id, order_id, user_id, amount, payment_method, status, transaction_id, created_at FROM payments WHERE order_id = ? ORDER BY id DESC LIMIT 1";
        try (Connection conn = mysqlDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        }
    }

    public List<Payment> findAllDirect() throws Exception {
        List<Payment> list = new ArrayList<>();
        String sql = "SELECT id, order_id, user_id, amount, payment_method, status, transaction_id, created_at FROM payments ORDER BY id DESC";
        try (Connection conn = mysqlDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    private Payment mapRow(ResultSet rs) throws Exception {
        Timestamp ts = rs.getTimestamp("created_at");
        return new Payment(
                rs.getInt("id"),
                rs.getInt("order_id"),
                rs.getInt("user_id"),
                rs.getBigDecimal("amount"),
                rs.getString("payment_method"),
                rs.getString("status"),
                rs.getString("transaction_id"),
                ts != null ? ts.toLocalDateTime() : null
        );
    }
}

