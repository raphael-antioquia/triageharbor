DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_roles
        WHERE rolname = 'triageharbor_migrator'
    ) THEN
        RAISE EXCEPTION
            'Required database role triageharbor_migrator does not exist. Run database bootstrap before Flyway migrations.'
            USING ERRCODE = '55000';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_roles
        WHERE rolname = 'triageharbor_app'
    ) THEN
        RAISE EXCEPTION
            'Required database role triageharbor_app does not exist. Run database bootstrap before Flyway migrations.'
            USING ERRCODE = '55000';
    END IF;
END
$$;
