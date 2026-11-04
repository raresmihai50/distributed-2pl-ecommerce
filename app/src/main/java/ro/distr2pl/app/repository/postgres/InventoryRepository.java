package ro.distr2pl.app.repository.postgres;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import ro.distr2pl.app.model.DatabaseType;
import ro.distr2pl.app.model.Inventory;
import ro.distr2pl.app.transaction.TransactionContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL JDBC Repository for the {@code inventory} table.
 */
@Repository
public class InventoryRepository {

    private static final String TABLE = "inventory";
    private final DataSource postgresDataSource;

    public InventoryRepository(@Qualifier("postgresDataSource") DataSource postgresDataSource) {
        this.postgresDataSource = postgresDataSource;
    }

    /**
     * Transactional SELECT: Acquires SHARED (S) lock on POSTGRESQL:inventory:{productId}.
     */
    public Optional<Inventory> findByProductId(TransactionContext ctx, int productId) throws Exception {
        ctx.lockShared(DatabaseType.POSTGRESQL, TABLE, productId);
        String sql = "SELECT product_id, quantity, reserved_quantity, updated_at FROM inventory WHERE product_id = ?";
        Connection conn = ctx.getPostgresConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, productId);
            ctx.recordSql(DatabaseType.POSTGRESQL, "SELECT", TABLE, productId, sql, List.of(productId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Transactional INSERT: Initializes inventory row for a newly created product.
     */
    public Inventory create(TransactionContext ctx, int productId, int initialQuantity) throws Exception {
        ctx.lockExclusive(DatabaseType.POSTGRESQL, TABLE, productId);
        Connection conn = ctx.getPostgresConnection();

        String inverseSql = "DELETE FROM inventory WHERE product_id = ?";
        ctx.recordUndo(DatabaseType.POSTGRESQL, TABLE, productId, "quantity",
                "NONE", initialQuantity, "INSERT", inverseSql, List.of(productId));

        String sql = "INSERT INTO inventory (product_id, quantity, reserved_quantity) VALUES (?, ?, 0)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, productId);
            ps.setInt(2, initialQuantity);
            ps.executeUpdate();
            ctx.recordSql(DatabaseType.POSTGRESQL, "INSERT", TABLE, productId, sql, List.of(productId, initialQuantity));
        }
        return findByProductIdDirect(productId).orElseThrow();
    }

    /**
     * Transactional UPDATE: Acquires EXCLUSIVE (X) lock on POSTGRESQL:inventory:{productId},
     * records old quantity in UndoLog, and updates stock quantity.
     * Prevents negative stock at both application and SQL constraint levels.
     */
    public void updateQuantity(TransactionContext ctx, int productId, int newQuantity) throws Exception {
        ctx.lockExclusive(DatabaseType.POSTGRESQL, TABLE, productId);
        Connection conn = ctx.getPostgresConnection();

        int oldQuantity;
        String selectSql = "SELECT quantity FROM inventory WHERE product_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Inventory not found for product_id: " + productId);
                }
                oldQuantity = rs.getInt("quantity");
            }
        }

        if (newQuantity < 0) {
            throw new IllegalStateException("Insufficient stock for product " + productId
                    + ": available=" + oldQuantity + ", cannot set quantity to " + newQuantity);
        }

        String inverseSql = "UPDATE inventory SET quantity = ?, updated_at = CURRENT_TIMESTAMP WHERE product_id = ?";
        ctx.recordUndo(DatabaseType.POSTGRESQL, TABLE, productId, "quantity",
                oldQuantity, newQuantity, "UPDATE", inverseSql, List.of(oldQuantity, productId));

        String updateSql = "UPDATE inventory SET quantity = ?, updated_at = CURRENT_TIMESTAMP WHERE product_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setInt(1, newQuantity);
            ps.setInt(2, productId);
            ps.executeUpdate();
            ctx.recordSql(DatabaseType.POSTGRESQL, "UPDATE", TABLE, productId, updateSql, List.of(newQuantity, productId));
        }
    }

    public Optional<Inventory> findByProductIdDirect(int productId) throws Exception {
        String sql = """
            SELECT i.product_id, p.name AS product_name, i.quantity, i.reserved_quantity, i.updated_at
            FROM inventory i
            LEFT JOIN products p ON i.product_id = p.id
            WHERE i.product_id = ?
        """;
        try (Connection conn = postgresDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Inventory inv = mapRow(rs);
                    inv.setProductName(rs.getString("product_name"));
                    return Optional.of(inv);
                }
                return Optional.empty();
            }
        }
    }

    public List<Inventory> findAllDirect() throws Exception {
        List<Inventory> list = new ArrayList<>();
        String sql = """
            SELECT i.product_id, p.name AS product_name, i.quantity, i.reserved_quantity, i.updated_at
            FROM inventory i
            LEFT JOIN products p ON i.product_id = p.id
            ORDER BY i.product_id ASC
        """;
        try (Connection conn = postgresDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Inventory inv = mapRow(rs);
                inv.setProductName(rs.getString("product_name"));
                list.add(inv);
            }
        }
        return list;
    }

    private Inventory mapRow(ResultSet rs) throws Exception {
        Timestamp ts = rs.getTimestamp("updated_at");
        return new Inventory(
                rs.getInt("product_id"),
                rs.getInt("quantity"),
                rs.getInt("reserved_quantity"),
                ts != null ? ts.toLocalDateTime() : null
        );
    }
}

