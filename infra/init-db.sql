-- Creates all databases and configures logical replication for Debezium CDC
CREATE DATABASE acquirer_core;
CREATE DATABASE ledger;
CREATE DATABASE card_vault;
CREATE DATABASE token_service;
CREATE DATABASE card_auth;

-- Debezium requires wal_level=logical; set via postgresql.conf in docker-compose.
-- Grant replication permissions to the postgres user (already superuser in Docker).
ALTER ROLE postgres WITH REPLICATION;
