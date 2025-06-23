#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "${PG_USER:-postgres}" --dbname "postgres" <<EOSQL
ALTER USER postgres WITH PASSWORD '${PG_PASSWORD}';
DO \$\$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_database WHERE datname = '${PG_DATABASE}') THEN
      CREATE DATABASE ${PG_DATABASE};
   END IF;
END
\$\$;
EOSQL