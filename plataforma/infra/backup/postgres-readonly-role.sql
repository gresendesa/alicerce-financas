-- Execute este arquivo conectado como administrador do banco.
-- A senha é solicitada interativamente pelo comando \\password.
\set ON_ERROR_STOP on

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = 'backup_readonly'
  ) THEN
    CREATE ROLE backup_readonly LOGIN;
  END IF;
END
$$;

GRANT CONNECT ON DATABASE postgres TO backup_readonly;
GRANT pg_read_all_data TO backup_readonly;
ALTER ROLE backup_readonly SET default_transaction_read_only = on;
\password backup_readonly
