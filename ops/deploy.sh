#!/usr/bin/env bash
# Run from the repository after ./gradlew test installDist and npm --prefix web run build.
set -euo pipefail
cd "$(dirname "$0")/.."
test -x build/install/reviq/bin/reviq
test -f web/dist/index.html
test -f .env
release=$(date -u +%Y%m%dT%H%M%SZ)
release_dir="/opt/reviq/releases/$release"
backup_dir="/var/backups/reviq/$release"
sudo -n install -d -m 0755 "$release_dir" "$backup_dir"
sudo -n cp -a build/install/reviq/. "$release_dir/"
sudo -n cp -a /etc/apache2/sites-available/reviq.xyz.vhost "$backup_dir/"
sudo -n cp -a /var/www/reviq.xyz/web "$backup_dir/web"
if ! getent passwd reviq >/dev/null; then
    sudo -n useradd --system --home-dir /opt/reviq --shell /usr/sbin/nologin reviq
fi
sudo -n install -d -o root -g reviq -m 0750 /etc/reviq
sudo -n install -o root -g reviq -m 0640 .env /etc/reviq/reviq.env
sudo -n ln -sfn "$release_dir" /opt/reviq/current
sudo -n install -m 0644 ops/reviq.service /etc/systemd/system/reviq.service
sudo -n install -m 0644 ops/apache-reviq.conf /etc/apache2/reviq-app.conf
sudo -n python3 - <<'PY'
from pathlib import Path
p = Path('/etc/apache2/sites-available/reviq.xyz.vhost')
s = p.read_text()
if 'Include /etc/apache2/reviq-app.conf' not in s:
    s = s.replace('</VirtualHost>', '    Include /etc/apache2/reviq-app.conf\n</VirtualHost>')
    p.write_text(s)
PY
sudo -n /usr/sbin/apache2ctl configtest
# Keep ISPConfig-managed stats and the previous default page; replace only application files.
sudo -n systemctl daemon-reload
sudo -n systemctl enable reviq
sudo -n systemctl restart reviq
healthy=0
for attempt in $(seq 1 30); do
    if curl --fail --silent http://127.0.0.1:8088/api/health >/dev/null; then healthy=1; break; fi
    sleep 1
done
if [ "$healthy" != 1 ]; then
    echo 'API health check failed. Inspect journalctl -u reviq; Apache has not been reloaded.' >&2
    exit 1
fi
sudo -n cp -a web/dist/. /var/www/reviq.xyz/web/
sudo -n chown -R web58:client1 /var/www/reviq.xyz/web/assets
sudo -n chown web58:client1 /var/www/reviq.xyz/web/index.html
sudo -n systemctl reload apache2
curl --fail --silent https://reviq.xyz/api/health
printf '\nDeployed %s; backups at %s\n' "$release" "$backup_dir"
