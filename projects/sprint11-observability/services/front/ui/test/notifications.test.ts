import assert from 'node:assert/strict';
import test from 'node:test';
import { parseNotificationPage } from '../src/scripts/api.ts';
import type { NotificationItem } from '../src/scripts/api.ts';
import { NotificationFeed } from '../src/scripts/notifications.ts';

function notification(values: Partial<NotificationItem> = {}): NotificationItem {
  return { eventId: crypto.randomUUID(), source: 'CASH', readAt: null, type: 'DEPOSIT_SUCCEEDED', amountMinor: 29, balanceMinor: 125050, occurredAt: '2026-10-19T09:12:34.123456789Z', ...values };
}

function envelope(accountId: string, items: unknown[], nextCursor: unknown = null) {
  return { accountId, notifications: { items, nextCursor } };
}

test('notification JSON keeps exact monetary fields and a large string cursor while rejecting malformed feed data', () => {
  const entry = notification({ type: 'TRANSFER_REJECTED' });
  const profile = notification({ type: 'PROFILE_UPDATED', amountMinor: 0, balanceMinor: 0 });
  const ownerId = crypto.randomUUID();
  const payload = { items: [entry, profile], nextCursor: '9223372036854775807' };
  assert.deepEqual(parseNotificationPage(envelope(ownerId, payload.items, payload.nextCursor), ownerId), payload);
  const invalidItems = [
    ...[undefined, null, '29', 0.29, Number.MAX_SAFE_INTEGER + 1].map(amountMinor => ({ ...entry, amountMinor })),
    ...[undefined, null, '125050', 0.1, Number.MAX_SAFE_INTEGER + 1].map(balanceMinor => ({ ...entry, balanceMinor })),
    { ...entry, eventId: 'invalid' }, { ...entry, type: 'UNKNOWN_EVENT' },
    ...[null, '2026-10-19', '2026-10-19T09:12:34', '2026-02-31T09:12:34Z'].map(occurredAt => ({ ...entry, occurredAt }))
  ];
  for (const item of invalidItems) assert.throws(() => parseNotificationPage(envelope(ownerId, [item]), ownerId));
  for (const nextCursor of [undefined, 20, '', '0', '-1', '1.5', '1e3', ' 20', '20&owner=other']) assert.throws(() => parseNotificationPage({ accountId: ownerId, notifications: { items: [], nextCursor } }, ownerId));
  assert.throws(() => parseNotificationPage(envelope(ownerId, Array.from({ length: 21 }, () => notification())), ownerId));
});

test('feed retries the failed keyset page, preserves existing rows on errors and replaces them on manual refresh', async t => {
  const first = notification();
  const second = notification({ type: 'WITHDRAW_REJECTED', amountMinor: 100 });
  const latest = notification({ type: 'PROFILE_UPDATED', amountMinor: 0, balanceMinor: 0 });
  const ownerId = crypto.randomUUID();
  const responses = [
    Response.json(envelope(ownerId, [first], '9223372036854775806')),
    Response.json({ error: 'DEPENDENCY_UNAVAILABLE' }, { status: 503 }),
    Response.json(envelope(ownerId, [second])),
    Response.json(envelope(crypto.randomUUID(), [notification({ amountMinor: 999 })])),
    Response.json(envelope(ownerId, [latest])),
    Response.json(envelope(ownerId, []))
  ];
  const paths: string[] = [];
  t.mock.method(globalThis, 'fetch', async (path: string | URL | Request) => {
    paths.push(String(path));
    const response = responses.shift();
    assert.ok(response, 'Unexpected notification request');
    return response;
  });
  const feed = new NotificationFeed();
  await feed.load(ownerId);
  assert.deepEqual(feed.items, [first]);
  await feed.load(ownerId, false);
  assert.ok(feed.error instanceof Error);
  assert.equal(feed.loading, false);
  assert.deepEqual(feed.items, [first]);
  await feed.retry(ownerId);
  assert.equal(feed.error, null);
  assert.deepEqual(feed.items, [first, second]);
  assert.equal(feed.nextCursor, null);
  await feed.load(ownerId);
  assert.match((feed.error as Error).message, /Notification account mismatch/);
  assert.deepEqual(feed.items, [first, second]);
  await feed.retry(ownerId);
  assert.deepEqual(feed.items, [latest]);
  await feed.load(ownerId);
  assert.deepEqual(feed.items, []);
  assert.equal(feed.loaded, true);
  assert.equal(feed.error, null);
  await feed.load(ownerId, false);
  assert.deepEqual(paths, [
    '/ui/notifications?limit=20',
    '/ui/notifications?limit=20&before=9223372036854775806',
    '/ui/notifications?limit=20&before=9223372036854775806',
    '/ui/notifications?limit=20', '/ui/notifications?limit=20', '/ui/notifications?limit=20'
  ]);
});

