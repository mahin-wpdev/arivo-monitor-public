const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { URL } = require('url');
const { createEnrollmentStore } = require('./device-enrollment');

const ROOT = __dirname;
const ENV = {};
for (const line of fs.readFileSync(path.join(ROOT, '.env'), 'utf8').split(/\r?\n/)) {
  if (!line || !line.includes('=')) continue;
  const i = line.indexOf('=');
  ENV[line.slice(0, i).trim()] = line.slice(i + 1).trim();
}
const PORT = Number(ENV.PORT || 8787);
const DEVICE_KEY = ENV.ARIVO_DEVICE_KEY || '';
const ADMIN_USER = ENV.ARIVO_ADMIN_USER || 'admin';
const ADMIN_PASSWORD = ENV.ARIVO_ADMIN_PASSWORD || '';
const DATA = path.resolve(ROOT, ENV.DATA_DIR || './data');
const PUBLIC = path.join(ROOT, 'public');
for (const dir of ['locations', 'screenshots', 'live', 'updates', 'backups', 'diagnostics']) {
  fs.mkdirSync(path.join(DATA, dir), { recursive: true });
}
const devicesFile = path.join(DATA, 'devices.json');
const commandsFile = path.join(DATA, 'commands.json');
const settingsFile = path.join(DATA, 'settings.json');
const updateFile = path.join(DATA, 'updates', 'arivo-monitor.apk');
const updateMetaFile = path.join(DATA, 'updates', 'update.json');
const updateHistoryFile = path.join(DATA, 'updates', 'history.jsonl');
const liveLogFile = path.join(DATA, 'live-sessions.jsonl');
const storageHealthFile = path.join(DATA, 'storage-health.json');
const enrollment = createEnrollmentStore(DATA);
let enrollmentAttempts = 0;
let enrollmentWindow = Date.now();
const safe = v => String(v || '').replace(/[^a-zA-Z0-9._-]/g, '').slice(0, 120);
const now = () => new Date().toISOString();
const sha256File = file => {
  const hash = crypto.createHash('sha256');
  hash.update(fs.readFileSync(file));
  return hash.digest('hex');
};
const readJson = (file, fallback = {}) => {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return fallback; }
};
const writeJson = (file, value) => {
  const tmp = file + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(value, null, 2));
  fs.renameSync(tmp, file);
};
const sendJson = (res, code, value) => {
  const body = Buffer.from(JSON.stringify(value));
  res.writeHead(code, {
    'content-type': 'application/json',
    'content-length': body.length,
    'cache-control': 'no-store'
  });
  res.end(body);
};
const readBody = (req, limit) => new Promise((resolve, reject) => {
  const chunks = [];
  let size = 0;
  req.on('data', chunk => {
    size += chunk.length;
    if (size > limit) reject(new Error('too_large'));
    else chunks.push(chunk);
  });
  req.on('end', () => resolve(Buffer.concat(chunks)));
  req.on('error', reject);
});
const appendJsonl = (file, value) => fs.appendFileSync(file, JSON.stringify(value) + '\n');
const jsonLines = (file, max = 2000) => {
  if (!fs.existsSync(file)) return [];
  const raw = fs.readFileSync(file, 'utf8').trim();
  if (!raw) return [];
  return raw.split(/\r?\n/).filter(Boolean).slice(-max)
    .map(line => { try { return JSON.parse(line); } catch { return null; } })
    .filter(Boolean);
};
const updateDevice = (id, patch) => {
  const all = readJson(devicesFile, {});
  all[id] = { ...(all[id] || {}), ...patch, device_id: id };
  writeJson(devicesFile, all);
  return all[id];
};
const getSettings = () => {
  const saved = readJson(settingsFile, {});
  const days = Number(saved.screenshot_retention_days || 0);
  const low = Number(saved.storage_low_free_percent ?? 10);
  const auto = Number(saved.storage_auto_cleanup_used_percent ?? 90);
  return {
    screenshot_retention_days: [0, 1, 3, 7, 30, 90].includes(days) ? days : 0,
    per_device_retention: saved.per_device_retention && typeof saved.per_device_retention === 'object'
      ? saved.per_device_retention : {},
    storage_low_free_percent: Number.isFinite(low) ? Math.max(1, Math.min(50, low)) : 10,
    storage_auto_cleanup_used_percent: Number.isFinite(auto) ? Math.max(60, Math.min(99, auto)) : 90
  };
};
const writeScreenshotIndex = (index, rows) => {
  fs.writeFileSync(index, rows.map(x => JSON.stringify(x)).join('\n') + (rows.length ? '\n' : ''));
};
function cleanupScreenshots() {
  const settings = getSettings();
  const root = path.join(DATA, 'screenshots');
  let removed = 0;
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const id = entry.name;
    const days = Number(settings.per_device_retention[id] ?? settings.screenshot_retention_days);
    if (!days) continue;
    const cutoff = Date.now() - days * 86400000;
    const dir = path.join(root, id);
    const index = path.join(dir, 'index.jsonl');
    const rows = jsonLines(index, 100000);
    const keep = [];
    for (const item of rows) {
      const old = item.captured_at && Date.parse(item.captured_at) < cutoff;
      if (!old) { keep.push(item); continue; }
      const file = path.join(dir, safe(item.file));
      try { if (fs.existsSync(file)) fs.unlinkSync(file); } catch {}
      removed++;
    }
    if (keep.length !== rows.length) writeScreenshotIndex(index, keep);
  }
  return removed;
}
function storageStats() {
  const root = path.join(DATA, 'screenshots');
  const stat = fs.statfsSync(root);
  const total = Number(stat.blocks) * Number(stat.bsize);
  const free = Number(stat.bavail) * Number(stat.bsize);
  const used = total - free;
  return { total, free, used, used_percent: total ? (used / total) * 100 : 0, free_percent: total ? (free / total) * 100 : 0 };
}
function cleanupStoragePressure() {
  const settings = getSettings();
  const before = storageStats();
  const threshold = settings.storage_auto_cleanup_used_percent;
  if (before.used_percent < threshold) return { removed: 0, freed_bytes: 0, triggered: false };
  const targetUsed = before.total * Math.max(0.50, (threshold - 5) / 100);
  let need = Math.max(0, before.used - targetUsed);
  const root = path.join(DATA, 'screenshots');
  const items = [];
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const index = path.join(root, entry.name, 'index.jsonl');
    for (const row of jsonLines(index, 100000)) {
      items.push({ id: entry.name, ...row });
    }
  }
  items.sort((a,b) => Date.parse(a.captured_at || 0) - Date.parse(b.captured_at || 0));
  let freed = 0, removed = 0;
  const changed = new Set();
  for (const item of items) {
    if (freed >= need) break;
    const dir = path.join(root, item.id);
    const file = path.join(dir, safe(item.file));
    let bytes = Number(item.bytes || 0);
    try {
      if (fs.existsSync(file)) {
        if (!bytes) bytes = fs.statSync(file).size;
        fs.unlinkSync(file);
      }
      freed += bytes;
      removed++;
      changed.add(item.id);
    } catch {}
  }
  for (const id of changed) {
    const dir = path.join(root, id);
    const index = path.join(dir, 'index.jsonl');
    const rows = jsonLines(index, 100000).filter(x => fs.existsSync(path.join(dir, safe(x.file))));
    writeScreenshotIndex(index, rows);
  }
  return { removed, freed_bytes: freed, triggered: true };
}
function screenshotStats(id) {
  const root = path.join(DATA, 'screenshots');
  const ids = id ? [safe(id)] : fs.readdirSync(root, { withFileTypes: true }).filter(x => x.isDirectory()).map(x => x.name);
  const out = {};
  for (const deviceId of ids) {
    const dir = path.join(root, deviceId);
    const rows = jsonLines(path.join(dir, 'index.jsonl'), 100000);
    const byDay = {};
    let bytes = 0, missing = 0;
    for (const row of rows) {
      const day = String(row.captured_at || '').slice(0, 10) || 'unknown';
      byDay[day] = (byDay[day] || 0) + 1;
      bytes += Number(row.bytes || 0);
      if (!fs.existsSync(path.join(dir, safe(row.file)))) missing++;
    }
    out[deviceId] = { count: rows.length, bytes, missing, by_day: byDay };
  }
  return id ? (out[safe(id)] || { count: 0, bytes: 0, missing: 0, by_day: {} }) : out;
}
function integrityCheck() {
  const stats = screenshotStats();
  let indexed = 0, missing = 0, orphaned = 0;
  const root = path.join(DATA, 'screenshots');
  for (const [id, s] of Object.entries(stats)) {
    indexed += s.count; missing += s.missing;
    const dir = path.join(root, id);
    const indexedFiles = new Set(jsonLines(path.join(dir, 'index.jsonl'), 100000).map(x => safe(x.file)));
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (entry.isFile() && entry.name.toLowerCase().endsWith('.jpg') && !indexedFiles.has(entry.name)) orphaned++;
    }
  }
  return { checked_at: now(), indexed, missing, orphaned, ok: missing === 0 && orphaned === 0 };
}
function createMetadataBackup() {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const file = path.join(DATA, 'backups', 'metadata-' + stamp + '.json');
  const payload = {
    created_at: now(),
    devices: readJson(devicesFile, {}),
    commands: readJson(commandsFile, {}),
    settings: getSettings(),
    update: readJson(updateMetaFile, {}),
    live_sessions: jsonLines(liveLogFile, 5000),
    screenshot_stats: screenshotStats()
  };
  writeJson(file, payload);
  return { file: path.basename(file), bytes: fs.statSync(file).size, created_at: payload.created_at };
}
const deviceIdentity = req => {
  const key = req.headers['x-arivo-key'];
  if (DEVICE_KEY && key === DEVICE_KEY) return { legacy: true };
  const id = enrollment.authenticate(key);
  return id ? { id } : null;
};
const isAdmin = req => {
  const header = req.headers.authorization || '';
  if (!header.startsWith('Basic ')) return false;
  const raw = Buffer.from(header.slice(6), 'base64').toString('utf8');
  return raw === ADMIN_USER + ':' + ADMIN_PASSWORD;
};
const requireAdmin = (req, res) => {
  if (isAdmin(req)) return true;
  res.writeHead(401, {
    'www-authenticate': 'Basic realm="Arivo Monitor"',
    'content-type': 'text/plain'
  });
  res.end('Authentication required');
  return false;
};
async function deviceApi(req, res, url) {
  if (req.method === 'POST' && ['/device/enroll', '/device/auto-enroll'].includes(url.pathname)) {
    if (Date.now() - enrollmentWindow >= 60000) {
      enrollmentAttempts = 0;
      enrollmentWindow = Date.now();
    }
    if (++enrollmentAttempts > 60) return sendJson(res, 429, { error: 'try_again_later' });
    if (url.pathname === '/device/auto-enroll') {
      if (ENV.ARIVO_AUTO_ENROLL === 'false') return sendJson(res, 403, { error: 'automatic_enrollment_disabled' });
      const enrolled = enrollment.registerAuto();
      if (!enrolled) return sendJson(res, 429, { error: 'enrollment_capacity_reached' });
      return sendJson(res, 200, enrolled);
    }
    const data = JSON.parse((await readBody(req, 4096)).toString('utf8') || '{}');
    const token = enrollment.redeem(data.code, data.device_id);
    if (!token) return sendJson(res, 401, { error: 'invalid_or_expired_code' });
    return sendJson(res, 200, { token });
  }
  const identity = deviceIdentity(req);
  if (!identity) return sendJson(res, 401, { error: 'unauthorized' });
  const ownsDevice = id => !enrollment.isRemoved(id) && (identity.legacy || identity.id === id);

  if (req.method === 'GET' && url.pathname === '/device/update') {
    const deviceId = safe(url.searchParams.get('device_id') || identity.id);
    if (!ownsDevice(deviceId)) return sendJson(res, 403, { error: 'wrong_device' });
    if (!fs.existsSync(updateFile) || !fs.existsSync(updateMetaFile)) {
      return sendJson(res, 200, { available: false });
    }
    const meta = readJson(updateMetaFile, {});
    const stat = fs.statSync(updateFile);
    const rollout = Math.max(1, Math.min(100, Number(meta.rollout_percent || 100)));
    let eligible = true;
    if (deviceId && rollout < 100) {
      const bucket = parseInt(crypto.createHash('sha256').update(deviceId).digest('hex').slice(0, 8), 16) % 100;
      eligible = bucket < rollout;
    }
    return sendJson(res, 200, {
      available: eligible,
      version_name: String(meta.version_name || ''),
      version_code: Number(meta.version_code || 0),
      required: Boolean(meta.required),
      release_notes: String(meta.release_notes || ''),
      rollout_percent: rollout,
      published_at: meta.published_at || null,
      size_bytes: stat.size,
      sha256: meta.sha256 || sha256File(updateFile),
      download_path: '/device/update-apk'
    });
  }
  if (req.method === 'GET' && url.pathname === '/device/update-apk') {
    if (!fs.existsSync(updateFile)) return sendJson(res, 404, { error: 'update_not_found' });
    const stat = fs.statSync(updateFile);
    res.writeHead(200, {
      'content-type': 'application/vnd.android.package-archive',
      'content-length': stat.size,
      'content-disposition': 'attachment; filename="arivo-monitor.apk"',
      'cache-control': 'no-store'
    });
    return fs.createReadStream(updateFile).pipe(res);
  }

  if (req.method === 'POST' && url.pathname === '/device/diagnostics') {
    const data = JSON.parse((await readBody(req, 65536)).toString('utf8') || '{}');
    const id = safe(data.device_id);
    if (!id) return sendJson(res, 400, { error: 'device_id_required' });
    if (!ownsDevice(id)) return sendJson(res, 403, { error: 'wrong_device' });
    const rawEvents = Array.isArray(data.events) ? data.events.slice(-20) : [];
    const events = rawEvents.map(raw => {
      const context = raw && raw.context && typeof raw.context === 'object' ? raw.context : {};
      return {
        at: typeof raw.at === 'string' && Number.isFinite(Date.parse(raw.at))
          ? new Date(raw.at).toISOString() : now(),
        received_at: now(),
        reason: String(raw.reason || 'unknown').slice(0, 80),
        context: {
          network: String(context.network || '').slice(0, 40),
          battery_percent: context.battery_percent != null && Number.isFinite(Number(context.battery_percent))
            ? Math.max(-1, Math.min(100, Number(context.battery_percent))) : null,
          charging: context.charging === true,
          battery_saver: context.battery_saver === true,
          interactive: context.interactive === true,
          gps_enabled: context.gps_enabled === true,
          app_version: String(context.app_version || '').slice(0, 40),
          app_build: context.app_build != null && Number.isFinite(Number(context.app_build)) ? Number(context.app_build) : null,
          android_sdk: context.android_sdk != null && Number.isFinite(Number(context.android_sdk)) ? Number(context.android_sdk) : null
        }
      };
    });
    const logFile = path.join(DATA, 'diagnostics', id + '.jsonl');
    for (const event of events) appendJsonl(logFile, { device_id: id, ...event });
    if (events.length) updateDevice(id, { last_diagnostic_event: events[events.length - 1] });
    return sendJson(res, 200, { ok: true, stored: events.length });
  }

  if (req.method === 'POST' && url.pathname === '/device/heartbeat') {
    const data = JSON.parse((await readBody(req, 1024 * 1024)).toString('utf8') || '{}');
    const id = safe(data.device_id);
    if (!id) return sendJson(res, 400, { error: 'device_id_required' });
    if (!ownsDevice(id)) return sendJson(res, 403, { error: 'wrong_device' });
    updateDevice(id, { ...data, last_seen: now() });
    return sendJson(res, 200, { ok: true });
  }

  if (req.method === 'POST' && url.pathname === '/device/location') {
    const data = JSON.parse((await readBody(req, 1024 * 1024)).toString('utf8') || '{}');
    const id = safe(data.device_id);
    if (!id) return sendJson(res, 400, { error: 'device_id_required' });
    if (!ownsDevice(id)) return sendJson(res, 403, { error: 'wrong_device' });
    const item = { ...data, device_id: id, recorded_at: now() };
    appendJsonl(path.join(DATA, 'locations', id + '.jsonl'), item);
    updateDevice(id, { last_location: item, last_seen: now() });
    return sendJson(res, 200, { ok: true });
  }
  if (req.method === 'POST' && url.pathname === '/device/screen') {
    const id = safe(url.searchParams.get('device_id'));
    if (!ownsDevice(id)) return sendJson(res, 403, { error: 'wrong_device' });
    const kind = url.searchParams.get('kind') === 'live' ? 'live' : 'periodic';
    const capturedAtMs = Number(url.searchParams.get('captured_at'));
    const capturedAt = Number.isSafeInteger(capturedAtMs) && capturedAtMs > 0
      ? new Date(capturedAtMs).toISOString() : now();
    if (!id) return sendJson(res, 400, { error: 'device_id_required' });
    const image = await readBody(req, 12 * 1024 * 1024);
    if (!image.length) return sendJson(res, 400, { error: 'empty_image' });

    if (kind === 'live') {
      fs.writeFileSync(path.join(DATA, 'live', id + '.jpg'), image);
      const current = readJson(devicesFile, {})[id] || {};
      updateDevice(id, {
        last_live_frame: now(),
        live_frame_count: Number(current.live_frame_count || 0) + 1,
        last_seen: now()
      });
    } else {
      const dir = path.join(DATA, 'screenshots', id);
      fs.mkdirSync(dir, { recursive: true });
      const file = Date.now() + '.jpg';
      fs.writeFileSync(path.join(dir, file), image);
      appendJsonl(path.join(dir, 'index.jsonl'), {
        file, captured_at: capturedAt, bytes: image.length
      });
      updateDevice(id, { last_screenshot: capturedAt, last_seen: now() });
    }
    return sendJson(res, 200, { ok: true, kind });
  }
  if (req.method === 'GET' && url.pathname === '/device/command') {
    const id = safe(url.searchParams.get('device_id'));
    if (!id) return sendJson(res, 400, { error: 'device_id_required' });
    if (!ownsDevice(id)) return sendJson(res, 403, { error: 'wrong_device' });
    const commands = readJson(commandsFile, {});
    return sendJson(res, 200, { live: Boolean(commands[id]?.live) });
  }

  return sendJson(res, 404, { error: 'not_found' });
}

