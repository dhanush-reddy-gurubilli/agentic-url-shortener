-- One-time setup: run as a PostgreSQL superuser, e.g.
--   psql -h localhost -U postgres -f scripts/db-init.sql
-- The application creates all tables itself (Flyway) on first start.
CREATE DATABASE agentic;        -- the application
CREATE DATABASE agentic_test;   -- the test suite (it truncates tables, so it must not share a database with real data)
