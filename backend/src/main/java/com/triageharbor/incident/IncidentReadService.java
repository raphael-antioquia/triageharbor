package com.triageharbor.incident;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IncidentReadService {

    private final JdbcTemplate jdbcTemplate;

    public IncidentReadService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<IncidentSummary> listForProject(UUID projectId) {
        if (!projectExists(projectId)) {
            throw new UnknownProjectException();
        }

        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    title,
                    status,
                    occurrence_count,
                    first_seen_at,
                    last_seen_at
                FROM incidents
                WHERE project_id = ?
                ORDER BY last_seen_at DESC, id DESC
                LIMIT 100
                """,
                (resultSet, rowNumber) ->
                        new IncidentSummary(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getString("title"),
                                resultSet.getString("status"),
                                resultSet.getLong("occurrence_count"),
                                resultSet
                                        .getObject("first_seen_at", OffsetDateTime.class)
                                        .toInstant(),
                                resultSet
                                        .getObject("last_seen_at", OffsetDateTime.class)
                                        .toInstant()),
                projectId);
    }

    private boolean projectExists(UUID projectId) {
        Boolean exists =
                jdbcTemplate.queryForObject(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM projects
                            WHERE id = ?
                        )
                        """,
                        Boolean.class,
                        projectId);

        return Boolean.TRUE.equals(exists);
    }
}
