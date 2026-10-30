#!/bin/bash
# T03 — tạo 5 database + 5 tài khoản riêng, quyền tách thật.
# Chạy một lần khi volume dữ liệu mới (docker-entrypoint-initdb.d).
# F27 fix: mật khẩu KHÔNG nối thẳng vào SQL literal; truyền qua biến psql và để psql tự
# quote (:'VAR') — mật khẩu chứa ' " $ khoảng trắng vẫn tạo user/DB đúng, không lỗi SQL.
set -euo pipefail

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" --dbname postgres \
     -v IDENTITY_DB_PASSWORD="$IDENTITY_DB_PASSWORD" \
     -v DRIVER_DB_PASSWORD="$DRIVER_DB_PASSWORD" \
     -v TRIP_DB_PASSWORD="$TRIP_DB_PASSWORD" \
     -v MATCHING_DB_PASSWORD="$MATCHING_DB_PASSWORD" \
     -v PAYMENT_DB_PASSWORD="$PAYMENT_DB_PASSWORD" <<SQL
-- identity-service :8081
CREATE USER identity_svc WITH PASSWORD :'IDENTITY_DB_PASSWORD';
CREATE DATABASE identity_db OWNER identity_svc;
REVOKE CONNECT ON DATABASE identity_db FROM PUBLIC;

-- driver-service :8082
CREATE USER driver_svc WITH PASSWORD :'DRIVER_DB_PASSWORD';
CREATE DATABASE driver_db OWNER driver_svc;
REVOKE CONNECT ON DATABASE driver_db FROM PUBLIC;

-- trip-service :8083
CREATE USER trip_svc WITH PASSWORD :'TRIP_DB_PASSWORD';
CREATE DATABASE trip_db OWNER trip_svc;
REVOKE CONNECT ON DATABASE trip_db FROM PUBLIC;

-- matching-service :8084
CREATE USER matching_svc WITH PASSWORD :'MATCHING_DB_PASSWORD';
CREATE DATABASE matching_db OWNER matching_svc;
REVOKE CONNECT ON DATABASE matching_db FROM PUBLIC;

-- payment-service :8085
CREATE USER payment_svc WITH PASSWORD :'PAYMENT_DB_PASSWORD';
CREATE DATABASE payment_db OWNER payment_svc;
REVOKE CONNECT ON DATABASE payment_db FROM PUBLIC;
SQL

echo "T03: 5 DB + 5 users created with isolated permissions."
