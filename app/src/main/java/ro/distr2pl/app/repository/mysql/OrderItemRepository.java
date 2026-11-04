package ro.distr2pl.app.repository.mysql;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import ro.distr2pl.app.model.DatabaseType;
import ro.distr2pl.app.model.OrderItem;
import ro.distr2pl.app.transaction.TransactionContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL JDBC Repository for the {@code order_items} table.
 */
@Repository
public class OrderItemRepository {

    private static final String TABLE = "order_items";
    private final DataSource mysqlDataSource;

    public OrderItemRepository(@Qualifier("mysqlDataSource") DataSource mysqlDataSource) {
        this.mysqlDataSource = mysqlDataSource;
    }

    /**
     * Transactional INSERT: Adds an order item in MySQL, acquires EXCLUSIVE lock,
     * and records an UndoLog DELETE entry.
     */
    public OrderItem create(TransactionContext ctx, int orderId, int productId,
                            int quantity, BigDecimal unitPrice, BigDecimal subtotal) throws Exception {
        Connection conn = ctx.getMysqlConnection();
        String sql = "INSERT INTO order_items (order_id, product_id, quantity, unit_price, subtotal) VALUES (?, ?, ?, ?, ?)";
        int generatedId;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, orderId);
            ps.setInt(2, productId);
            ps.setInt(3, quantity);
            ps.setBigDecimal(4, unitPrice);
            ps.setBigDecimal(5, subtotal);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Failed to generate order_item ID in MySQL");
                }
                generatedId = rs.getInt(1);
            }
        }

        ctx.lockExclusive(DatabaseType.MYSQL, TABLE, generatedId);
        String inverseSql = "DELETE FROM order_items WHERE id = ?";
        ctx.recordUndo(DatabaseType.MYSQL, TABLE, generatedId, "ALL_COLUMNS",
                "NONE", "order=" + orderId + ",product=" + productId + ",qty=" + quantity,
                "INSERT", inverseSql, List.of(generatedId));
        ctx.recordSql(DatabaseType.MYSQL, "INSERT", TABLE, generatedId, sql,
                List.of(orderId, productId, quantity, unitPrice, subtotal));

        return new OrderItem(generatedId, orderId, productId, quantity, unitPrice, subtotal);
    }

    /**
     * Transactional SELECT by order_id: Acquires SHARED (S) lock on each order_item row.
     */
    public List<OrderItem> findByOrderId(TransactionContext ctx, int orderId) throws Exception {
        List<OrderItem> items = new ArrayList<>();
        String sql = "SELECT id, order_id, product_id, quantity, unit_price, subtotal FROM order_items WHERE order_id = ?";
        Connection conn = ctx.getMysqlConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OrderItem item = mapRow(rs);
                    ctx.lockShared(DatabaseType.MYSQL, TABLE, item.getId());
                    items.add(item);
                }
            }
        }
        ctx.recordSql(DatabaseType.MYSQL, "SELECT", TABLE, "order:" + orderId, sql, List.of(orderId));
        return items;
    }

    /**
     * Transactional DELETE by id: Acquires EXCLUSIVE (X) lock, captures full row before-image
     * in UndoLog (so Rollback restores the deleted record via INSERT!), and deletes the row.
     */
    public void deleteById(TransactionContext ctx, int itemId) throws Exception {
        ctx.lockExclusive(DatabaseType.MYSQL, TABLE, itemId);
        Connection conn = ctx.getMysqlConnection();

        OrderItem oldItem;
        String selectSql = "SELECT id, order_id, product_id, quantity, unit_price, subtotal FROM order_items WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                oldItem = mapRow(rs);
            }
        }

        String inverseSql = "INSERT INTO order_items (id, order_id, product_id, quantity, unit_price, subtotal) VALUES (?, ?, ?, ?, ?, ?)";
        ctx.recordUndo(DatabaseType.MYSQL, TABLE, itemId, "ALL_COLUMNS",
                "order=" + oldItem.getOrderId() + ",product=" + oldItem.getProductId() + ",qty=" + oldItem.getQuantity(),
                "DELETED", "DELETE", inverseSql,
                List.of(oldItem.getId(), oldItem.getOrderId(), oldItem.getProductId(),
                        oldItem.getQuantity(), oldItem.getUnitPrice(), oldItem.getSubtotal()));

        String deleteSql = "DELETE FROM order_items WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
            ps.setInt(1, itemId);
            ps.executeUpdate();
            ctx.recordSql(DatabaseType.MYSQL, "DELETE", TABLE, itemId, deleteSql, List.of(itemId));
        }
    }

    public List<OrderItem> findByOrderIdDirect(int orderId) throws Exception {
        List<OrderItem> items = new ArrayList<>();
        String sql = "SELECT id, order_id, product_id, quantity, unit_price, subtotal FROM order_items WHERE order_id = ?";
        try (Connection conn = mysqlDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(mapRow(rs));
                }
            }
        }
        return items;
    }

    public List<OrderItem> findAllDirect() throws Exception {
        List<OrderItem> items = new ArrayList<>();
        String sql = "SELECT id, order_id, product_id, quantity, unit_price, subtotal FROM order_items ORDER BY id DESC";
        try (Connection conn = mysqlDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                items.add(mapRow(rs));
            }
        }
        return items;
    }

    private OrderItem mapRow(ResultSet rs) throws Exception {
        return new OrderItem(
                rs.getInt("id"),
                rs.getInt("order_id"),
                rs.getInt("product_id"),
                rs.getInt("quantity"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("subtotal")
        );
    }
}

