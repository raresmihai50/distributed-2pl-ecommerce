package ro.distr2pl.app.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Configures two completely independent JDBC DataSources:
 * 1. PostgreSQL DataSource (users, products, inventory, orders)
 * 2. MySQL DataSource (payments, order_items, transaction_logs)
 *
 * No Spring PlatformTransactionManager is registered. Connections are managed
 * directly by our custom TransactionManager, CommitManager, and RollbackManager.
 */
@Configuration
public class DataSourceConfig {

    @Value("${app.datasource.postgres.url}")
    private String pgUrl;

    @Value("${app.datasource.postgres.username}")
    private String pgUsername;

    @Value("${app.datasource.postgres.password}")
    private String pgPassword;

    @Value("${app.datasource.postgres.driver-class-name}")
    private String pgDriver;

    @Value("${app.datasource.mysql.url}")
    private String mysqlUrl;

    @Value("${app.datasource.mysql.username}")
    private String mysqlUsername;

    @Value("${app.datasource.mysql.password}")
    private String mysqlPassword;

    @Value("${app.datasource.mysql.driver-class-name}")
    private String mysqlDriver;

    @Bean(name = "postgresDataSource")
    public DataSource postgresDataSource() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("Postgres-2PL-Pool");
        config.setJdbcUrl(pgUrl);
        config.setUsername(pgUsername);
        config.setPassword(pgPassword);
        config.setDriverClassName(pgDriver);
        config.setMaximumPoolSize(40);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(15000);
        return new HikariDataSource(config);
    }

    @Bean(name = "mysqlDataSource")
    public DataSource mysqlDataSource() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("MySQL-2PL-Pool");
        config.setJdbcUrl(mysqlUrl);
        config.setUsername(mysqlUsername);
        config.setPassword(mysqlPassword);
        config.setDriverClassName(mysqlDriver);
        config.setMaximumPoolSize(40);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(15000);
        return new HikariDataSource(config);
    }
}

