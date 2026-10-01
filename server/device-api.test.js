const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const net = require('node:net');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');

test('manual and automatic enrollment retain device-scoped authorization', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'arivo-api-test-'));
  const port = await new Promise(resolve => {
    const listener = net.createServer();
    listener.listen(0, '127.0.0.1', () => {
      const available = listener.address().port;
      listener.close(() => resolve(available));
    });
  });
  for (const file of ['server.js', 'device-enrollment.js']) {
    fs.copyFileSync(path.join(__dirname, file), path.join(directory, file));
  }
  const password = crypto.randomBytes(16).toString('hex');
  const legacy = crypto.randomBytes(32).toString('hex');
  fs.writeFileSync(path.join(directory, '.env'), `PORT=${port}\nARIVO_ADMIN_USER=test\nARIVO_ADMIN_PASSWORD=${password}\nARIVO_DEVICE_KEY=${legacy}\n`);
  const child = spawn(process.execPath, [path.join(directory, 'server.js')], { stdio: 'ignore' });
  t.after(async () => {
    if (child.exitCode === null) {
      await new Promise(resolve => { child.once('exit', resolve); child.kill(); });
    }
    assert.ok(path.resolve(directory).startsWith(path.join(os.tmpdir(), 'arivo-api-test-')));
    fs.rmSync(directory, { recursive: true, force: true });
  });
  const base = `http://127.0.0.1:${port}`;
  let ready = false;
  for (let attempt = 0; attempt < 50; attempt++) {
    try { await fetch(base + '/admin/devices'); ready = true; break; }
    catch { await new Promise(resolve => setTimeout(resolve, 100)); }
  }
  assert.ok(ready, 'isolated test server started');
  const json = body => ({ 'content-type': 'application/json', ...body });
  assert.equal((await fetch(base + '/admin/pairing-code', { method: 'POST' })).status, 401);
  const issue = await fetch(base + '/admin/pairing-code', {
    method: 'POST', headers: { authorization: 'Basic ' + Buffer.from('test:' + password).toString('base64') }
  });
  const { code } = await issue.json();
  const enrolled = await fetch(base + '/device/enroll', {
    method: 'POST', headers: json(), body: JSON.stringify({ code, device_id: 'test-phone' })
  });
  assert.equal(enrolled.status, 200);
  const { token } = await enrolled.json();
  assert.equal((await fetch(base + '/device/enroll', {
    method: 'POST', headers: json(), body: JSON.stringify({ code, device_id: 'another-phone' })
  })).status, 401);
  const headers = { 'x-arivo-key': token };
  assert.equal((await fetch(base + '/device/update?device_id=test-phone', { headers })).status, 200);
  assert.equal((await fetch(base + '/device/update?device_id=another-phone', { headers })).status, 403);
  assert.equal((await fetch(base + '/device/command?device_id=another-phone', { headers })).status, 403);
  for (const endpoint of ['heartbeat', 'location']) {
    assert.equal((await fetch(base + '/device/' + endpoint, {
      method: 'POST', headers: json(headers), body: JSON.stringify({ device_id: 'another-phone' })
    })).status, 403);
  }
  assert.equal((await fetch(base + '/device/screen?device_id=another-phone', {
    method: 'POST', headers, body: Buffer.from('not-a-real-screen')
  })).status, 403);
  assert.equal((await fetch(base + '/device/heartbeat', {
    method: 'POST', headers: json(headers), body: JSON.stringify({ device_id: 'test-phone', app_build: 7 })
  })).status, 200);
  assert.equal((await fetch(base + '/device/update', { headers: { 'x-arivo-key': legacy } })).status, 200);
  assert.equal((await fetch(base + '/device/update')).status, 401);
  const automatic = await fetch(base + '/device/auto-enroll', {
    method: 'POST', headers: json(), body: JSON.stringify({ device_id: 'test-phone' })
  });
  assert.equal(automatic.status, 200);
  const registration = await automatic.json();
  assert.match(registration.device_id, /^[a-f0-9]{32}$/);
  assert.notEqual(registration.device_id, 'test-phone');
  assert.equal((await fetch(base + '/device/update?device_id=test-phone', {
    headers: { 'x-arivo-key': registration.token }
  })).status, 403);
  assert.equal((await fetch(base + '/device/update', { headers })).status, 200);
  assert.equal((await fetch(base + '/device/update?device_id=' + registration.device_id, {
    headers: { 'x-arivo-key': registration.token }
  })).status, 200);
});
