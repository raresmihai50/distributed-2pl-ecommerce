package ro.distr2pl.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;

/**
 * Entry point for the Distributed 2PL E-Commerce application.
 *
 * IMPORTANT: Spring's single-DataSource and TransactionManager auto-configurations
 * are explicitly excluded. All distributed transactions, Strict 2PL locking,
 * Wait-For Graph deadlock detection, Undo Log rollbacks, and Two-Phase Commit (2PC)
 * are implemented manually at the application level.
 */
@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class
})
public class AppApplication {

    public static void main(String[] args) {
        SpringApplication.run(AppApplication.class, args);
    }

}
