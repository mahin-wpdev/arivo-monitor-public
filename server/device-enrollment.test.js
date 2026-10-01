const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createEnrollmentStore } = require('./device-enrollment');

test('automatic registrations have independent persisted identities and enforce capacity', t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'arivo-enrollment-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const store = createEnrollmentStore(directory);
  const first = store.registerAuto();
  const second = store.registerAuto();
  assert.notEqual(first.device_id, second.device_id);
  assert.equal(createEnrollmentStore(directory).authenticate(first.token), first.device_id);
  assert.equal(store.authenticate(second.token), second.device_id);
  const persisted = fs.readFileSync(path.join(directory, 'device-tokens.json'), 'utf8');
  assert.ok(!persisted.includes(first.token));
  const tokens = JSON.parse(persisted);
  for (let index = 2; index < 1000; index++) tokens['test-' + index] = { device_id: 'placeholder' };
  fs.writeFileSync(path.join(directory, 'device-tokens.json'), JSON.stringify(tokens));
  assert.equal(store.registerAuto(), null);
  assert.equal(store.authenticate(first.token), first.device_id);
});

test('pairing is one-time, scoped, hashed at rest, and survives restart', t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'arivo-enrollment-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const store = createEnrollmentStore(directory);
  const issued = store.issue();
  const codes = fs.readFileSync(path.join(directory, 'pairing-codes.json'), 'utf8');
  assert.ok(!codes.includes(issued.code.replaceAll('-', '')));
  const token = store.redeem(issued.code, 'test-phone');
  assert.match(token, /^[a-f0-9]{64}$/);
  assert.equal(store.redeem(issued.code, 'other-phone'), null);
  assert.equal(store.authenticate(token), 'test-phone');
  assert.equal(store.authenticate('0'.repeat(64)), null);
  assert.ok(!fs.readFileSync(path.join(directory, 'device-tokens.json'), 'utf8').includes(token));
  assert.equal(createEnrollmentStore(directory).authenticate(token), 'test-phone');
  const replacement = store.redeem(store.issue().code, 'test-phone');
  assert.equal(store.authenticate(token), null);
  assert.equal(store.authenticate(replacement), 'test-phone');
});

test('expired codes and invalid identities are rejected', t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'arivo-enrollment-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  let time = Date.now();
  const store = createEnrollmentStore(directory, () => time);
  const code = store.issue().code;
  assert.equal(store.redeem(code, '../phone'), null);
  time += 10 * 60 * 1000;
  assert.equal(store.redeem(code, 'test-phone'), null);
});
