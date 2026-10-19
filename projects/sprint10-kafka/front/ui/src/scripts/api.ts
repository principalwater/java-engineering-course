import { isUuid } from './operation.ts';

export type Session = { login: string; csrfToken: string; csrfHeader: string; csrfParameter: string; analyst: boolean; analyticsEnabled: boolean };
export type Account = { accountId: string; login: string; fullName: string | null; birthDate: string | null; balanceMinor: number; version: number; profileComplete: boolean };
export type Recipient = { accountId: string; login: string; fullName: string | null };
export type HistoryItem = { operationId: string; type: 'DEPOSIT' | 'WITHDRAW' | 'TRANSFER_IN' | 'TRANSFER_OUT'; amountMinor: number; balanceMinor: number; status: 'SUCCEEDED'; occurredAt: string; counterparty: string | null };
export type NotificationSource = 'ACCOUNTS' | 'CASH' | 'TRANSFER';
export type NotificationItem = { eventId: string; source: NotificationSource; readAt: string | null; type: 'PROFILE_UPDATED' | 'DEPOSIT_SUCCEEDED' | 'DEPOSIT_REJECTED' | 'WITHDRAW_SUCCEEDED' | 'WITHDRAW_REJECTED' | 'TRANSFER_SUCCEEDED' | 'TRANSFER_REJECTED'; amountMinor: number; balanceMinor: number; occurredAt: string };
export type NotificationPage = { items: NotificationItem[]; nextCursor: string | null };
export type Breakdown = { date: string; type: 'DEPOSIT' | 'WITHDRAW' | 'TRANSFER_IN' | 'TRANSFER_OUT'; incomingMinor: number; outgoingMinor: number; operationsCount: number };
export type Analytics = { accountId?: string; month?: string; breakdown: Breakdown[]; available: boolean; days: { date: string; incomingMinor: number; outgoingMinor: number }[]; operationsCount: number; updatedAt: string | null; accountsCount?: number };
export type OperationResult = { operationId: string; status: 'SUCCEEDED' | 'REJECTED' | 'PENDING'; error: string | null; accountId: string | null; balanceMinor: number | null; occurredAt: string | null };

export class ApiError extends Error {
  status: number;
  code: string;
  constructor(status: number, code = 'REQUEST_FAILED') {
    super(`Request failed: ${status} ${code}`);
    this.status = status;
    this.code = code;
  }
}

export async function request(path: string, options: RequestInit = {}): Promise<unknown> {
  const response = await fetch(path, { ...options, credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(15_000), headers: { Accept: 'application/json', ...options.headers } });
  if (!response.ok) {
    const error: unknown = await response.json().catch(() => null);
    const object = error && typeof error === 'object' ? error as Record<string, unknown> : {};
    throw new ApiError(response.status, typeof object.error === 'string' ? object.error : typeof object.code === 'string' ? object.code : 'REQUEST_FAILED');
  }
  if (response.status === 204) return null;
  return response.json();
}

export function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid response object');
  return value as Record<string, unknown>;
}

export function string(value: unknown): string {
  if (typeof value !== 'string') throw new Error('Invalid response string');
  return value;
}

export function integer(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) throw new Error('Invalid response integer');
  return value;
}

function boolean(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new Error('Invalid response boolean');
  return value;
}

function uuid(value: unknown): string {
  if (!isUuid(value)) throw new Error('Invalid response UUID');
  return value;
}

function date(value: unknown): string {
  const text = string(value);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text) || !Number.isFinite(Date.parse(text)) || new Date(`${text}T00:00:00Z`).toISOString().slice(0, 10) !== text) throw new Error('Invalid response date');
  return text;
}

function instant(value: unknown): string {
  const text = string(value);
  if (!/^\d{4}-\d{2}-\d{2}T/.test(text) || !Number.isFinite(Date.parse(text))) throw new Error('Invalid response instant');
  return text;
}

function nullableString(value: unknown): string | null { return value === null ? null : string(value); }

