CREATE ROLE triageharbor_migrator;

ALTER ROLE triageharbor_migrator
    WITH LOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    NOBYPASSRLS
    PASSWORD 'triageharbor_migrator';

CREATE ROLE triageharbor_app;

ALTER ROLE triageharbor_app
    WITH LOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    NOBYPASSRLS
    PASSWORD 'triageharbor_app';

REVOKE CONNECT, TEMPORARY
    ON DATABASE triageharbor
    FROM PUBLIC;

REVOKE ALL PRIVILEGES
    ON DATABASE triageharbor
    FROM triageharbor_migrator, triageharbor_app;

GRANT CONNECT
    ON DATABASE triageharbor
    TO triageharbor_migrator, triageharbor_app;

REVOKE ALL PRIVILEGES
    ON SCHEMA public
    FROM PUBLIC;

REVOKE ALL PRIVILEGES
    ON SCHEMA public
    FROM triageharbor_migrator;

GRANT USAGE, CREATE
    ON SCHEMA public
    TO triageharbor_migrator;

REVOKE ALL PRIVILEGES
    ON SCHEMA public
    FROM triageharbor_app;

GRANT USAGE
    ON SCHEMA public
    TO triageharbor_app;
