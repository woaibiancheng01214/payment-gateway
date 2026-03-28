-- Creates both databases and configures logical replication for Debezium CDC
CREATE DATABASE acquirer_core;
CREATE DATABASE ledger;

-- Debezium requires wal_level=logical; set via postgresql.conf in docker-compose.
-- Grant replication permissions to the postgres user (already superuser in Docker).
ALTER ROLE postgres WITH REPLICATION;
