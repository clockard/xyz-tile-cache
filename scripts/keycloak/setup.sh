#!/usr/bin/env bash
# Helper for the local Keycloak instance defined in docker-compose.yml.
#
# Usage:
#   scripts/keycloak/setup.sh           # wait for Keycloak, print test-user token-fetch curls
#   scripts/keycloak/setup.sh --reseed  # delete the realm and re-import it from xyz-tile-cache-realm.json
set -euo pipefail

KC_BASE="${KC_BASE:-http://localhost:8080}"
REALM="xyz-tile-cache"
CLIENT_ID="xyz-tile-cache"
ADMIN_USER="${KC_ADMIN_USER:-admin}"
ADMIN_PASS="${KC_ADMIN_PASS:-admin}"
COMPOSE_SERVICE="${KC_COMPOSE_SERVICE:-keycloak}"

wait_ready() {
  # Keycloak 26 exposes /health/ready on the management port (9000), not 8080.
  # The OIDC discovery endpoint is served on the main port and is a strong
  # signal that the realm machinery is actually ready to issue tokens.
  local ready_url="${KC_BASE}/realms/master/.well-known/openid-configuration"
  echo "Waiting for Keycloak at ${ready_url} ..."
  for _ in $(seq 1 60); do
    if curl -fsS "${ready_url}" >/dev/null 2>&1; then
      echo "Keycloak is ready."
      return 0
    fi
    sleep 2
  done
  echo "Timed out waiting for Keycloak." >&2
  return 1
}

print_token_examples() {
  cat <<EOF

Test users (password is 'password' for all):
  alice — realm role 'admin', group 'admins'
  bob   — group 'team-foresters'
  carol — group 'team-imagery'
  dan   — no roles, no groups

Fetch a token (replace USER):
  TOKEN=\$(curl -s -X POST ${KC_BASE}/realms/${REALM}/protocol/openid-connect/token \\
    -d grant_type=password \\
    -d client_id=${CLIENT_ID} \\
    -d username=USER -d password=password | jq -r .access_token)

Call the tile cache:
  curl -i -H "Authorization: Bearer \$TOKEN" http://localhost:8383/layers
  curl -i -H "Authorization: Bearer \$TOKEN" http://localhost:8383/tilesZYX/<layer>/0/0/0.png
EOF
}

reseed() {
  echo "Re-seeding realm '${REALM}' ..."
  docker compose exec -T "${COMPOSE_SERVICE}" /opt/keycloak/bin/kcadm.sh \
    config credentials --server "http://localhost:8080" --realm master \
    --user "${ADMIN_USER}" --password "${ADMIN_PASS}"
  if docker compose exec -T "${COMPOSE_SERVICE}" /opt/keycloak/bin/kcadm.sh \
      get "realms/${REALM}" >/dev/null 2>&1; then
    docker compose exec -T "${COMPOSE_SERVICE}" /opt/keycloak/bin/kcadm.sh \
      delete "realms/${REALM}"
  fi
  docker compose exec -T "${COMPOSE_SERVICE}" /opt/keycloak/bin/kcadm.sh \
    create realms -f /opt/keycloak/data/import/xyz-tile-cache-realm.json
  echo "Realm '${REALM}' re-imported."
}

main() {
  wait_ready
  if [[ "${1:-}" == "--reseed" ]]; then
    reseed
  fi
  print_token_examples
}

main "$@"
