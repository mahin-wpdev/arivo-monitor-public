# HTTPS certificate renewal

The production IP certificate is managed by Certbot using the webroot authenticator.
Nginx is installed by aaPanel at `/www/server/nginx/sbin/nginx`.

## HTTP challenge routing

The HTTP virtual host at
`/www/server/panel/vhost/nginx/27.147.201.165-http-redirect.conf`
must serve ACME HTTP-01 challenges before the normal HTTPS redirect:

```nginx
server {
    listen 10.10.10.7:80;
    server_name 27.147.201.165;
    location ^~ /.well-known/acme-challenge/ {
        root /www/wwwroot/27.147.201.165;
        default_type text/plain;
        try_files $uri =404;
    }
    location / {
        return 301 https://$host:8443$request_uri;
    }
}
```

Do not move the redirect back to server scope: server-level returns run before
location selection and prevent certificate renewal. Preserve this exception when
editing the site through aaPanel. Public port 80 must remain reachable.

## Renewal and reload

The existing `snap.certbot.renew.timer` must be enabled and active. The production
lineage is `/etc/letsencrypt/live/27.147.201.165`; the separate staging lineage is
not used for public TLS.

The existing deploy hook
`/etc/letsencrypt/renewal-hooks/deploy/jm-ip-nginx-reload.sh` restricts reloads to the
production lineage, validates Nginx, and reloads the renewed certificate.

Run these commands on the server with authorized administrative access:

```sh
/www/server/nginx/sbin/nginx -t
certbot renew --cert-name 27.147.201.165 --non-interactive --no-random-sleep-on-renew
certbot renew --cert-name 27.147.201.165 --dry-run --run-deploy-hooks --non-interactive --no-random-sleep-on-renew
systemctl is-enabled snap.certbot.renew.timer
systemctl is-active snap.certbot.renew.timer
```

After a routing change, place a temporary text probe in the challenge webroot,
verify HTTP 200 with the exact content from the public IP without following
redirects, and remove the probe. Missing challenge files should return 404.
Check public TLS trust, IP identity, and expiry on both ports 443 and 8443.

## Verification on 2026-10-01

- Backed up and repaired the live HTTP redirect configuration.
- Public HTTP challenge probe returned 200; ordinary requests retained the 8443 redirect.
- Production renewal succeeded, and the deploy hook reloaded Nginx.
- A simulated renewal including deploy hooks succeeded.
- Public certificate trust and IP identity passed on ports 443 and 8443.
- Renewal timer was enabled and active; backend was active with zero restarts.
- Existing release remains 1.1.2+6, required=false, rollout=100%.
- No APK rebuild or signing-key change was needed.
- At the user's request, reinstalled the exact published APK after verifying its
  release SHA-256 and the repository's pinned signer, resetting the previous
  failed update cache.
- After the user completed Android permission prompts, the connected phone sent
  a fresh heartbeat at 2026-10-01T07:38:56.431Z (13:38:56 Dhaka) reporting
  app_version=1.1.2, app_build=6, and update_status=current.
- Authenticated update metadata returned HTTP 200 over trusted public HTTPS;
  downloading the published APK again matched the existing release checksum.

The released Android app limits update checks to a 30-minute interval. After TLS
recovery, a prior failed update status can remain until its next scheduled check;
verify a subsequent device heartbeat rather than treating an old status as a new
TLS failure.
