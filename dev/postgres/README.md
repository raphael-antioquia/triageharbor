# Local PostgreSQL

`compose.yaml` runs `01-role-bootstrap.sql` only when the PostgreSQL data
volume is first created. The bootstrap creates:

- `triageharbor_migrator` for Flyway migrations
- `triageharbor_app` for application runtime access

Local volumes created before role separation was introduced do not contain
these roles. Starting the backend against one of those volumes may fail with:

    password authentication failed for user "triageharbor_migrator"

Reset the disposable local database to recreate it with the required roles:

    docker compose down -v
    docker compose up -d postgres

This deletes local PostgreSQL data. Do not use these commands against the
production Compose configuration.
