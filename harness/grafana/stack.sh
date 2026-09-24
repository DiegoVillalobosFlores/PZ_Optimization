#!/usr/bin/env bash
# Grafana + Postgres metrics stack for the harness runs (rootless podman, no sudo, localhost only).
#
#   harness/grafana/stack.sh up        # install + enable + start the user units: the pod pzopt-grafana (postgres:15 +
#                                      # grafana-oss, schema, dashboards) and the follower; both start again at every login
#   harness/grafana/stack.sh down      # stop the follower and the pod (data kept; back at the next login)
#   harness/grafana/stack.sh disable   # stop and remove the autostart
#   harness/grafana/stack.sh status    # pod, follower unit, row counts
#   harness/grafana/stack.sh psql [args]   # psql into the metrics DB
#   harness/grafana/stack.sh logs [grafana|pg|follow]
#   harness/grafana/stack.sh reset     # down + delete every stored metric (asks first)
#
# Grafana: http://127.0.0.1:3000 (anonymous viewer; admin password in ~/.config/pzopt/grafana-admin).
# Postgres: 127.0.0.1:5433, db/user pzopt (password pzopt, localhost only); Grafana reads as role grafana.
# Data lives in ~/.local/share/pzopt-grafana/{pg,grafana}. The follower (user unit pzopt-grafana-follow,
# harness/grafana/ingest.py --follow) streams the live game's logs and imports every finished run under
# harness/runs/ of this checkout and its worktrees. Units in ~/.config/systemd/user/.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
DATA="${PZOPT_GRAFANA_DATA:-$HOME/.local/share/pzopt-grafana}"
POD=pzopt-grafana
PG_IMAGE=docker.io/library/postgres:15
GF_IMAGE=docker.io/grafana/grafana-oss:latest
UNIT=pzopt-grafana-follow
PASSFILE="$HOME/.config/pzopt/grafana-admin"
export PGHOST=127.0.0.1 PGPORT=5433 PGUSER=pzopt PGPASSWORD=pzopt PGDATABASE=pzopt

wait_pg() {
  for _ in $(seq 60); do
    pg_isready -q && return 0
    sleep 1
  done
  echo "postgres did not come up" >&2
  return 1
}

wait_grafana() {
  for _ in $(seq 60); do
    curl -fsS http://127.0.0.1:3000/api/health >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "grafana did not come up (stack.sh logs grafana)" >&2
  return 1
}

up() {
  install_units
  systemctl --user daemon-reload
  systemctl --user enable -q pzopt-grafana.service "$UNIT.service"
  systemctl --user restart pzopt-grafana.service
  systemctl --user restart "$UNIT.service"
  echo "grafana: http://127.0.0.1:3000  (admin password: $PASSFILE; starts with your session: systemctl --user status pzopt-grafana $UNIT)"
}

# the two user units: the pod (oneshot, this script's pod-up) and the follower (restarts until the DB answers)
install_units() {
  local dir="$HOME/.config/systemd/user"
  mkdir -p "$dir"
  cat >"$dir/pzopt-grafana.service" <<UNITEOF
[Unit]
Description=PZ Optimization metrics: Postgres + Grafana pod (harness/grafana)

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=$HERE/stack.sh pod-up
ExecStop=/usr/bin/podman pod stop $POD
TimeoutStartSec=300

[Install]
WantedBy=default.target
UNITEOF
  cat >"$dir/$UNIT.service" <<UNITEOF
[Unit]
Description=PZ Optimization metrics: import finished runs + stream the live game (harness/grafana/ingest.py --follow)
Wants=pzopt-grafana.service
After=pzopt-grafana.service

[Service]
Environment=PGHOST=$PGHOST PGPORT=$PGPORT PGUSER=$PGUSER PGPASSWORD=$PGPASSWORD PGDATABASE=$PGDATABASE
WorkingDirectory=$REPO
ExecStart=/usr/bin/python3 $HERE/ingest.py --follow
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
UNITEOF
}