async function adminApi(req, res, url) {
  if (!requireAdmin(req, res)) return;
  if (req.method === 'POST' && url.pathname === '/admin/pairing-code') {
    return sendJson(res, 200, enrollment.issue());
  }
  if (req.method === 'GET' && url.pathname === '/admin/devices') {
    const all = readJson(devicesFile, {});
    const t = Date.now();
    for (const item of Object.values(all)) {
      item.online = Boolean(item.last_seen && t - Date.parse(item.last_seen) < 120000);
    }
    return sendJson(res, 200, Object.values(all));
  }
  if (req.method === 'POST' && url.pathname === '/admin/device-remove') {
    const data = JSON.parse((await readBody(req, 4096)).toString('utf8') || '{}');
    const id = safe(data.device_id);
    if (!id) return sendJson(res, 400, { error: 'device_id_required' });
    const all = readJson(devicesFile, {});
    if (!all[id]) return sendJson(res, 404, { error: 'device_not_found' });
    enrollment.removeDevice(id);
    const commands = readJson(commandsFile, {});
    delete commands[id];
    writeJson(commandsFile, commands);
    delete all[id];
    writeJson(devicesFile, all);
    appendJsonl(liveLogFile, { device_id: id, event: 'device_removed', at: now() });
    const settings = getSettings();
    delete settings.per_device_retention[id];
    writeJson(settingsFile, settings);
    return sendJson(res, 200, { ok: true, history_kept: true });
  }
  if (req.method === 'GET' && url.pathname === '/admin/storage') {
    const screenshotDir = path.join(DATA, 'screenshots');
    const s = storageStats();
    let writable = false;
    try {
      const test = path.join(screenshotDir, '.healthcheck');
      fs.writeFileSync(test, 'ok');
      fs.unlinkSync(test);
      writable = true;
    } catch {}
    const settings = getSettings();
    const health = readJson(storageHealthFile, {});
    return sendJson(res, 200, {
      type: 'HDD',
      path: fs.realpathSync(screenshotDir),
      total_bytes: s.total,
      used_bytes: s.used,
      free_bytes: s.free,
      used_percent: s.used_percent,
      free_percent: s.free_percent,
      writable,
      low_space: s.free_percent <= settings.storage_low_free_percent,
      low_free_percent: settings.storage_low_free_percent,
      auto_cleanup_used_percent: settings.storage_auto_cleanup_used_percent,
      smart: health
    });
  }
  if (req.method === 'GET' && url.pathname === '/admin/storage-devices') {
    return sendJson(res, 200, screenshotStats());
  }
  if (req.method === 'POST' && url.pathname === '/admin/storage-integrity') {
    return sendJson(res, 200, integrityCheck());
  }
  if (req.method === 'POST' && url.pathname === '/admin/storage-backup') {
    return sendJson(res, 200, { ok: true, ...createMetadataBackup() });
  }
  if (req.method === 'GET' && url.pathname === '/admin/update') {
    if (!fs.existsSync(updateFile) || !fs.existsSync(updateMetaFile)) {
      return sendJson(res, 200, { available: false });
    }
    const meta = readJson(updateMetaFile, {});
    const stat = fs.statSync(updateFile);
    return sendJson(res, 200, {
      available: true,
      ...meta,
      size_bytes: stat.size
    });
  }
  if (req.method === 'POST' && url.pathname === '/admin/update-apk') {
    const versionName = String(url.searchParams.get('version_name') || '').trim().slice(0, 40);
    const versionCode = Number(url.searchParams.get('version_code') || 0);
    const required = url.searchParams.get('required') === '1';
    const releaseNotes = String(url.searchParams.get('release_notes') || '').trim().slice(0, 2000);
    const rolloutPercent = Math.max(1, Math.min(100, Number(url.searchParams.get('rollout_percent') || 100)));
    if (!versionName || !Number.isInteger(versionCode) || versionCode < 1) {
      return sendJson(res, 400, { error: 'version_name_and_code_required' });
    }
    const apk = await readBody(req, 250 * 1024 * 1024);
    if (apk.length < 4 || apk[0] !== 0x50 || apk[1] !== 0x4b) {
      return sendJson(res, 400, { error: 'invalid_apk' });
    }
    const tmp = updateFile + '.tmp';
    fs.writeFileSync(tmp, apk);
    fs.renameSync(tmp, updateFile);
    const meta = {
      version_name: versionName,
      version_code: versionCode,
      required,
      release_notes: releaseNotes,
      rollout_percent: rolloutPercent,
      published_at: now(),
      sha256: sha256File(updateFile)
    };
    writeJson(updateMetaFile, meta);
    appendJsonl(updateHistoryFile, { event: 'published', ...meta, size_bytes: apk.length });
    return sendJson(res, 200, { ok: true, ...meta, size_bytes: apk.length });
  }
  if (req.method === 'POST' && url.pathname === '/admin/update-settings') {
    const data = JSON.parse((await readBody(req, 1024 * 1024)).toString('utf8') || '{}');
    const meta = readJson(updateMetaFile, {});
    if (!fs.existsSync(updateFile) || !meta.version_code) {
      return sendJson(res, 404, { error: 'update_not_found' });
    }
    meta.required = Boolean(data.required);
    if (data.rollout_percent != null) meta.rollout_percent = Math.max(1, Math.min(100, Number(data.rollout_percent)));
    if (data.release_notes != null) meta.release_notes = String(data.release_notes).slice(0, 2000);
    meta.updated_at = now();
    writeJson(updateMetaFile, meta);
    appendJsonl(updateHistoryFile, { event: 'settings_updated', ...meta });
    return sendJson(res, 200, { ok: true, ...meta });
  }
  if (req.method === 'GET' && url.pathname === '/admin/update-history') {
    return sendJson(res, 200, jsonLines(updateHistoryFile, 200).reverse());
  }

  if (req.method === 'GET' && url.pathname === '/admin/settings') {
    return sendJson(res, 200, getSettings());
  }
  if (req.method === 'POST' && url.pathname === '/admin/settings') {
    const data = JSON.parse((await readBody(req, 1024 * 1024)).toString('utf8') || '{}');
    const current = getSettings();
    const days = Number(data.screenshot_retention_days ?? current.screenshot_retention_days);
    if (![0, 1, 3, 7, 30, 90].includes(days)) {
      return sendJson(res, 400, { error: 'invalid_retention' });
    }
    const next = {
      screenshot_retention_days: days,
      per_device_retention: data.per_device_retention && typeof data.per_device_retention === 'object'
        ? data.per_device_retention : current.per_device_retention,
      storage_low_free_percent: Math.max(1, Math.min(50, Number(data.storage_low_free_percent ?? current.storage_low_free_percent))),
      storage_auto_cleanup_used_percent: Math.max(60, Math.min(99, Number(data.storage_auto_cleanup_used_percent ?? current.storage_auto_cleanup_used_percent)))
    };
    writeJson(settingsFile, next);
    const removed = cleanupScreenshots();
    const pressure = cleanupStoragePressure();
    return sendJson(res, 200, { ok: true, ...next, removed, pressure });
  }
  if (req.method === 'POST' && url.pathname === '/admin/cleanup-screenshots') {
    const removed = cleanupScreenshots();
    const pressure = cleanupStoragePressure();
    return sendJson(res, 200, { ok: true, removed: removed + pressure.removed, retention_removed: removed, pressure });
  }
  if (req.method === 'GET' && url.pathname === '/admin/history') {
    const id = safe(url.searchParams.get('device_id'));
    const file = path.join(DATA, 'locations', id + '.jsonl');
    let rows = jsonLines(file, 10000);
    const date = url.searchParams.get('date');
    if (date) rows = rows.filter(x => String(x.recorded_at || '').startsWith(date));
    return sendJson(res, 200, rows);
  }

  if (req.method === 'GET' && url.pathname === '/admin/screenshots') {
    const id = safe(url.searchParams.get('device_id'));
    const index = path.join(DATA, 'screenshots', id, 'index.jsonl');
    let rows = jsonLines(index, 100000).reverse();
    const date = String(url.searchParams.get('date') || '').slice(0, 10);
    if (date) rows = rows.filter(x => String(x.captured_at || '').startsWith(date));
    const limit = Math.max(1, Math.min(500, Number(url.searchParams.get('limit') || 100)));
    const offset = Math.max(0, Math.min(100000, Math.floor(Number(url.searchParams.get('offset') || 0))));
    return sendJson(res, 200, rows.slice(offset, offset + limit));
  }
  if (req.method === 'GET' && url.pathname === '/admin/screenshot-stats') {
    const id = safe(url.searchParams.get('device_id'));
    return sendJson(res, 200, screenshotStats(id));
  }
  if (req.method === 'DELETE' && url.pathname === '/admin/screenshot') {
    const id = safe(url.searchParams.get('device_id'));
    const fileName = safe(url.searchParams.get('file'));
    if (!id || !fileName) return sendJson(res, 400, { error: 'device_and_file_required' });
    const dir = path.join(DATA, 'screenshots', id);
    const file = path.join(dir, fileName);
    const index = path.join(dir, 'index.jsonl');
    if (fs.existsSync(file)) fs.unlinkSync(file);
    const rows = jsonLines(index, 100000).filter(x => safe(x.file) !== fileName);
    writeScreenshotIndex(index, rows);
    const all = readJson(devicesFile, {});
    if (all[id]) {
      all[id].last_screenshot = rows.length ? rows[rows.length - 1].captured_at : null;
      writeJson(devicesFile, all);
    }
    return sendJson(res, 200, { ok: true });
  }
  if (req.method === 'POST' && url.pathname === '/admin/screenshots-bulk-delete') {
    const data = JSON.parse((await readBody(req, 2 * 1024 * 1024)).toString('utf8') || '{}');
    const id = safe(data.device_id);
    const files = Array.isArray(data.files) ? data.files.map(safe).filter(Boolean).slice(0, 500) : [];
    if (!id || !files.length) return sendJson(res, 400, { error: 'device_and_files_required' });
    const set = new Set(files);
    const dir = path.join(DATA, 'screenshots', id);
    const index = path.join(dir, 'index.jsonl');
    let removed = 0;
    for (const name of set) {
      const file = path.join(dir, name);
      try { if (fs.existsSync(file)) { fs.unlinkSync(file); removed++; } } catch {}
    }
    const rows = jsonLines(index, 100000).filter(x => !set.has(safe(x.file)));
    writeScreenshotIndex(index, rows);
    return sendJson(res, 200, { ok: true, removed });
  }

  if (req.method === 'POST' && url.pathname === '/admin/live') {
    const data = JSON.parse((await readBody(req, 1024 * 1024)).toString('utf8') || '{}');
    const id = safe(data.device_id);
    if (!id) return sendJson(res, 400, { error: 'device_id_required' });
    const commands = readJson(commandsFile, {});
    const enabled = Boolean(data.enabled);
    commands[id] = {
      ...(commands[id] || {}),
      live: enabled,
      updated_at: now()
    };
    writeJson(commandsFile, commands);
    appendJsonl(liveLogFile, { device_id: id, event: enabled ? 'started' : 'stopped', at: now() });
    return sendJson(res, 200, { ok: true, live: commands[id].live });
  }
  if (req.method === 'GET' && url.pathname === '/admin/live-status') {
    const id = safe(url.searchParams.get('device_id'));
    const device = readJson(devicesFile, {})[id] || {};
    const commands = readJson(commandsFile, {});
    const frameAt = device.last_live_frame || null;
    return sendJson(res, 200, {
      device_id: id,
      live: Boolean(commands[id]?.live),
      last_frame: frameAt,
      frame_age_ms: frameAt ? Math.max(0, Date.now() - Date.parse(frameAt)) : null,
      frame_count: Number(device.live_frame_count || 0),
      sessions: jsonLines(liveLogFile, 500).filter(x => x.device_id === id).slice(-30).reverse()
    });
  }

  return sendJson(res, 404, { error: 'not_found' });
}

