-- One-time database setup, run as the database owner (Supabase: `postgres`).
-- Passwords are psql variables so they never live in this file:
--   psql "<connection-string>" -v app_password='...' -v ml_password='...' -f backend/db/setup-roles.sql
-- Flyway (run by the backend as gitmedic_app) then creates the tables, RLS and grants.
\set ON_ERROR_STOP on

create role gitmedic_app login password :'app_password';
create role gitmedic_ml login password :'ml_password';

create schema if not exists extensions;
create extension if not exists vector with schema extensions;
grant usage on schema extensions to gitmedic_app, gitmedic_ml;

-- On Supabase `postgres` is not a superuser and cannot create a schema owned by another role,
-- so the schema stays with the creator; gitmedic_app gets CREATE and owns every table Flyway
-- makes. The grant option lets migration V2 grant schema usage to gitmedic_ml.
create schema if not exists gitmedic;
grant usage, create on schema gitmedic to gitmedic_app with grant option;
grant usage on schema gitmedic to gitmedic_ml;
