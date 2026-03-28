-- Creates both databases and configures logical replication for Debezium CDC
CREATE DATABASE payment_gateway;
CREATE DATABASE ledger;

-- Debezium requires wal_level=logical; set via postgresql.conf in docker-compose.
-- Grant replication permissions to the postgres user (already superuser in Docker).
ALTER ROLE postgres WITH REPLICATION;
