package ro.distr2pl.app.repository.postgres;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import ro.distr2pl.app.model.DatabaseType;
import ro.distr2pl.app.model.User;
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
 * PostgreSQL JDBC Repository for the {@code users} table.
 * All queries use PreparedStatements and integrate with Strict 2PL and UndoLog.
 */
@Repository
public class UserRepository {

    private static final String TABLE = "users";
    private final DataSource postgresDataSource;

    public UserRepository(@Qualifier("postgresDataSource") DataSource postgresDataSource) {
        this.postgresDataSource = postgresDataSource;
    }

    /**
     * Transactional SELECT: Acquires SHARED (S) lock on POSTGRESQL:users:{id} before reading.
     */
    public Optional<User> findById(TransactionContext ctx, int userId) throws Exception {
        ctx.lockShared(DatabaseType.POSTGRESQL, TABLE, userId);
        String sql = "SELECT id, username, email, full_name, balance, created_at FROM users WHERE id = ?";
        Connection conn = ctx.getPostgresConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ctx.recordSql(DatabaseType.POSTGRESQL, "SELECT", TABLE, userId, sql, List.of(userId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Transactional UPDATE: Acquires EXCLUSIVE (X) lock on POSTGRESQL:users:{id},
     * captures old balance in UndoLog, and updates user balance.
     */
    public void updateBalance(TransactionContext ctx, int userId, BigDecimal newBalance) throws Exception {
        ctx.lockExclusive(DatabaseType.POSTGRESQL, TABLE, userId);
        Connection conn = ctx.getPostgresConnection();

        BigDecimal oldBalance;
        String selectSql = "SELECT balance FROM users WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("User not found with id: " + userId);
                }
                oldBalance = rs.getBigDecimal("balance");
            }
        }

        String inverseSql = "UPDATE users SET balance = ? WHERE id = ?";
        ctx.recordUndo(DatabaseType.POSTGRESQL, TABLE, userId, "balance",
                oldBalance, newBalance, "UPDATE", inverseSql, List.of(oldBalance, userId));

        String updateSql = "UPDATE users SET balance = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setBigDecimal(1, newBalance);
            ps.setInt(2, userId);
            ps.executeUpdate();
            ctx.recordSql(DatabaseType.POSTGRESQL, "UPDATE", TABLE, userId, updateSql, List.of(newBalance, userId));
        }
    }

    /**
     * Transactional INSERT: Creates a new user in PostgreSQL under Strict 2PL and UndoLog.
     */
    public User create(TransactionContext ctx, String username, String email, String fullName, BigDecimal balance) throws Exception {
        Connection conn = ctx.getPostgresConnection();
        String sql = "INSERT INTO users (username, email, full_name, balance) VALUES (?, ?, ?, ?)";
        int generatedId;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, email);
            ps.setString(3, fullName);
            ps.setBigDecimal(4, balance);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Failed to generate user ID");
                }
                generatedId = rs.getInt(1);
            }
        }

        ctx.lockExclusive(DatabaseType.POSTGRESQL, TABLE, generatedId);
        String inverseSql = "DELETE FROM users WHERE id = ?";
        ctx.recordUndo(DatabaseType.POSTGRESQL, TABLE, generatedId, "ALL_COLUMNS",
                "NONE", username + "/" + email, "INSERT", inverseSql, List.of(generatedId));
        ctx.recordSql(DatabaseType.POSTGRESQL, "INSERT", TABLE, generatedId, sql,
                List.of(username, email, fullName, balance));

        return findByIdDirect(generatedId).orElseThrow();
    }

    public Optional<User> findByIdDirect(int userId) throws Exception {
        String sql = "SELECT id, username, email, full_name, balance, created_at FROM users WHERE id = ?";
        try (Connection conn = postgresDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        }
    }

    public List<User> findAllDirect() throws Exception {
        List<User> list = new ArrayList<>();
        String sql = "SELECT id, username, email, full_name, balance, created_at FROM users ORDER BY id ASC";
        try (Connection conn = postgresDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    private User mapRow(ResultSet rs) throws Exception {
        Timestamp ts = rs.getTimestamp("created_at");
        return new User(
                rs.getInt("id"),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("full_name"),
                rs.getBigDecimal("balance"),
                ts != null ? ts.toLocalDateTime() : null
        );
    }
}

