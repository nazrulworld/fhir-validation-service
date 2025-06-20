#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$PG_USER" --dbname "postgres" <<-EOSQL
    SELECT 'CREATE DATABASE $PG_DATABASE'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$POSTGRES_DB')\gexec
EOSQL