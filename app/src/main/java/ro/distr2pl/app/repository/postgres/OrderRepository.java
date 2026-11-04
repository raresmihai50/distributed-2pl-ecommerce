package ro.distr2pl.app.repository.postgres;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import ro.distr2pl.app.model.DatabaseType;
import ro.distr2pl.app.model.Order;
import ro.distr2pl.app.transaction.TransactionContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL JDBC Repository for the {@code orders} table.
 */
@Repository
public class OrderRepository {

    private static final String TABLE = "orders";
    private final DataSource postgresDataSource;

    public OrderRepository(@Qualifier("postgresDataSource") DataSource postgresDataSource) {
        this.postgresDataSource = postgresDataSource;
    }

    /**
     * Transactional SELECT: Acquires SHARED (S) lock on POSTGRESQL:orders:{orderId}.
     */
    public Optional<Order> findById(TransactionContext ctx, int orderId) throws Exception {
        ctx.lockShared(DatabaseType.POSTGRESQL, TABLE, orderId);
        String sql = "SELECT id, user_id, total_amount, status, created_at FROM orders WHERE id = ?";
        Connection conn = ctx.getPostgresConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            ctx.recordSql(DatabaseType.POSTGRESQL, "SELECT", TABLE, orderId, sql, List.of(orderId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Transactional INSERT: Creates a new order in PostgreSQL, locks it exclusively,
     * and registers a DELETE UndoLog entry.
     */
    public Order create(TransactionContext ctx, int userId, BigDecimal totalAmount, String initialStatus) throws Exception {
        Connection conn = ctx.getPostgresConnection();
        String sql = "INSERT INTO orders (user_id, total_amount, status) VALUES (?, ?, ?)";
        int generatedOrderId;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, userId);
            ps.setBigDecimal(2, totalAmount);
            ps.setString(3, initialStatus);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Failed to generate order ID");
                }
                generatedOrderId = rs.getInt(1);
            }
        }

        ctx.lockExclusive(DatabaseType.POSTGRESQL, TABLE, generatedOrderId);
        String inverseSql = "DELETE FROM orders WHERE id = ?";
        ctx.recordUndo(DatabaseType.POSTGRESQL, TABLE, generatedOrderId, "ALL_COLUMNS",
                "NONE", "user=" + userId + ",total=" + totalAmount + ",status=" + initialStatus,
                "INSERT", inverseSql, List.of(generatedOrderId));
        ctx.recordSql(DatabaseType.POSTGRESQL, "INSERT", TABLE, generatedOrderId, sql,
                List.of(userId, totalAmount, initialStatus));

        return new Order(generatedOrderId, userId, totalAmount, initialStatus, java.time.LocalDateTime.now());
    }

    /**
     * Transactional UPDATE: Acquires EXCLUSIVE (X) lock on POSTGRESQL:orders:{orderId},
     * captures old status in UndoLog, and updates order status.
     */
    public void updateStatus(TransactionContext ctx, int orderId, String newStatus) throws Exception {
        ctx.lockExclusive(DatabaseType.POSTGRESQL, TABLE, orderId);
        Connection conn = ctx.getPostgresConnection();

        String oldStatus;
        String selectSql = "SELECT status FROM orders WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Order not found with id: " + orderId);
                }
                oldStatus = rs.getString("status");
            }
        }

        String inverseSql = "UPDATE orders SET status = ? WHERE id = ?";
        ctx.recordUndo(DatabaseType.POSTGRESQL, TABLE, orderId, "status",
                oldStatus, newStatus, "UPDATE", inverseSql, List.of(oldStatus, orderId));

        String updateSql = "UPDATE orders SET status = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, newStatus);
            ps.setInt(2, orderId);
            ps.executeUpdate();
            ctx.recordSql(DatabaseType.POSTGRESQL, "UPDATE", TABLE, orderId, updateSql, List.of(newStatus, orderId));
        }
    }

    public Optional<Order> findByIdDirect(int orderId) throws Exception {
        String sql = "SELECT id, user_id, total_amount, status, created_at FROM orders WHERE id = ?";
        try (Connection conn = postgresDataSource.getConnection();
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

    public List<Order> findAllDirect() throws Exception {
        List<Order> list = new ArrayList<>();
        String sql = "SELECT id, user_id, total_amount, status, created_at FROM orders ORDER BY id DESC";
        try (Connection conn = postgresDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    private Order mapRow(ResultSet rs) throws Exception {
        Timestamp ts = rs.getTimestamp("created_at");
        return new Order(
                rs.getInt("id"),
                rs.getInt("user_id"),
                rs.getBigDecimal("total_amount"),
                rs.getString("status"),
                ts != null ? ts.toLocalDateTime() : null
        );
    }
}