export function parseSession(value: unknown): Session {
  const data = object(value);
  const csrfHeader = string(data.csrfHeader);
  const csrfParameter = string(data.csrfParameter);
  if (!/^[a-zA-Z0-9_-]+$/.test(csrfHeader) || !/^[a-zA-Z0-9_-]+$/.test(csrfParameter)) throw new Error('Invalid CSRF names');
  return { login: string(data.login), csrfToken: string(data.csrfToken), csrfHeader, csrfParameter, analyst: boolean(data.analyst), analyticsEnabled: boolean(data.analyticsEnabled) };
}

export function parseAccount(value: unknown): Account {
  const data = object(value);
  return { accountId: uuid(data.accountId), login: string(data.login), fullName: nullableString(data.fullName), birthDate: data.birthDate === null ? null : date(data.birthDate), balanceMinor: integer(data.balanceMinor), version: integer(data.version), profileComplete: boolean(data.profileComplete) };
}

export function parseRecipient(value: unknown): Recipient {
  const data = object(value);
  return { accountId: uuid(data.accountId), login: string(data.login), fullName: nullableString(data.fullName) };
}

export function parsePage<T>(value: unknown, parseItem: (item: unknown) => T): { items: T[]; nextCursor: string | null } {
  const data = object(value);
  if (!Array.isArray(data.items) || data.items.length > 20) throw new Error('Invalid response page');
  return { items: data.items.map(parseItem), nextCursor: nullableString(data.nextCursor) };
}

export function parseHistoryItem(value: unknown): HistoryItem {
  const data = object(value);
  if (!['DEPOSIT', 'WITHDRAW', 'TRANSFER_IN', 'TRANSFER_OUT'].includes(string(data.type)) || data.status !== 'SUCCEEDED') throw new Error('Invalid history operation');
  return { operationId: uuid(data.operationId), type: data.type as HistoryItem['type'], amountMinor: integer(data.amountMinor), balanceMinor: integer(data.balanceMinor), status: 'SUCCEEDED', occurredAt: instant(data.occurredAt), counterparty: nullableString(data.counterparty) };
}

function parseNotificationItem(value: unknown): NotificationItem {
  const data = object(value);
  if (!['PROFILE_UPDATED', 'DEPOSIT_SUCCEEDED', 'DEPOSIT_REJECTED', 'WITHDRAW_SUCCEEDED', 'WITHDRAW_REJECTED', 'TRANSFER_SUCCEEDED', 'TRANSFER_REJECTED'].includes(string(data.type))) throw new Error('Invalid notification type');
  if (!['ACCOUNTS', 'CASH', 'TRANSFER'].includes(string(data.source))) throw new Error('Invalid notification source');
  function notificationInstant(value: unknown): string {
    const text = instant(value);
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(text)) throw new Error('Invalid notification instant');
    date(text.slice(0, 10));
    return text;
  }
  return { eventId: uuid(data.eventId), source: data.source as NotificationSource, type: data.type as NotificationItem['type'], amountMinor: integer(data.amountMinor), balanceMinor: integer(data.balanceMinor), occurredAt: notificationInstant(data.occurredAt), readAt: data.readAt === null ? null : notificationInstant(data.readAt) };
}

export function parseNotificationPage(value: unknown, expectedAccountId: string): NotificationPage {
  const envelope = object(value);
  if (uuid(envelope.accountId) !== expectedAccountId) throw new Error('Notification account mismatch');
  const page = parsePage(envelope.notifications, parseNotificationItem);
  // Курсор остаётся строкой: последовательность PostgreSQL может быть больше safe integer JavaScript.
  if (page.nextCursor !== null && !/^[1-9]\d*$/.test(page.nextCursor)) throw new Error('Invalid notification cursor');
  return page;
}

export function parseNotificationRead(value: unknown, expectedAccountId: string): NotificationItem {
  const envelope = object(value);
  if (uuid(envelope.accountId) !== expectedAccountId) throw new Error('Notification account mismatch');
  return parseNotificationItem(envelope.notification);
}

