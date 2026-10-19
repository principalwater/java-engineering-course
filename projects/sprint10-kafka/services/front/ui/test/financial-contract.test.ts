import assert from 'node:assert/strict';
import test from 'node:test';
import { formatMoney, MAX_AMOUNT_MINOR, parseAmount } from '../src/scripts/money.ts';
import { OperationQueue } from '../src/scripts/operation.ts';
import { parseAccount, parseOperationResult } from '../src/scripts/api.ts';

function storageFixture() {
  const data = new Map<string, string>();
  return { getItem: (key: string) => data.get(key) ?? null, setItem: (key: string, value: string) => { data.set(key, value); }, removeItem: (key: string) => { data.delete(key); } };
}

test('decimal input keeps exact kopecks and rejects ambiguous or overflowing amounts', () => {
  for (const [input, expected] of [['0,01', 1], ['0.29', 29], ['1 250,50', 125050], ['1\u202f250.5', 125050], ['10000000000', MAX_AMOUNT_MINOR]] as const) {
    assert.equal(parseAmount(input), expected);
  }
  for (const input of ['0', '-1', '1e3', '0.001', '1/2', '1,2,3', '12 34', '1.000,00', 'NaN', '10000000000.01', '', '9'.repeat(100)]) {
    assert.throws(() => parseAmount(input), Error, input);
  }
  assert.equal(formatMoney(29), '0,29\u00a0₽');
  assert.equal(formatMoney(-125050), '−1\u00a0250,50\u00a0₽');
  assert.equal(formatMoney(9007199254740991n), '90\u00a0071\u00a0992\u00a0547\u00a0409,91\u00a0₽');
});

test('an unknown transfer survives reload with the same immutable ID and payload until a terminal result', () => {
  const storage = storageFixture();
  const accountId = crypto.randomUUID();
  const recipientId = crypto.randomUUID();
  const original = new OperationQueue(storage, accountId);
  const pending = original.begin('TRANSFER', 29, recipientId);
  assert.throws(() => original.begin('DEPOSIT', 500));
  assert.throws(() => { (pending.command as { amountMinor: number }).amountMinor = 30; }, TypeError);
  const reloaded = new OperationQueue(storage, accountId);
  assert.deepEqual(reloaded.current, pending);
  reloaded.settle(pending.command.operationId, 'PENDING');
  assert.deepEqual(new OperationQueue(storage, accountId).current, pending);
  assert.throws(() => reloaded.settle(crypto.randomUUID(), 'SUCCEEDED'));
  assert.equal(new OperationQueue(storage, crypto.randomUUID()).current, null);
  reloaded.settle(pending.command.operationId, 'SUCCEEDED');
  assert.equal(new OperationQueue(storage, accountId).current, null);
  const next = reloaded.begin('WITHDRAW', 100);
  assert.notEqual(next.command.operationId, pending.command.operationId);
  reloaded.settle(next.command.operationId, 'REJECTED');
  assert.equal(reloaded.current, null);
});

test('unavailable storage prevents a first submission and corrupted recovery data fails closed', () => {
  const failing = { getItem: () => null, setItem: () => { throw new Error('Storage unavailable'); }, removeItem: () => {} };
  const queue = new OperationQueue(failing, crypto.randomUUID());
  assert.throws(() => queue.begin('DEPOSIT', 100));
  assert.equal(queue.current, null);
  assert.throws(() => new OperationQueue({ ...failing, getItem: () => '{broken' }, crypto.randomUUID()));
});

test('financial JSON rejects unsafe or absent balances and keeps date-only profile fields unchanged', () => {
  const payload = { accountId: crypto.randomUUID(), login: 'reader', fullName: null, birthDate: '1990-02-28', balanceMinor: 29, version: 1, profileComplete: false };
  assert.equal(parseAccount(payload).birthDate, '1990-02-28');
  for (const balanceMinor of [undefined, null, '29', 0.29, Number.MAX_SAFE_INTEGER + 1]) assert.throws(() => parseAccount({ ...payload, balanceMinor }));
  for (const birthDate of ['1990-02-31', '1990-02-28T00:00:00Z']) assert.throws(() => parseAccount({ ...payload, birthDate }));
  const pending = parseOperationResult({ operationId: crypto.randomUUID(), status: 'PENDING', error: null, accountId: payload.accountId, balanceMinor: null, occurredAt: null }, payload.accountId);
  assert.equal(pending.balanceMinor, null);
  assert.equal(pending.occurredAt, null);
});

test('nullable pending and rejected outcomes preserve or settle the same owner-bound command without accepting a foreign owner', () => {
  const storage = storageFixture();
  const owner = crypto.randomUUID();
  const queue = new OperationQueue(storage, owner);
  const pending = queue.begin('DEPOSIT', 29);
  const payload = { operationId: pending.command.operationId, status: 'PENDING', error: null, accountId: null, balanceMinor: null, occurredAt: null };
  const waiting = parseOperationResult(payload, owner);
  queue.settle(waiting.operationId, waiting.status);
  assert.deepEqual(new OperationQueue(storage, owner).current, pending);
  assert.throws(() => parseOperationResult({ ...payload, accountId: crypto.randomUUID(), status: 'REJECTED' }, owner));
  assert.throws(() => parseOperationResult({ ...payload, status: 'SUCCEEDED' }, owner));
  assert.deepEqual(queue.current, pending);
  const rejected = parseOperationResult({ ...payload, status: 'REJECTED', error: 'PROFILE_REQUIRED' }, owner);
  queue.settle(rejected.operationId, rejected.status);
  assert.equal(new OperationQueue(storage, owner).current, null);
  assert.notEqual(queue.begin('DEPOSIT', 100).command.operationId, pending.command.operationId);
});
