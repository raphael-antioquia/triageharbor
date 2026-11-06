package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.triageharbor.support.PostgresTestConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=1")
@Import(PostgresTestConfiguration.class)
class EventIngestConnectionRetentionTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void transactionRetainsConnectionBeforeFirstSqlStatement() {
        // Verify we're inspecting the expected transaction setup.
        assertThat(transactionManager)
                .isInstanceOf(JpaTransactionManager.class);

        assertThat(dataSource)
                .isInstanceOf(HikariDataSource.class);

        HikariDataSource hikariDataSource =
                (HikariDataSource) dataSource;

        HikariPoolMXBean pool =
                hikariDataSource.getHikariPoolMXBean();

        assertThat(pool).isNotNull();

        // No transaction is active.
        assertThat(pool.getActiveConnections()).isZero();

        TransactionTemplate template =
                new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {
            // No SQL has been issued inside this callback.
            assertThat(pool.getActiveConnections())
                    .isEqualTo(1);
        });

        // The connection should be returned after completion.
        assertThat(pool.getActiveConnections()).isZero();
    }
}
