package ro.distr2pl.app.repository.postgres;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import ro.distr2pl.app.model.DatabaseType;
import ro.distr2pl.app.model.Product;
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
 * PostgreSQL JDBC Repository for the {@code products} table.
 */
@Repository
public class ProductRepository {

    private static final String TABLE = "products";
    private final DataSource postgresDataSource;

    public ProductRepository(@Qualifier("postgresDataSource") DataSource postgresDataSource) {
        this.postgresDataSource = postgresDataSource;
    }

    /**
     * Transactional SELECT: Acquires SHARED (S) lock on POSTGRESQL:products:{id}.
     */
    public Optional<Product> findById(TransactionContext ctx, int productId) throws Exception {
        ctx.lockShared(DatabaseType.POSTGRESQL, TABLE, productId);
        String sql = "SELECT id, name, description, price, category, created_at FROM products WHERE id = ?";
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
     * Transactional INSERT: Adds a new product and records UndoLog DELETE compensating SQL.
     */
    public Product create(TransactionContext ctx, String name, String description, BigDecimal price, String category) throws Exception {
        Connection conn = ctx.getPostgresConnection();
        String sql = "INSERT INTO products (name, description, price, category) VALUES (?, ?, ?, ?)";
        int generatedId;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setBigDecimal(3, price);
            ps.setString(4, category != null ? category : "General");
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Failed to generate product ID");
                }
                generatedId = rs.getInt(1);
            }
        }

        ctx.lockExclusive(DatabaseType.POSTGRESQL, TABLE, generatedId);
        String inverseSql = "DELETE FROM products WHERE id = ?";
        ctx.recordUndo(DatabaseType.POSTGRESQL, TABLE, generatedId, "ALL_COLUMNS",
                "NONE", name + " (" + price + ")", "INSERT", inverseSql, List.of(generatedId));
        ctx.recordSql(DatabaseType.POSTGRESQL, "INSERT", TABLE, generatedId, sql,
                List.of(name, description != null ? description : "", price, category != null ? category : "General"));

        return findByIdDirect(generatedId).orElseThrow();
    }

    /**
     * Transactional UPDATE: Acquires EXCLUSIVE (X) lock on POSTGRESQL:products:{id},
     * captures old price in UndoLog, and updates the price.
     */
    public Product updatePrice(TransactionContext ctx, int productId, BigDecimal newPrice) throws Exception {
        if (newPrice == null || newPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Product price must be strictly positive");
        }
        ctx.lockExclusive(DatabaseType.POSTGRESQL, TABLE, productId);
        Connection conn = ctx.getPostgresConnection();

        BigDecimal oldPrice;
        String selectSql = "SELECT price FROM products WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Product not found with id: " + productId);
                }
                oldPrice = rs.getBigDecimal("price");
            }
        }

        String inverseSql = "UPDATE products SET price = ? WHERE id = ?";
        ctx.recordUndo(DatabaseType.POSTGRESQL, TABLE, productId, "price",
                oldPrice, newPrice, "UPDATE", inverseSql, List.of(oldPrice, productId));

        String updateSql = "UPDATE products SET price = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setBigDecimal(1, newPrice);
            ps.setInt(2, productId);
            ps.executeUpdate();
            ctx.recordSql(DatabaseType.POSTGRESQL, "UPDATE", TABLE, productId, updateSql, List.of(newPrice, productId));
        }

        return findByIdDirect(productId).orElseThrow();
    }

    public Optional<Product> findByIdDirect(int productId) throws Exception {
        String sql = """
            SELECT p.id, p.name, p.description, p.price, p.category, p.created_at, COALESCE(i.quantity, 0) AS qty
            FROM products p
            LEFT JOIN inventory i ON p.id = i.product_id
            WHERE p.id = ?
        """;
        try (Connection conn = postgresDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Product p = mapRow(rs);
                    p.setAvailableQuantity(rs.getInt("qty"));
                    return Optional.of(p);
                }
                return Optional.empty();
            }
        }
    }

    public List<Product> findAllDirect() throws Exception {
        List<Product> list = new ArrayList<>();
        String sql = """
            SELECT p.id, p.name, p.description, p.price, p.category, p.created_at, COALESCE(i.quantity, 0) AS qty
            FROM products p
            LEFT JOIN inventory i ON p.id = i.product_id
            ORDER BY p.id ASC
        """;
        try (Connection conn = postgresDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Product p = mapRow(rs);
                p.setAvailableQuantity(rs.getInt("qty"));
                list.add(p);
            }
        }
        return list;
    }

    private Product mapRow(ResultSet rs) throws Exception {
        Timestamp ts = rs.getTimestamp("created_at");
        return new Product(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getBigDecimal("price"),
                rs.getString("category"),
                ts != null ? ts.toLocalDateTime() : null
        );
    }
}

