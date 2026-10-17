import http from 'k6/http';
import { check, fail } from 'k6';
import execution from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const fixtures = new SharedArray('checkout fixtures', () => JSON.parse(open(__ENV.FIXTURES)));
const queued = __ENV.PHASE === 'queue';
const backlog = Number(__ENV.BACKLOG);
const catalogLatency = new Trend('catalog_ms', true);
const purchaseLatency = new Trend('purchase_ms', true);
const purchases = new Counter('purchases_completed');
export const options = {
  scenarios: queued ? {
    pending: { executor: 'shared-iterations', exec: 'pending', vus: 4, iterations: backlog, maxDuration: '60s' },
  } : {
    catalog: { executor: 'shared-iterations', exec: 'catalog', vus: 4, iterations: 400, maxDuration: '60s' },
    purchase: { executor: 'shared-iterations', exec: 'purchase', vus: 2, iterations: 20, maxDuration: '60s' },
  },
  thresholds: { checks: ['rate==1'], http_req_failed: ['rate==0'], ...(queued ? {} : { purchases_completed: ['count==20'] }) },
  // Идентификаторы и CSRF не попадают в имена метрик или консольный вывод.
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
};

function assert(result, name, predicate) {
  if (!check(result, { [name]: predicate })) fail(name);
  return result;
}

function field(response, name) {
  const value = response.html().find(`input[name="${name}"]`).first().attr('value');
  if (!value) fail(`Missing form field: ${name}`);
  return value;
}

export function pending() {
  const fixture = fixtures[execution.scenario.iterationInTest];
  const response = http.post(`${__ENV.BASE_URL}/buy`, fixture.form, {
    headers: { Cookie: Object.entries(fixture.cookies).map(([name, value]) => `${name}=${value}`).join('; '), Accept: 'application/json' }, redirects: 0,
    tags: { name: 'pending-checkout' }, responseCallback: http.expectedStatuses(503),
  });
  assert(response, 'unknown payment stays pending', value => value.status === 503 && value.json('code') === 'PAYMENT_PENDING');
}

export function catalog() {
  const response = http.get(`${__ENV.BASE_URL}/items`, { tags: { name: 'catalog' } });
  catalogLatency.add(response.timings.duration);
  assert(response, 'catalog remains available', value => value.status === 200);
}

export function purchase() {
  const fixture = fixtures[backlog + execution.scenario.iterationInTest];
  const jar = http.cookieJar();
  jar.clear(__ENV.BASE_URL);
  Object.entries(fixture.cookies).forEach(([name, value]) => jar.set(__ENV.BASE_URL, name, value));
  const started = Date.now();
  const page = assert(http.get(`${__ENV.BASE_URL}/items`, { tags: { name: 'purchase-catalog' } }),
    'purchase catalog opens', value => value.status === 200);
  const added = http.post(`${__ENV.BASE_URL}/cart/items`, {
    _csrf: field(page, '_csrf'), id: __ENV.PRODUCT_ID, action: 'PLUS',
  }, { redirects: 0, tags: { name: 'cart-add' } });
  assert(added, 'cart mutation redirects', value => value.status === 303);
  const cart = assert(http.get(`${__ENV.BASE_URL}/cart/items`, { tags: { name: 'purchase-cart' } }),
    'purchase cart opens', value => value.status === 200);
  const form = { _csrf: field(cart, '_csrf'), checkoutKey: field(cart, 'checkoutKey'), amountMinor: field(cart, 'amountMinor') };
  const params = { redirects: 0, tags: { name: 'foreground-checkout' } };
  const paid = assert(http.post(`${__ENV.BASE_URL}/buy`, form, params),
    'foreground purchase completes during recovery', value => value.status === 303 && /\/orders\/\d+/.test(value.headers.Location));
  const proof = http.get(`${__ENV.BASE_URL}${paid.headers.Location.split('?')[0]}/payment`, {
    headers: { Accept: 'application/json' }, tags: { name: 'paid-receipt' },
  });
  assert(proof, 'order has a paid receipt', value => value.status === 200 && value.json('status') === 'PAID');
  const replay = http.post(`${__ENV.BASE_URL}/buy`, form, params);
  assert(replay, 'checkout replay returns the original order', value => value.status === 303 && value.headers.Location === paid.headers.Location);
  purchaseLatency.add(Date.now() - started);
  purchases.add(1);
}
