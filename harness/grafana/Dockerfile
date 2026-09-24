# The public dashboard (Cloud Run, harness/grafana/cloud.sh deploy): Grafana with the provisioned dashboards baked in,
# anonymous read-only, no login form, no plugin downloads, Grafana's own DB migrated at build time (fast cold starts).
FROM docker.io/grafana/grafana-oss:13.0.2
ENV GF_AUTH_ANONYMOUS_ENABLED=true \
    GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer \
    GF_AUTH_DISABLE_LOGIN_FORM=true \
    GF_AUTH_BASIC_ENABLED=false \
    GF_USERS_ALLOW_SIGN_UP=false \
    GF_DASHBOARDS_MIN_REFRESH_INTERVAL=5s \
    GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH=/etc/grafana/dashboards/pzopt-runs.json \
    GF_ANALYTICS_REPORTING_ENABLED=false \
    GF_ANALYTICS_CHECK_FOR_UPDATES=false \
    GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES=false \
    GF_NEWS_NEWS_FEED_ENABLED=false \
    GF_PLUGINS_PREINSTALL_DISABLED=true \
    GF_SERVER_ENABLE_GZIP=true \
    GF_LOG_LEVEL=warn \
    GF_EXPLORE_ENABLED=false \
    GF_SNAPSHOTS_ENABLED=false
COPY provisioning/dashboards/pzopt.yaml /etc/grafana/provisioning/dashboards/pzopt.yaml
COPY cloud/datasource.yaml /etc/grafana/provisioning/datasources/pzopt.yaml
COPY dashboards/ /etc/grafana/dashboards/
# run the migrations once now instead of at every cold start
RUN PZ_DB_HOST=127.0.0.1 PZ_DB_PASSWORD=x sh -c 'grafana server --homepath=/usr/share/grafana >/tmp/g.log 2>&1 & \
      for i in $(seq 120); do wget -qO- http://127.0.0.1:3000/api/health >/dev/null 2>&1 && break; sleep 1; done; \
      kill %1; wait; ls -la /var/lib/grafana/grafana.db'
