-- Portable database role/security contract verifier (PostgreSQL 17+: uses MAINTAIN).
-- Plain SQL only, so the same file runs through JDBC and psql.
-- One row per check (check_name | passed); NULL is reported as failure.
-- The row count (47) is part of the contract: changing it is a deliberate contract change.

WITH
context AS (
    SELECT
        (SELECT oid FROM pg_roles WHERE rolname = 'triageharbor_migrator') AS migrator_oid,
        (SELECT oid FROM pg_roles WHERE rolname = 'triageharbor_app') AS app_oid,
        (SELECT oid FROM pg_database WHERE datname = current_database()) AS database_oid,
        (SELECT oid FROM pg_namespace WHERE nspname = 'public') AS public_schema_oid
),

-- Role existence and attribute flags (14 rows)
restricted_roles (label, role_name) AS (
    VALUES
        ('migrator', 'triageharbor_migrator'),
        ('app', 'triageharbor_app')
),
role_checks AS (
    SELECT
        'role_' || r.label || '_' || flag.suffix AS check_name,
        flag.ok AS passed
    FROM restricted_roles AS r
    LEFT JOIN pg_roles AS role ON role.rolname = r.role_name
    CROSS JOIN LATERAL (
        VALUES
            ('exists', role.oid IS NOT NULL),
            ('login', role.rolcanlogin),
            ('nosuperuser', NOT role.rolsuper),
            ('nocreatedb', NOT role.rolcreatedb),
            ('nocreaterole', NOT role.rolcreaterole),
            ('noreplication', NOT role.rolreplication),
            ('nobypassrls', NOT role.rolbypassrls)
    ) AS flag (suffix, ok)
),

-- Role memberships (2 rows)
membership_checks AS (
    SELECT
        'restricted_roles_are_members_of_nothing' AS check_name,
        c.app_oid IS NOT NULL
            AND c.migrator_oid IS NOT NULL
            AND NOT EXISTS (
                SELECT 1 FROM pg_auth_members AS m
                WHERE m.member IN (c.app_oid, c.migrator_oid)
            ) AS passed
    FROM context AS c

    UNION ALL

    -- Revisit when the triageharbor_admin creation mechanism is decided:
    -- a non-superuser CREATEROLE creator is auto-granted membership (PG16+).
    SELECT
        'restricted_roles_granted_to_nobody',
        c.app_oid IS NOT NULL
            AND c.migrator_oid IS NOT NULL
            AND NOT EXISTS (
                SELECT 1 FROM pg_auth_members AS m
                WHERE m.roleid IN (c.app_oid, c.migrator_oid)
            )
    FROM context AS c
),

-- Database and public-schema ACLs (15 rows).
-- Every row also requires that the privilege is not delegable (no grant option).
acl_expectations (grantee, object_kind, privilege, expected) AS (
    VALUES
        ('migrator', 'database', 'CONNECT', true),
        ('migrator', 'database', 'TEMPORARY', false),
        ('migrator', 'database', 'CREATE', false),
        ('migrator', 'schema', 'USAGE', true),
        ('migrator', 'schema', 'CREATE', true),

        ('app', 'database', 'CONNECT', true),
        ('app', 'database', 'TEMPORARY', false),
        ('app', 'database', 'CREATE', false),
        ('app', 'schema', 'USAGE', true),
        ('app', 'schema', 'CREATE', false),

        ('public_role', 'database', 'CONNECT', false),
        ('public_role', 'database', 'TEMPORARY', false),
        ('public_role', 'database', 'CREATE', false),
        ('public_role', 'schema', 'USAGE', false),
        ('public_role', 'schema', 'CREATE', false)
),
acl_probes AS (
    SELECT
        e.grantee,
        e.object_kind,
        e.privilege,
        e.expected,
        mode.with_grant,
        CASE
            WHEN e.object_kind = 'database' AND e.grantee = 'public_role'
                THEN has_database_privilege(
                    'public'::name,
                    c.database_oid,
                    mode.privilege_text
                )
            WHEN e.object_kind = 'database'
                THEN has_database_privilege(
                    g.grantee_oid,
                    c.database_oid,
                    mode.privilege_text
                )
            WHEN e.grantee = 'public_role'
                THEN has_schema_privilege(
                    'public'::name,
                    c.public_schema_oid,
                    mode.privilege_text
                )
            ELSE has_schema_privilege(
                g.grantee_oid,
                c.public_schema_oid,
                mode.privilege_text
            )
        END AS granted
    FROM acl_expectations AS e
    CROSS JOIN context AS c
    CROSS JOIN LATERAL (
        SELECT CASE e.grantee
            WHEN 'app' THEN c.app_oid
            WHEN 'migrator' THEN c.migrator_oid
        END AS grantee_oid
    ) AS g
    CROSS JOIN LATERAL (
        VALUES
            (false, e.privilege),
            (true, e.privilege || ' WITH GRANT OPTION')
    ) AS mode (with_grant, privilege_text)
),
acl_checks AS (
    SELECT
        grantee || '_' || object_kind || '_'
            || CASE WHEN expected THEN '' ELSE 'no_' END
            || lower(privilege) AS check_name,
        bool_and(
            COALESCE(
                CASE
                    WHEN with_grant THEN NOT granted
                    ELSE granted = expected
                END,
                false
            )
        ) AS passed
    FROM acl_probes
    GROUP BY grantee, object_kind, privilege, expected
),