export function parseOperationResult(value: unknown, expectedAccountId: string): OperationResult {
  const data = object(value);
  if (!['SUCCEEDED', 'REJECTED', 'PENDING'].includes(string(data.status))) throw new Error('Invalid operation status');
  const accountId = data.accountId === null ? null : uuid(data.accountId);
  if (accountId !== null && accountId !== expectedAccountId || data.status === 'SUCCEEDED' && accountId === null) {
    throw new Error('Operation account mismatch');
  }
  return { operationId: uuid(data.operationId), status: data.status as OperationResult['status'], error: nullableString(data.error), accountId, balanceMinor: data.balanceMinor === null ? null : integer(data.balanceMinor), occurredAt: data.occurredAt === null ? null : instant(data.occurredAt) };
}

export function parseAnalytics(value: unknown, expectedAccountId?: string, expectedMonth?: string): Analytics {
  const data = object(value);
  const available = boolean(data.available);
  if (!available) return { available: false, days: [], breakdown: [], operationsCount: 0, updatedAt: null };
  if (expectedAccountId && uuid(data.accountId) !== expectedAccountId) throw new Error('Analytics account mismatch');
  if (expectedMonth && data.month !== expectedMonth) throw new Error('Analytics month mismatch');
  if (!Array.isArray(data.days) || data.days.length > 31) throw new Error('Invalid analytics days');
  const days = data.days.map(value => {
    const day = object(value);
    const incomingMinor = integer(day.incomingMinor);
    const outgoingMinor = integer(day.outgoingMinor);
    if (incomingMinor < 0 || outgoingMinor < 0) throw new Error('Invalid analytics amount');
    return { date: date(day.date), incomingMinor, outgoingMinor };
  });
  if (new Set(days.map(day => day.date)).size !== days.length) throw new Error('Duplicate analytics date');
  const operationsCount = integer(data.operationsCount);
  if (operationsCount < 0) throw new Error('Invalid analytics count');
  let breakdown: Breakdown[] = [];
  if (expectedAccountId) {
    if (!Array.isArray(data.breakdown) || data.breakdown.length > 124) throw new Error('Invalid analytics breakdown');
    breakdown = data.breakdown.map(value => {
      const row = object(value);
      if (!['DEPOSIT', 'WITHDRAW', 'TRANSFER_IN', 'TRANSFER_OUT'].includes(string(row.type))) throw new Error('Invalid breakdown type');
      const item = { date: date(row.date), type: row.type as Breakdown['type'], incomingMinor: integer(row.incomingMinor), outgoingMinor: integer(row.outgoingMinor), operationsCount: integer(row.operationsCount) };
      if (item.incomingMinor < 0 || item.outgoingMinor < 0 || item.operationsCount < 1 || expectedMonth && !item.date.startsWith(expectedMonth + '-')) throw new Error('Invalid breakdown amount or date');
      return item;
    });
    if (new Set(breakdown.map(row => row.date + row.type)).size !== breakdown.length) throw new Error('Duplicate breakdown');
  }
  return { available, breakdown, ...(expectedAccountId ? { accountId: expectedAccountId, month: expectedMonth } : {}), days: days.sort((a, b) => a.date.localeCompare(b.date)), operationsCount, updatedAt: data.updatedAt === null ? null : instant(data.updatedAt), ...(data.accountsCount === undefined || data.accountsCount === null ? {} : { accountsCount: integer(data.accountsCount) }) };
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'INVALID_PROFILE') return 'Укажите полное имя и дату рождения. Личный счёт доступен клиентам старше 18 лет.';
    if (error.status === 401) return 'Сессия завершилась. Войдите заново; введённые данные остаются в этой вкладке.';
    if (error.status === 403) return 'Действие недоступно. Обновите сессию и проверьте свои права.';
    if (error.status === 409) return 'Данные изменились. Загрузите актуальные значения и проверьте их перед повтором.';
    if (error.status === 429) return 'Слишком много запросов. Подождите немного и попробуйте снова.';
    if (error.status === 400 || error.status === 422) return 'Проверьте введённые данные. Сервер не принял запрос.';
  }
  return 'Не удалось получить ответ. Проверьте соединение и попробуйте ещё раз.';
}
