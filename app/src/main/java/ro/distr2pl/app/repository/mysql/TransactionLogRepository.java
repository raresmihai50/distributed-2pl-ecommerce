package ro.distr2pl.app.repository.mysql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import ro.distr2pl.app.model.TransactionLog;
import ro.distr2pl.app.transaction.DistributedTransaction;
import ro.distr2pl.app.transaction.SqlOperation;
import ro.distr2pl.app.transaction.UndoLogEntry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JDBC Repository for persisting and querying distributed transaction audit logs
 * in the MySQL {@code transaction_logs} table.
 */
@Repository
public class TransactionLogRepository {

    private static final Logger log = LoggerFactory.getLogger(TransactionLogRepository.class);

    private final DataSource mysqlDataSource;

    public TransactionLogRepository(@Qualifier("mysqlDataSource") DataSource mysqlDataSource) {
        this.mysqlDataSource = mysqlDataSource;
    }

    public void saveOrUpdate(DistributedTransaction tx) {
        String sqlOps = tx.getSqlOperations().stream()
                .map(SqlOperation::toString)
                .collect(Collectors.joining("\n"));

        String locksHeld = String.join(", ", tx.getAcquiredLocks());

        String undoOps = tx.getUndoLog().getEntries().stream()
                .map(UndoLogEntry::toSummary)
                .collect(Collectors.joining("\n"));

        String upsertSql = """
            INSERT INTO transaction_logs (
                transaction_id, operation_name, status, phase_2pc,
                postgres_prepared, mysql_prepared, participants,
                sql_operations, locks_held, undo_operations, error_message,
                started_at, completed_at, duration_ms
            ) VALUES (?, ?, ?, ?, ?, ?, 'POSTGRESQL,MYSQL', ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                status = VALUES(status),
                phase_2pc = VALUES(phase_2pc),
                postgres_prepared = VALUES(postgres_prepared),
                mysql_prepared = VALUES(mysql_prepared),
                sql_operations = VALUES(sql_operations),
                locks_held = VALUES(locks_held),
                undo_operations = VALUES(undo_operations),
                error_message = VALUES(error_message),
                completed_at = VALUES(completed_at),
                duration_ms = VALUES(duration_ms)
        """;

        try (Connection conn = mysqlDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            conn.setAutoCommit(true);
            ps.setString(1, tx.getTransactionId());
            ps.setString(2, tx.getOperationName());
            ps.setString(3, tx.getStatus().name());
            ps.setString(4, tx.getPhase2pc());
            ps.setBoolean(5, tx.isPostgresPrepared());
            ps.setBoolean(6, tx.isMysqlPrepared());
            ps.setString(7, sqlOps);
            ps.setString(8, locksHeld);
            ps.setString(9, undoOps);
            ps.setString(10, tx.getErrorMessage());
            ps.setTimestamp(11, tx.getStartedAt() != null ? Timestamp.valueOf(tx.getStartedAt()) : null);
            ps.setTimestamp(12, tx.getCompletedAt() != null ? Timestamp.valueOf(tx.getCompletedAt()) : null);
            ps.setLong(13, tx.getDurationMs());
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Could not persist transaction log for {}: {}", tx.getTransactionId(), e.getMessage());
        }
    }

    public List<TransactionLog> findAll() {
        List<TransactionLog> list = new ArrayList<>();
        String sql = "SELECT * FROM transaction_logs ORDER BY started_at DESC LIMIT 200";
        try (Connection conn = mysqlDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TransactionLog tl = new TransactionLog();
                tl.setTransactionId(rs.getString("transaction_id"));
                tl.setOperationName(rs.getString("operation_name"));
                tl.setStatus(rs.getString("status"));
                tl.setPhase2pc(rs.getString("phase_2pc"));
                tl.setPostgresPrepared(rs.getBoolean("postgres_prepared"));
                tl.setMysqlPrepared(rs.getBoolean("mysql_prepared"));
                tl.setParticipants(rs.getString("participants"));
                tl.setSqlOperations(rs.getString("sql_operations"));
                tl.setLocksHeld(rs.getString("locks_held"));
                tl.setUndoOperations(rs.getString("undo_operations"));
                tl.setErrorMessage(rs.getString("error_message"));
                Timestamp started = rs.getTimestamp("started_at");
                if (started != null) {
                    tl.setStartedAt(started.toLocalDateTime());
                }
                Timestamp completed = rs.getTimestamp("completed_at");
                if (completed != null) {
                    tl.setCompletedAt(completed.toLocalDateTime());
                }
                tl.setDurationMs(rs.getLong("duration_ms"));
                list.add(tl);
            }
        } catch (Exception e) {
            log.warn("Could not query transaction_logs: {}", e.getMessage());
        }
        return list;
    }
}