function media(req, res, url) {
  if (!requireAdmin(req, res)) return;
  const parts = url.pathname.split('/').filter(Boolean);
  let file = null;
  if (parts[1] === 'live' && parts[2]) {
    file = path.join(DATA, 'live', safe(parts[2]) + (parts[2].endsWith('.jpg') ? '' : '.jpg'));
  } else if (parts[1] === 'screenshot' && parts[2] && parts[3]) {
    file = path.join(DATA, 'screenshots', safe(parts[2]), safe(parts[3]));
  }
  if (!file || !fs.existsSync(file)) return sendJson(res, 404, { error: 'not_found' });
  const headers = {
    'content-type': 'image/jpeg',
    'cache-control': 'no-store'
  };
  if (url.searchParams.get('download') === '1') {
    headers['content-disposition'] = 'attachment; filename="' + path.basename(file) + '"';
  }
  res.writeHead(200, headers);
  fs.createReadStream(file).pipe(res);
}

function dashboard(req, res) {
  if (!requireAdmin(req, res)) return;
  const file = path.join(PUBLIC, 'index.html');
  const body = fs.readFileSync(file);
  res.writeHead(200, {
    'content-type': 'text/html; charset=utf-8',
    'content-length': body.length,
    'cache-control': 'no-store'
  });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://' + (req.headers.host || 'localhost'));
    // Support both a root-mounted service and requests passed through the public
    // /arivo-monitor/ prefix when the reverse proxy does not strip that prefix.
    if (url.pathname === '/arivo-monitor' || url.pathname.startsWith('/arivo-monitor/')) {
      url.pathname = url.pathname.slice('/arivo-monitor'.length) || '/';
    }
    if (url.pathname.startsWith('/device/')) return await deviceApi(req, res, url);
    if (url.pathname.startsWith('/admin/')) return await adminApi(req, res, url);
    if (url.pathname.startsWith('/media/')) return media(req, res, url);
    if (url.pathname === '/' || url.pathname === '/index.html') return dashboard(req, res);
    return sendJson(res, 404, { error: 'not_found' });
  } catch (error) {
    console.error(error);
    return sendJson(res, error.message === 'too_large' ? 413 : 500, {
      error: 'server_error'
    });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('Arivo Monitor server listening on :' + PORT);
  setTimeout(() => {
    try { console.log('Screenshot cleanup removed:', cleanupScreenshots()); } catch (error) { console.error(error); }
  }, 5000);
});
setInterval(() => {
  try {
    cleanupScreenshots();
    cleanupStoragePressure();
  } catch (error) { console.error(error); }
}, 60 * 60 * 1000);