test('late responses and errors cannot replace a newer feed or leak the previous owner rows', async t => {
  const pending: { resolve: (response: Response) => void; reject: (error: Error) => void }[] = [];
  t.mock.method(globalThis, 'fetch', () => new Promise<Response>((resolve, reject) => { pending.push({ resolve, reject }); }));
  const feed = new NotificationFeed();
  const ownerId = crypto.randomUUID();
  const oldEntry = notification();
  const currentEntry = notification({ type: 'TRANSFER_SUCCEEDED' });
  const oldLoad = feed.load(ownerId);
  const currentLoad = feed.load(ownerId);
  assert.equal(feed.loading, true);
  pending[1]!.resolve(Response.json(envelope(ownerId, [currentEntry], '40')));
  await currentLoad;
  pending[0]!.resolve(Response.json(envelope(ownerId, [oldEntry], '50')));
  await oldLoad;
  assert.deepEqual(feed.items, [currentEntry]);
  assert.equal(feed.nextCursor, '40');
  const previousOwnerLoad = feed.load(ownerId, false);
  const nextOwnerId = crypto.randomUUID();
  const nextOwnerLoad = feed.load(nextOwnerId);
  assert.deepEqual(feed.items, []);
  assert.equal(feed.loaded, false);
  pending[2]!.reject(new Error('Previous owner request failed'));
  await previousOwnerLoad;
  assert.equal(feed.loading, true);
  assert.equal(feed.error, null);
  pending[3]!.resolve(Response.json(envelope(nextOwnerId, [])));
  await nextOwnerLoad;
  assert.equal(feed.loading, false);
  assert.equal(feed.loaded, true);
  assert.deepEqual(feed.items, []);
});


test('a confirmed read survives a late page response and a foreign read acknowledgement cannot replace it', async t => {
  const ownerId = crypto.randomUUID();
  const entry = notification();
  const read = { ...entry, readAt: '2026-10-19T09:15:00Z' };
  let resolvePage: (response: Response) => void = () => {};
  const sent: string[] = [];
  let refresh = false;
  let foreign = false;
  t.mock.method(globalThis, 'fetch', async (path: string | URL | Request, options?: RequestInit) => {
    if (String(path) === '/ui/notifications/read') {
      sent.push(String(options?.body));
      return Response.json({ accountId: foreign ? crypto.randomUUID() : ownerId, notification: read });
    }
    if (refresh) return new Promise<Response>(resolve => { resolvePage = resolve; });
    return Response.json(envelope(ownerId, [entry]));
  });
  const feed = new NotificationFeed();
  await feed.load(ownerId);
  refresh = true;
  const pending = feed.load(ownerId);
  await feed.markRead(ownerId, entry.source, entry.eventId, { 'X-CSRF-TOKEN': 'fixture' });
  resolvePage(Response.json(envelope(ownerId, [entry])));
  await pending;
  assert.deepEqual(feed.items, [read]);
  assert.deepEqual(JSON.parse(sent[0]!), { source: entry.source, eventId: entry.eventId });
  foreign = true;
  await assert.rejects(feed.markRead(ownerId, entry.source, entry.eventId, {}), /Notification account mismatch/);
  assert.deepEqual(feed.items, [read]);
});
