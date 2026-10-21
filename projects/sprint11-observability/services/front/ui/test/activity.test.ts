import assert from 'node:assert/strict';
import test from 'node:test';
import { UiActivityBuffer } from '../src/scripts/activity.ts';
import { parseAnalytics } from '../src/scripts/api.ts';
import { filterAnalytics } from '../src/scripts/analytics.ts';

test('UI activity retries the same immutable action without owner, time, money or form values', async t => {
  const bodies: Record<string, unknown>[] = [];
  let failing = true;
  t.mock.method(globalThis, 'fetch', async (path: string | URL | Request, options?: RequestInit) => {
    assert.equal(path, '/ui/analytics/activities');
    bodies.push(JSON.parse(String(options?.body)) as Record<string, unknown>);
    if (failing) return Response.json({ error: 'UNAVAILABLE' }, { status: 503 });
    return new Response(null, { status: 204 });
  });
  const activity = new UiActivityBuffer();
  activity.record('SCREEN_OPENED', 'ANALYTICS');
  await activity.flush({ 'X-CSRF-TOKEN': 'fixture' });
  failing = false;
  activity.record('FORM_OPENED', 'PAYMENTS');
  await activity.flush({ 'X-CSRF-TOKEN': 'fixture' });
  assert.deepEqual(bodies[0], bodies[1]);
  assert.match(String(bodies[0]!.eventId), /^[0-9a-f-]{36}$/);
  assert.deepEqual(bodies[0], { eventId: bodies[0]!.eventId, type: 'SCREEN_OPENED', section: 'ANALYTICS' });
  assert.deepEqual(bodies[2], { eventId: bodies[2]!.eventId, type: 'FORM_OPENED', section: 'PAYMENTS' });
  assert.notEqual(bodies[0]!.eventId, bodies[2]!.eventId);
  await activity.flush({ 'X-CSRF-TOKEN': 'fixture' });
  assert.equal(bodies.length, 3);
});

test('owned monthly breakdown preserves exact directions and filters actual rows without inventing empty days', () => {
  const accountId = crypto.randomUUID();
  const payload = { available: true, accountId, month: '2026-10', days: [{ date: '2026-10-19', incomingMinor: 29, outgoingMinor: 17 }], operationsCount: 2, updatedAt: '2026-10-19T10:00:00Z', breakdown: [
    { date: '2026-10-19', type: 'DEPOSIT', incomingMinor: 29, outgoingMinor: 0, operationsCount: 1 },
    { date: '2026-10-19', type: 'TRANSFER_OUT', incomingMinor: 0, outgoingMinor: 17, operationsCount: 1 }
  ] };
  const data = parseAnalytics(payload, accountId, '2026-10');
  const transfer = filterAnalytics(data, 'TRANSFER_OUT');
  assert.deepEqual(transfer.days, [{ date: '2026-10-19', incomingMinor: 0, outgoingMinor: 17 }]);
  assert.equal(transfer.operationsCount, 1);
  assert.deepEqual(filterAnalytics(data, 'WITHDRAW').days, []);
  assert.equal(filterAnalytics(data, 'WITHDRAW').operationsCount, 0);
  assert.equal(data.operationsCount, 2);
  assert.throws(() => parseAnalytics(payload, crypto.randomUUID(), '2026-10'));
  assert.throws(() => parseAnalytics(payload, accountId, '2026-09'));
  for (const item of [{ ...payload.breakdown[0], type: 'PROFILE_UPDATED' }, { ...payload.breakdown[0], incomingMinor: '29' }, { ...payload.breakdown[0], date: '2026-09-19' }]) {
    assert.throws(() => parseAnalytics({ ...payload, breakdown: [item] }, accountId, '2026-10'));
  }
});

test('terminal UI rejection discards the rejected action while later real gestures can still be sent', async t => {
  const bodies: Record<string, unknown>[] = [];
  t.mock.method(globalThis, 'fetch', async (_path: string | URL | Request, options?: RequestInit) => {
    bodies.push(JSON.parse(String(options?.body)) as Record<string, unknown>);
    return bodies.length === 1 ? Response.json({}, { status: 429 }) : new Response(null, { status: 204 });
  });
  const activity = new UiActivityBuffer();
  activity.record('SCREEN_OPENED', 'HISTORY');
  await activity.flush({});
  activity.record('FORM_OPENED', 'PROFILE');
  await activity.flush({});
  assert.equal(bodies.length, 2);
  assert.notEqual(bodies[0]!.eventId, bodies[1]!.eventId);
  assert.equal(bodies[1]!.section, 'PROFILE');
});
