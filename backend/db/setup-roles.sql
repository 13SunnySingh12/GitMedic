-- One-time database setup, run as the database superuser/owner (Supabase: `postgres`).
-- Passwords are psql variables so they never live in this file:
--   psql "<connection-string>" -v app_password='...' -v ml_password='...' -f backend/db/setup-roles.sql
-- Flyway (run by the backend as gitmedic_app) then creates the tables, RLS and grants.
\set ON_ERROR_STOP on

create role gitmedic_app login password :'app_password';
create role gitmedic_ml login password :'ml_password';

create schema if not exists extensions;
create extension if not exists vector with schema extensions;
grant usage on schema extensions to gitmedic_app, gitmedic_ml;

create schema if not exists gitmedic authorization gitmedic_app;
grant usage on schema gitmedic to gitmedic_ml;
