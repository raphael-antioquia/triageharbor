package com.triageharbor.support;

import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class RoleSeparatedPostgresTestConfiguration {

    @Bean
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17.11"))
                .withDatabaseName("triageharbor")
                .withInitScript("db/test-role-bootstrap.sql");
    }

    @Bean
    JdbcConnectionDetails jdbcConnectionDetails(PostgreSQLContainer postgresContainer) {
        return new JdbcConnectionDetails() {

            @Override
            public String getUsername() {
                return "triageharbor_app";
            }

            @Override
            public String getPassword() {
                return "test-app-password";
            }

            @Override
            public String getJdbcUrl() {
                return postgresContainer.getJdbcUrl();
            }
        };
    }

    @Bean
    FlywayConnectionDetails flywayConnectionDetails(PostgreSQLContainer postgresContainer) {
        return new FlywayConnectionDetails() {

            @Override
            public String getUsername() {
                return "triageharbor_migrator";
            }

            @Override
            public String getPassword() {
                return "test-migrator-password";
            }

            @Override
            public String getJdbcUrl() {
                return postgresContainer.getJdbcUrl();
            }
        };
    }
}
