\set ON_ERROR_STOP on
\set VERBOSITY terse

BEGIN;

SET LOCAL lock_timeout = '5s';

SELECT set_config(
           'triageharbor.project_id',
           :'project_id',
           true
       ),
       set_config(
           'triageharbor.project_name',
           :'project_name',
           true
       )
\g /dev/null

DO $$
DECLARE
    cfg_id_text text :=
        current_setting('triageharbor.project_id');
    cfg_name text :=
        current_setting('triageharbor.project_name');
    cfg_id uuid;
    stored_name text;
BEGIN
    IF cfg_id_text
       !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    THEN
        RAISE EXCEPTION
            'configured project id is not a lowercase canonical UUID';
    END IF;

    cfg_id := cfg_id_text::uuid;

    IF cfg_name !~ '\S' THEN
        RAISE EXCEPTION
            'configured project name is blank';
    END IF;

    IF char_length(cfg_name) > 200 THEN
        RAISE EXCEPTION
            'configured project name exceeds 200 characters';
    END IF;

    INSERT INTO public.projects (id, name)
    VALUES (cfg_id, cfg_name)
    ON CONFLICT (id) DO NOTHING;

    SELECT p.name
    INTO STRICT stored_name
    FROM public.projects AS p
    WHERE p.id = cfg_id
    FOR SHARE;

    IF stored_name IS DISTINCT FROM cfg_name THEN
        RAISE EXCEPTION
            'project % exists with a different name; refusing to modify it',
            cfg_id;
    END IF;
END
$$;

COMMIT;

\echo project provisioning: converged
