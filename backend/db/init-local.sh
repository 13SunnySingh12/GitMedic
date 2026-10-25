#!/bin/sh
# Runs once when the local Docker volume is first created (docker-entrypoint-initdb.d).
set -e
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v app_password="$GITMEDIC_APP_PASSWORD" -v ml_password="$GITMEDIC_ML_PASSWORD" \
  -f /setup-roles.sql
