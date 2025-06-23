#!/bin/bash
set -e

# Set password for postgres user
psql -v ON_ERROR_STOP=1 --username "$PG_USER" --dbname "postgres" <<-EOSQL
    ALTER USER postgres WITH PASSWORD '$PG_PASSWORD';
    SELECT 'CREATE DATABASE $PG_DATABASE'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$PG_DATABASE')\gexec
EOSQL