pod_up() {
  mkdir -p "$DATA/pg" "$DATA/grafana" "$(dirname "$PASSFILE")"
  [[ -s "$PASSFILE" ]] || { head -c 18 /dev/urandom | base64 | tr -d '/+=' >"$PASSFILE"; chmod 600 "$PASSFILE"; }
  python3 "$HERE/dashboards.py"
  if ! podman pod exists "$POD"; then
    podman pod create --name "$POD" -p 127.0.0.1:3000:3000 -p 127.0.0.1:5433:5432 >/dev/null
    podman run -d --pod "$POD" --name "$POD-pg" \
      -e POSTGRES_USER=pzopt -e POSTGRES_PASSWORD=pzopt -e POSTGRES_DB=pzopt \
      -v "$DATA/pg:/var/lib/postgresql/data:Z" "$PG_IMAGE" \
      -c shared_buffers=512MB -c synchronous_commit=off -c max_wal_size=4GB >/dev/null
    podman run -d --pod "$POD" --name "$POD-grafana" \
      -e GF_SECURITY_ADMIN_PASSWORD="$(cat "$PASSFILE")" \
      -e GF_AUTH_ANONYMOUS_ENABLED=true -e GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer \
      -e GF_DASHBOARDS_MIN_REFRESH_INTERVAL=1s \
      -e GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH=/etc/grafana/dashboards/pzopt-runs.json \
      -e GF_ANALYTICS_REPORTING_ENABLED=false -e GF_ANALYTICS_CHECK_FOR_UPDATES=false \
      -e GF_NEWS_NEWS_FEED_ENABLED=false \
      -v "$DATA/grafana:/var/lib/grafana:Z,U" \
      -v "$HERE/provisioning:/etc/grafana/provisioning:ro,z" \
      -v "$HERE/dashboards:/etc/grafana/dashboards:ro,z" \
      "$GF_IMAGE" >/dev/null
  else
    podman pod start "$POD" >/dev/null
  fi
  wait_pg
  psql -q -v ON_ERROR_STOP=1 -f "$HERE/schema.sql" >/dev/null
  wait_grafana
}

down() {
  systemctl --user stop "$UNIT.service" pzopt-grafana.service 2>/dev/null || true
  podman pod exists "$POD" && podman pod stop "$POD" >/dev/null
  echo "stopped until the next login or stack.sh up (data kept in $DATA; stack.sh disable stops the autostart)"
}

status() {
  podman pod ps --filter name="$POD" --format '{{.Name}} {{.Status}}'
  systemctl --user is-active "$UNIT" | sed "s/^/$UNIT: /" || true
  if pg_isready -q 2>/dev/null; then
    psql -At -c "SELECT 'runs ' || count(*) FROM runs UNION ALL
                 SELECT 'frames ' || count(*) FROM frames UNION ALL
                 SELECT 'newest ' || coalesce(max(run), '-') FROM runs WHERE started = (SELECT max(started) FROM runs)"
  fi
}

case "${1:-status}" in
  up) up ;;
  pod-up) pod_up ;;
  disable) systemctl --user disable --now "$UNIT.service" pzopt-grafana.service ;;
  down) down ;;
  status) status ;;
  psql) shift; exec psql "$@" ;;
  logs)
    case "${2:-grafana}" in
      pg) podman logs --tail 100 "$POD-pg" ;;
      follow) journalctl --user -u "$UNIT" -n 100 --no-pager ;;
      *) podman logs --tail 100 "$POD-grafana" ;;
    esac ;;
  reset)
    read -r -p "delete every stored metric in $DATA? [y/N] " a
    [[ "$a" == y ]] || exit 1
    down
    podman pod rm -f "$POD" >/dev/null 2>&1 || true
    podman unshare rm -rf "$DATA" ;;
  *) sed -n 2,14p "$0"; exit 2 ;;
esac
