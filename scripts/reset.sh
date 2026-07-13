#!/usr/bin/env sh
set -eu

curl -fsS -X DELETE http://localhost:8080/api/control/reset
printf '\nRelay state reset.\n'
