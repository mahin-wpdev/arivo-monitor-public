const fs = require('fs');
const http = require('http');
const https = require('https');
const crypto = require('crypto');
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

function requestJson(url, options = {}, bodyStream = null) {
  return new Promise((resolve, reject) => {
    const client = url.protocol === 'https:' ? https : http;
    const req = client.request(url, options, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => {
        const raw = Buffer.concat(chunks).toString('utf8');
        let data = null;
        try {
          data = raw ? JSON.parse(raw) : {};
        } catch {
          return reject(new Error('Server returned invalid JSON (HTTP ' + res.statusCode + ')'));
        }
        if (res.statusCode < 200 || res.statusCode > 299) {
          return reject(new Error('Server publish request failed (HTTP ' + res.statusCode + '): ' + JSON.stringify(data)));
        }
        resolve(data);
      });
    });
    req.setTimeout(300000, () => req.destroy(new Error('request timeout')));
    req.on('error', reject);
    if (bodyStream) {
      bodyStream.on('error', reject);
      bodyStream.pipe(req);
    } else {
      req.end();
    }
  });
}

function sha256File(file) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    const stream = fs.createReadStream(file);
    stream.on('data', (chunk) => hash.update(chunk));
    stream.on('error', reject);
    stream.on('end', () => resolve(hash.digest('hex')));
  });
}

(async () => {
  const versionName = String(process.env.VERSION_NAME || '').trim();
  const versionCode = Number(process.env.BUILD_NUMBER || 0);
  const required = String(process.env.REQUIRED_UPDATE || '').toLowerCase() === 'true';
  const rollout = Number(process.env.ROLLOUT_PERCENT || 100);
  const notes = String(process.env.RELEASE_NOTES || '').trim();
  const apk = process.env.ARIVO_RELEASE_APK || 'build/app/outputs/flutter-apk/app-release.apk';
  const runtimeConfig = process.env.ARIVO_SOURCE_CONFIG;
  const serverEnvPath = process.env.ARIVO_SERVER_ENV;

  if (!/^\d+\.\d+\.\d+$/.test(versionName)) fail('Invalid VERSION_NAME');
  if (!Number.isInteger(versionCode) || versionCode < 1) fail('Invalid BUILD_NUMBER');
  if (!Number.isFinite(rollout) || rollout < 1 || rollout > 100) fail('Invalid ROLLOUT_PERCENT');
  if (!runtimeConfig || !fs.existsSync(runtimeConfig)) fail('ARIVO_SOURCE_CONFIG missing');
  if (!serverEnvPath || !fs.existsSync(serverEnvPath)) fail('ARIVO_SERVER_ENV missing');
  if (!fs.existsSync(apk)) fail('Release APK missing: ' + apk);

  const runtime = readKv(runtimeConfig);
  const serverEnv = readKv(serverEnvPath);
  const base = String(runtime.ARIVO_SERVER_BASE_URL || '').replace(/\/$/, '');
  const adminUser = String(serverEnv.ARIVO_ADMIN_USER || '');
  const adminPassword = String(serverEnv.ARIVO_ADMIN_PASSWORD || '');
  if (!base || !adminUser || !adminPassword) fail('Arivo public update API configuration is incomplete');

  const stat = fs.statSync(apk);
  const localSha = await sha256File(apk);
  const auth = 'Basic ' + Buffer.from(adminUser + ':' + adminPassword).toString('base64');
  const dryRun = String(process.env.ARIVO_PUBLISH_DRY_RUN || '').toLowerCase() === 'true';

  if (dryRun) {
    const currentUrl = new URL(base + '/admin/update');
    const current = await requestJson(currentUrl, {
      method: 'GET',
      headers: { Authorization: auth },
    });
    if (!current.available) fail('No current server update is available');
    if (String(current.version_name) !== versionName || Number(current.version_code) !== versionCode) {
      fail('Dry-run server version mismatch');
    }
    if (String(current.sha256 || '').toLowerCase() !== localSha) fail('Dry-run SHA-256 mismatch');
    if (Number(current.size_bytes) !== stat.size) fail('Dry-run APK size mismatch');
    if (Boolean(current.required) !== required) fail('Dry-run required-update mismatch');
    if (Number(current.rollout_percent) !== Math.round(rollout)) fail('Dry-run rollout mismatch');
    console.log('Verified current Arivo ' + versionName + '+' + versionCode);
    console.log('Required=' + required + ' Rollout=' + Math.round(rollout) + '%');
    console.log('Size=' + stat.size);
    console.log('SHA256=' + localSha);
    return;
  }

  const existingUrl = new URL(base + '/admin/update');
  const existing = await requestJson(existingUrl, {
    method: 'GET',
    headers: { Authorization: auth },
  });

  if (existing.available && Number(existing.version_code) === versionCode) {
    const exactMatch =
      String(existing.version_name) === versionName &&
      String(existing.sha256 || '').toLowerCase() === localSha &&
      Number(existing.size_bytes) === stat.size &&
      Boolean(existing.required) === required &&
      Number(existing.rollout_percent) === Math.round(rollout) &&
      String(existing.release_notes || '') === notes;

    if (!exactMatch) {
      fail('Server already has this build number with different APK or update metadata');
    }

    console.log('Arivo ' + versionName + '+' + versionCode + ' is already published; skipping duplicate upload');
    console.log('Size=' + stat.size);
    console.log('SHA256=' + localSha);
    return;
  }

  if (existing.available && Number(existing.version_code) > versionCode) {
    fail('Refusing to publish an older build over the current server update');
  }

  const publishUrl = new URL(base + '/admin/update-apk');
  publishUrl.searchParams.set('version_name', versionName);
  publishUrl.searchParams.set('version_code', String(versionCode));
  publishUrl.searchParams.set('required', required ? '1' : '0');
  publishUrl.searchParams.set('rollout_percent', String(Math.round(rollout)));
  publishUrl.searchParams.set('release_notes', notes);

  const published = await requestJson(publishUrl, {
    method: 'POST',
    headers: {
      Authorization: auth,
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': stat.size,
    },
  }, fs.createReadStream(apk));

  if (!published.ok) fail('Arivo server did not confirm publish');
  if (String(published.version_name) !== versionName || Number(published.version_code) !== versionCode) {
    fail('Published version metadata mismatch');
  }
  if (String(published.sha256 || '').toLowerCase() !== localSha) fail('Published SHA-256 mismatch');
  if (Number(published.size_bytes) !== stat.size) fail('Published APK size mismatch');

  const verifyUrl = new URL(base + '/admin/update');
  const verified = await requestJson(verifyUrl, {
    method: 'GET',
    headers: { Authorization: auth },
  });

  if (!verified.available) fail('Published update is not available');
  if (String(verified.version_name) !== versionName || Number(verified.version_code) !== versionCode) {
    fail('Server verification version mismatch');
  }
  if (String(verified.sha256 || '').toLowerCase() !== localSha) fail('Server verification SHA-256 mismatch');
  if (Number(verified.size_bytes) !== stat.size) fail('Server verification size mismatch');
  if (Boolean(verified.required) !== required) fail('Server verification required-update mismatch');
  if (Number(verified.rollout_percent) !== Math.round(rollout)) fail('Server verification rollout mismatch');

  console.log('Published Arivo ' + versionName + '+' + versionCode);
  console.log('Required=' + required + ' Rollout=' + Math.round(rollout) + '%');
  console.log('Size=' + stat.size);
  console.log('SHA256=' + localSha);
})().catch((error) => {
  fail(error && error.message ? error.message : String(error));
});
