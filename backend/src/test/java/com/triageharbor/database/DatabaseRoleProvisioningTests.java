package com.triageharbor.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triageharbor.support.PostgresTestConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class DatabaseRoleProvisioningTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @Test
    void restrictedRolesMatchProvisioningContract() {
        assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("triageharbor");

        assertRestrictedLoginRole("triageharbor_migrator");
        assertRestrictedLoginRole("triageharbor_app");

        assertThat(hasDatabasePrivilege("triageharbor_migrator", "CONNECT")).isTrue();
        assertThat(hasDatabasePrivilege("triageharbor_migrator", "TEMPORARY")).isFalse();

        assertThat(hasDatabasePrivilege("triageharbor_app", "CONNECT")).isTrue();
        assertThat(hasDatabasePrivilege("triageharbor_app", "TEMPORARY")).isFalse();

        assertThat(hasDatabasePrivilege("public", "CONNECT")).isFalse();
        assertThat(hasDatabasePrivilege("public", "TEMPORARY")).isFalse();

        assertThat(hasSchemaPrivilege("triageharbor_migrator", "USAGE")).isTrue();
        assertThat(hasSchemaPrivilege("triageharbor_migrator", "CREATE")).isTrue();

        assertThat(hasSchemaPrivilege("triageharbor_app", "USAGE")).isTrue();
        assertThat(hasSchemaPrivilege("triageharbor_app", "CREATE")).isFalse();

        assertThat(hasSchemaPrivilege("public", "USAGE")).isFalse();
        assertThat(hasSchemaPrivilege("public", "CREATE")).isFalse();
    }

    @Test
    void restrictedRolesCanAuthenticateWithTestPasswords() throws SQLException {
        assertCanAuthenticate("triageharbor_migrator", "test-migrator-password");
        assertCanAuthenticate("triageharbor_app", "test-app-password");
    }

    @Test
    void restrictedRolesRejectWrongPassword() {
        assertRejectsWrongPassword("triageharbor_migrator");
        assertRejectsWrongPassword("triageharbor_app");
    }

    private void assertRestrictedLoginRole(String roleName) {
        List<RoleAttributes> roles =
                jdbcTemplate.query(
                        """
                        SELECT
                            rolcanlogin,
                            rolsuper,
                            rolcreatedb,
                            rolcreaterole,
                            rolreplication,
                            rolbypassrls
                        FROM pg_roles
                        WHERE rolname = ?
                        """,
                        (resultSet, rowNumber) ->
                                new RoleAttributes(
                                        resultSet.getBoolean("rolcanlogin"),
                                        resultSet.getBoolean("rolsuper"),
                                        resultSet.getBoolean("rolcreatedb"),
                                        resultSet.getBoolean("rolcreaterole"),
                                        resultSet.getBoolean("rolreplication"),
                                        resultSet.getBoolean("rolbypassrls")),
                        roleName);

        assertThat(roles).as("role %s must exist", roleName).hasSize(1);

        RoleAttributes role = roles.get(0);

        assertThat(role.canLogin()).as("%s LOGIN", roleName).isTrue();
        assertThat(role.superuser()).as("%s NOSUPERUSER", roleName).isFalse();
        assertThat(role.createDb()).as("%s NOCREATEDB", roleName).isFalse();
        assertThat(role.createRole()).as("%s NOCREATEROLE", roleName).isFalse();
        assertThat(role.replication()).as("%s NOREPLICATION", roleName).isFalse();
        assertThat(role.bypassRls()).as("%s NOBYPASSRLS", roleName).isFalse();
    }

    private boolean hasDatabasePrivilege(String roleName, String privilege) {
        Boolean allowed =
                jdbcTemplate.queryForObject(
                        """
                        SELECT has_database_privilege(
                            ?,
                            current_database(),
                            ?
                        )
                        """,
                        Boolean.class,
                        roleName,
                        privilege);

        return Boolean.TRUE.equals(allowed);
    }

    private boolean hasSchemaPrivilege(String roleName, String privilege) {
        Boolean allowed =
                jdbcTemplate.queryForObject(
                        """
                        SELECT has_schema_privilege(
                            ?,
                            'public',
                            ?
                        )
                        """,
                        Boolean.class,
                        roleName,
                        privilege);

        return Boolean.TRUE.equals(allowed);
    }

    private void assertCanAuthenticate(String roleName, String password) throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(
                                postgresContainer.getJdbcUrl(), roleName, password);
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT
                                    current_user AS username,
                                    current_database() AS database_name
                                """);
                ResultSet resultSet = statement.executeQuery()) {

            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("username")).isEqualTo(roleName);
            assertThat(resultSet.getString("database_name")).isEqualTo("triageharbor");
        }
    }

    private void assertRejectsWrongPassword(String roleName) {
        assertThatThrownBy(
                        () ->
                                DriverManager.getConnection(
                                                postgresContainer.getJdbcUrl(),
                                                roleName,
                                                "wrong-password")
                                        .close())
                .isInstanceOfSatisfying(
                        SQLException.class,
                        exception -> assertThat(exception.getSQLState()).isEqualTo("28P01"));
    }

    private record RoleAttributes(
            boolean canLogin,
            boolean superuser,
            boolean createDb,
            boolean createRole,
            boolean replication,
            boolean bypassRls) {}
}
