const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

// Only hashes are persisted. Pairing codes expire and can be redeemed once.
function createEnrollmentStore(directory, clock = Date.now) {
  const codesFile = path.join(directory, 'pairing-codes.json');
  const tokensFile = path.join(directory, 'device-tokens.json');
  const removedFile = path.join(directory, 'removed-devices.json');
  const hash = value => crypto.createHash('sha256').update(value).digest('hex');
  const read = file => {
    if (!fs.existsSync(file)) return {};
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  };
  const write = (file, value) => {
    const temporary = file + '.tmp';
    fs.writeFileSync(temporary, JSON.stringify(value), { mode: 0o600 });
    fs.chmodSync(temporary, 0o600);
    fs.renameSync(temporary, file);
  };
  const normaliseCode = value => String(value || '').replace(/[\s-]/g, '').toUpperCase();
  return {
    registerAuto() {
      // Server-generated identity prevents anonymous callers claiming an existing phone.
      const deviceId = crypto.randomBytes(16).toString('hex');
      const token = crypto.randomBytes(32).toString('hex');
      const tokens = read(tokensFile);
      if (Object.keys(tokens).length >= 1000) return null;
      tokens[hash(token)] = { device_id: deviceId, enrolled_at: new Date(clock()).toISOString() };
      write(tokensFile, tokens);
      return { token, device_id: deviceId };
    },
    issue() {
      const codes = read(codesFile);
      for (const [key, entry] of Object.entries(codes)) {
        if (entry.expires_at <= clock()) delete codes[key];
      }
      if (Object.keys(codes).length >= 20) throw new Error('too_many_pending_codes');
      const code = crypto.randomBytes(10).toString('hex').toUpperCase();
      const expiresAt = clock() + 10 * 60 * 1000;
      codes[hash(code)] = { expires_at: expiresAt };
      write(codesFile, codes);
      return { code: code.match(/.{4}/g).join('-'), expires_at: new Date(expiresAt).toISOString() };
    },
    redeem(code, deviceId) {
      if (!/^[a-zA-Z0-9._-]{1,120}$/.test(String(deviceId || ''))) return null;
      const normalised = normaliseCode(code);
      if (!/^[A-F0-9]{20}$/.test(normalised)) return null;
      const codes = read(codesFile);
      const digest = hash(normalised);
      const entry = codes[digest];
      if (!entry || entry.expires_at <= clock()) return null;
      delete codes[digest];
      // Consume before issuing a token: a failed write cannot reuse this code.
      write(codesFile, codes);
      const token = crypto.randomBytes(32).toString('hex');
      const tokens = read(tokensFile);
      for (const [key, record] of Object.entries(tokens)) {
        if (record.device_id === deviceId) delete tokens[key];
      }
      tokens[hash(token)] = { device_id: deviceId, enrolled_at: new Date(clock()).toISOString() };
      write(tokensFile, tokens);
      const removed = read(removedFile);
      if (removed[deviceId]) {
        delete removed[deviceId];
        write(removedFile, removed);
      }
      return token;
    },
    removeDevice(deviceId) {
      const tokens = read(tokensFile);
      for (const [key, record] of Object.entries(tokens)) {
        if (record.device_id === deviceId) delete tokens[key];
      }
      write(tokensFile, tokens);
      const removed = read(removedFile);
      removed[deviceId] = { removed_at: new Date(clock()).toISOString() };
      write(removedFile, removed);
    },
    isRemoved(deviceId) {
      return Boolean(read(removedFile)[deviceId]);
    },
    authenticate(token) {
      if (!/^[a-f0-9]{64}$/.test(String(token || ''))) return null;
      return read(tokensFile)[hash(token)]?.device_id || null;
    }
  };
}

module.exports = { createEnrollmentStore };
