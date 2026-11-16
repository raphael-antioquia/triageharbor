package com.triageharbor.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IngestCredentialResolver {

    // Whole-string canonical grammar; no trimming or normalization.
    private static final Pattern INGEST_KEY_FORMAT =
            Pattern.compile("th_[A-Za-z0-9_-]{43}");

    private final JdbcTemplate jdbcTemplate;

    public IngestCredentialResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Malformed, unknown or revoked credentials become 401;
    // other database failures propagate.
    public AuthenticatedProject resolve(String ingestKey) {
        if (ingestKey == null || !INGEST_KEY_FORMAT.matcher(ingestKey).matches()) {
            throw new UnknownIngestKeyException();
        }

        try {
            UUID projectId =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT project_id
                            FROM project_ingest_credentials
                            WHERE key_hash = ?
                              AND revoked_at IS NULL
                            """,
                            UUID.class,
                            (Object) hash(ingestKey));

            return new AuthenticatedProject(
                    Objects.requireNonNull(
                            projectId, "Credential lookup returned a null project ID"));
        } catch (EmptyResultDataAccessException exception) {
            throw new UnknownIngestKeyException();
        }
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
