package com.triageharbor.event;

import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IngestCredentialResolver {

    private final JdbcTemplate jdbcTemplate;

    public IngestCredentialResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Only a missing credential becomes 401; other database failures propagate.
    public AuthenticatedProject resolve(UUID ingestKey) {
        try {
            UUID projectId =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT id
                            FROM projects
                            WHERE ingest_key = ?
                            """,
                            UUID.class,
                            ingestKey);

            return new AuthenticatedProject(
                    Objects.requireNonNull(projectId, "Project lookup returned a null ID"));
        } catch (EmptyResultDataAccessException exception) {
            throw new UnknownIngestKeyException();
        }
    }
}
