const fs = require('fs');
const http = require('http');
const https = require('https');
const { URL } = require('url');

function fail(message) {
  console.error(message);
  process.exit(1);
}

function readKv(file) {
  const out = {};
  const text = fs.readFileSync(file, 'utf8');
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const i = line.indexOf('=');
    if (i < 0) continue;
    out[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  return out;
}

function requestJson(url, options = {}, body = null) {
  return new Promise((resolve, reject) => {
    const client = url.protocol === 'https:' ? https : http;
    const req = client.request(url, options, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => {
        const raw = Buffer.concat(chunks).toString('utf8');
        let data;
        try {
          data = raw ? JSON.parse(raw) : {};
        } catch {
          return reject(new Error('Server returned invalid JSON (HTTP ' + res.statusCode + ')'));
        }
        if (res.statusCode < 200 || res.statusCode > 299) {
          return reject(new Error('Server request failed (HTTP ' + res.statusCode + '): ' + JSON.stringify(data)));
        }
        resolve(data);
      });
    });
    req.setTimeout(60000, () => req.destroy(new Error('request timeout')));
    req.on('error', reject);
    if (body) req.end(body);
    else req.end();
  });
}

(async () => {
  const runtimeConfig = process.env.ARIVO_SOURCE_CONFIG;
  const serverEnvPath = process.env.ARIVO_SERVER_ENV;
  const required = String(process.env.REQUIRED_UPDATE || '').toLowerCase() === 'true';
  const rollout = Number(process.env.ROLLOUT_PERCENT || 100);
  const notesOverride = String(process.env.RELEASE_NOTES_OVERRIDE || '');

  if (!runtimeConfig || !fs.existsSync(runtimeConfig)) fail('ARIVO_SOURCE_CONFIG missing');
  if (!serverEnvPath || !fs.existsSync(serverEnvPath)) fail('ARIVO_SERVER_ENV missing');
  if (!Number.isFinite(rollout) || rollout < 1 || rollout > 100) fail('Invalid ROLLOUT_PERCENT');

  const runtime = readKv(runtimeConfig);
  const serverEnv = readKv(serverEnvPath);
  const base = String(runtime.ARIVO_SERVER_BASE_URL || '').replace(/\/$/, '');
  const adminUser = String(serverEnv.ARIVO_ADMIN_USER || '');
  const adminPassword = String(serverEnv.ARIVO_ADMIN_PASSWORD || '');
  if (!base || !adminUser || !adminPassword) fail('Arivo public update API configuration is incomplete');

  const auth = 'Basic ' + Buffer.from(adminUser + ':' + adminPassword).toString('base64');
  const headers = { Authorization: auth };

  const current = await requestJson(new URL(base + '/admin/update'), {
    method: 'GET',
    headers,
  });
  if (!current.available) fail('No published Arivo update exists');

  const payload = {
    required,
    rollout_percent: Math.round(rollout),
  };
  if (notesOverride.trim()) payload.release_notes = notesOverride.trim();

  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  const updated = await requestJson(new URL(base + '/admin/update-settings'), {
    method: 'POST',
    headers: {
      ...headers,
      'Content-Type': 'application/json',
      'Content-Length': body.length,
    },
  }, body);

  if (!updated.ok) fail('Server did not confirm update settings');
  if (Boolean(updated.required) !== required) fail('Required-update verification failed');
  if (Number(updated.rollout_percent) !== Math.round(rollout)) fail('Rollout verification failed');

  const verified = await requestJson(new URL(base + '/admin/update'), {
    method: 'GET',
    headers,
  });
  if (!verified.available) fail('Update disappeared after settings change');
  if (Number(verified.version_code) !== Number(current.version_code)) fail('Published build changed unexpectedly');
  if (String(verified.sha256 || '') !== String(current.sha256 || '')) fail('Published APK changed unexpectedly');
  if (Boolean(verified.required) !== required) fail('Final required-update verification failed');
  if (Number(verified.rollout_percent) !== Math.round(rollout)) fail('Final rollout verification failed');

  console.log('Managed Arivo ' + verified.version_name + '+' + verified.version_code);
  console.log('Required=' + verified.required + ' Rollout=' + verified.rollout_percent + '%');
  console.log('SHA256=' + verified.sha256);
})().catch((error) => {
  fail(error && error.message ? error.message : String(error));
});
