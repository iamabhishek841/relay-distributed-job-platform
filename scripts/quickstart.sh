#!/usr/bin/env sh
set -eu

docker compose up --build -d
printf '\nRelay is starting at http://localhost:8080\n'
printf 'Health: http://localhost:8080/actuator/health\n'
printf 'Prometheus/Grafana: docker compose --profile observability up -d\n\n'