-- Object map for schema public (5 rows)
expected_tables (table_name) AS (
    VALUES
        ('events'),
        ('flyway_schema_history'),
        ('incidents'),
        ('project_ingest_credentials'),
        ('projects')
),
table_objects AS (
    SELECT
        t.table_name,
        rel.oid AS table_oid,
        pg_get_userbyid(rel.relowner) AS owner_name
    FROM expected_tables AS t
    CROSS JOIN context AS c
    LEFT JOIN pg_class AS rel
        ON rel.relnamespace = c.public_schema_oid
        AND rel.relname = t.table_name
        AND rel.relkind IN ('r', 'p')
),
object_checks AS (
    SELECT
        'schema_public_table_set_exact' AS check_name,
        NOT EXISTS (
            SELECT 1
            FROM table_objects
            WHERE table_oid IS NULL
        )
        AND NOT EXISTS (
            SELECT 1
            FROM pg_class AS rel
            WHERE rel.relnamespace = c.public_schema_oid
                AND rel.relkind IN ('r', 'p')
                AND rel.relname NOT IN (
                    SELECT table_name
                    FROM expected_tables
                )
        ) AS passed
    FROM context AS c

    UNION ALL

    SELECT
        'schema_public_' || kind.label || '_none',
        c.public_schema_oid IS NOT NULL
            AND NOT EXISTS (
                SELECT 1
                FROM pg_class AS rel
                WHERE rel.relnamespace = c.public_schema_oid
                    AND rel.relkind = kind.relkind
            )
    FROM context AS c
    CROSS JOIN (
        VALUES
            ('sequences', 'S'::"char"),
            ('views', 'v'::"char"),
            ('materialized_views', 'm'::"char")
    ) AS kind (label, relkind)

    UNION ALL

    -- All routine kinds: functions, procedures, aggregates, window functions.
    SELECT
        'schema_public_routines_none',
        c.public_schema_oid IS NOT NULL
            AND NOT EXISTS (
                SELECT 1
                FROM pg_proc AS routine
                WHERE routine.pronamespace = c.public_schema_oid
            )
    FROM context AS c
),

-- Default privileges (1 row).
-- Customized defaults may exist, but none may auto-grant future objects
-- to the app or PUBLIC (grantee OID 0).
--
-- Known false positive: a global customization on FUNCTIONS/TYPES can
-- materialize PostgreSQL's built-in PUBLIC entry and fail this check even
-- when PUBLIC's effective default was not widened. Fail closed and inspect.
default_acl_checks AS (
    SELECT
        'default_privileges_do_not_grant_app_or_public' AS check_name,
        c.app_oid IS NOT NULL
            AND NOT EXISTS (
                SELECT 1
                FROM pg_default_acl AS d
                CROSS JOIN LATERAL aclexplode(d.defaclacl) AS p
                WHERE p.grantee IN (c.app_oid, 0::oid)
            ) AS passed
    FROM context AS c
),

-- Ownership (5 rows)
ownership_checks AS (
    SELECT
        'owner_' || table_name || '_migrator' AS check_name,
        table_oid IS NOT NULL
            AND owner_name = 'triageharbor_migrator' AS passed
    FROM table_objects
),

-- Exact app table privileges (5 rows)
app_table_allowlist (table_name, privilege) AS (
    VALUES
        ('projects', 'SELECT'),
        ('incidents', 'SELECT'),
        ('incidents', 'INSERT'),
        ('incidents', 'UPDATE'),
        ('events', 'INSERT'),
        ('project_ingest_credentials', 'SELECT')
),
table_privileges (privilege) AS (
    VALUES
        ('SELECT'),
        ('INSERT'),
        ('UPDATE'),
        ('DELETE'),
        ('TRUNCATE'),
        ('REFERENCES'),
        ('TRIGGER'),
        ('MAINTAIN')
),
app_table_privilege_checks AS (
    SELECT
        'app_table_privileges_exact:' || t.table_name AS check_name,
        bool_and(
            COALESCE(
                has_table_privilege(
                    c.app_oid,
                    t.table_oid,
                    p.privilege
                ) = a.expected
                AND CASE
                    WHEN p.privilege IN (
                        'SELECT',
                        'INSERT',
                        'UPDATE',
                        'REFERENCES'
                    ) THEN
                        has_any_column_privilege(
                            c.app_oid,
                            t.table_oid,
                            p.privilege
                        ) = a.expected
                        AND NOT has_any_column_privilege(
                            c.app_oid,
                            t.table_oid,
                            p.privilege || ' WITH GRANT OPTION'
                        )
                    ELSE
                        NOT has_table_privilege(
                            c.app_oid,
                            t.table_oid,
                            p.privilege || ' WITH GRANT OPTION'
                        )
                END,
                false
            )
        ) AS passed
    FROM table_objects AS t
    CROSS JOIN table_privileges AS p
    CROSS JOIN context AS c
    CROSS JOIN LATERAL (
        SELECT EXISTS (
            SELECT 1
            FROM app_table_allowlist AS al
            WHERE al.table_name = t.table_name
                AND al.privilege = p.privilege
        ) AS expected
    ) AS a
    GROUP BY t.table_name
),

checks AS (
    SELECT check_name, passed FROM role_checks
    UNION ALL
    SELECT check_name, passed FROM membership_checks
    UNION ALL
    SELECT check_name, passed FROM acl_checks
    UNION ALL
    SELECT check_name, passed FROM object_checks
    UNION ALL
    SELECT check_name, passed FROM default_acl_checks
    UNION ALL
    SELECT check_name, passed FROM ownership_checks
    UNION ALL
    SELECT check_name, passed FROM app_table_privilege_checks
)
SELECT
    check_name,
    COALESCE(passed, false) AS passed
FROM checks
ORDER BY check_name;
