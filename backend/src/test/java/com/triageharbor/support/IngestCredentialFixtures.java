package com.triageharbor.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Creates synthetic ingest credentials for PostgreSQL integration tests. */
public final class IngestCredentialFixtures {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String INSERT_SQL =
            """
            INSERT INTO project_ingest_credentials (
                project_id,
                key_hash,
                key_prefix
            )
            VALUES (?, ?, ?)
            """;

    private IngestCredentialFixtures() {}

    public static String insertForProject(
            JdbcTemplate jdbcTemplate, UUID projectId) {

        String token = newToken();

        jdbcTemplate.update(
                INSERT_SQL,
                projectId,
                hash(token),
                token.substring(0, 11));

        return token;
    }

    public static String insertForProject(
            Connection connection, UUID projectId) throws SQLException {

        String token = newToken();

        try (PreparedStatement statement =
                connection.prepareStatement(INSERT_SQL)) {

            statement.setObject(1, projectId);
            statement.setBytes(2, hash(token));
            statement.setString(3, token.substring(0, 11));
            statement.executeUpdate();
        }

        return token;
    }

    private static String newToken() {
        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);

        return "th_"
                + Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(randomBytes);
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }
}
