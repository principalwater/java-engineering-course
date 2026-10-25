import { ApiError, errorMessage, parseAccount, parseAnalytics, parseHistoryItem, parseOperationResult, parsePage, parseRecipient, parseSession, request } from './api.ts';
import type { Account, Analytics, HistoryItem, NotificationItem, Recipient, Session } from './api.ts';
import { renderAnalyticsChart, renderBalanceChart } from './charts.ts';
import { formatMoney, MAX_AMOUNT_MINOR, parseAmount } from './money.ts';
import { OperationQueue } from './operation.ts';
import type { OperationType } from './operation.ts';
import { NotificationFeed } from './notifications.ts';
import { UiActivityBuffer } from './activity.ts';
import { filterAnalytics } from './analytics.ts';

function el<T extends HTMLElement = HTMLElement>(id: string): T {
  const element = document.getElementById(id);
  if (!element) throw new Error(`Missing UI element: ${id}`);
  return element as T;
}
function message(id: string, text: string, kind: 'error' | 'success' | '' = ''): void {
  const element = el(id);
  element.textContent = text;
  element.classList.remove('error', 'success');
  if (kind) element.classList.add(kind);
}
const paymentForm = el<HTMLFormElement>('payment-form');
const paymentFields = el<HTMLFieldSetElement>('payment-fields');
const recipientSelect = el<HTMLSelectElement>('recipient');
const amountInput = el<HTMLInputElement>('amount');
const confirmation = el<HTMLDialogElement>('confirmation');
let session: Session | null = null;
let account: Account | null = null;
let queue: OperationQueue | null = null;
let sending = false;
let profileVersion: number | null = null;
let accountRequest = 0;
let historyRequest = 0;
let historyCursor: string | null = null;
let historyItems: HistoryItem[] = [];
const notifications = new NotificationFeed();
let renderedNotifications: NotificationItem[] | null = null;
let recipients: Recipient[] = [];
let recipientCursor: string | null = null;
let recipientSearch = '';
let recipientRequest = 0;
let analyticsData: Analytics | null = null;
let businessData: Analytics | null = null;
const activity = new UiActivityBuffer();
const openedForms = new Set<string>();
let activeScreen = 'OVERVIEW';
let analyticsRequest = 0;
let businessRequest = 0;
let reviewed: { type: OperationType; amountMinor: number; recipientId?: string } | null = null;
const dateTime = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
const notificationDateTime = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' });
const notificationLabels: Record<NotificationItem['type'], string> = { PROFILE_UPDATED: 'Профиль обновлён', DEPOSIT_SUCCEEDED: 'Счёт пополнен', DEPOSIT_REJECTED: 'Пополнение отклонено', WITHDRAW_SUCCEEDED: 'Снятие выполнено', WITHDRAW_REJECTED: 'Снятие отклонено', TRANSFER_SUCCEEDED: 'Перевод выполнен', TRANSFER_REJECTED: 'Перевод отклонён' };
const typeLabels: Record<string, string> = { TRANSFER: 'Перевод', DEPOSIT: 'Пополнение', WITHDRAW: 'Снятие', TRANSFER_IN: 'Входящий перевод', TRANSFER_OUT: 'Перевод получателю' };

function operationType(): OperationType {
  return paymentForm.querySelector<HTMLInputElement>('input[name="operation-type"]:checked')!.value as OperationType;
}

function updatePaymentFields(): void {
  const pending = queue?.current;
  paymentFields.disabled = !account?.profileComplete || !queue || !!pending || sending;
  document.querySelectorAll<HTMLButtonElement>('[data-quick]').forEach(button => { button.disabled = paymentFields.disabled; });
  el('pending-operation').hidden = !pending;
  el<HTMLButtonElement>('retry-payment').disabled = sending;
  if (pending) {
    const name = pending.path === '/ui/transfers' ? 'Перевод' : typeLabels[pending.command.type!]!;
    el('pending-summary').textContent = `${name} · ${formatMoney(pending.command.amountMinor)}`;
    el('pending-id').textContent = `№ ${pending.command.operationId}`;
  }
}

function changeOperation(type: OperationType): void {
  if (queue?.current || sending) return;
  paymentForm.querySelector<HTMLInputElement>(`input[value="${type}"]`)!.checked = true;
  const transfer = type === 'TRANSFER';
  el('recipient-fields').hidden = !transfer;
  recipientSelect.required = transfer;
  el('amount-label').textContent = transfer ? 'Сумма перевода' : type === 'DEPOSIT' ? 'Сумма пополнения' : 'Сумма снятия';
  el('review-payment').querySelector('span')!.textContent = transfer ? 'Проверить перевод' : 'Проверить операцию';
  message('payment-message', '');
}

function showAccount(data: Account, fillProfile: boolean): void {
  if (account && account.accountId !== data.accountId) {
    document.body.hidden = true;
    window.location.replace('/cabinet');
    throw new Error('Account identity changed');
  }
  account = data;
  el('balance').textContent = formatMoney(data.balanceMinor);
  el('balance-caption').textContent = 'Доступно сейчас';
  el('account-owner').textContent = data.fullName || data.login;
  el('welcome-title').textContent = data.fullName ? `Здравствуйте, ${data.fullName.split(/\s+/).filter(Boolean).slice(1, 2)[0] || data.fullName}.` : 'Ваш день. Ваши планы.';
  el('account-card').setAttribute('aria-busy', 'false');
  el('profile-login').textContent = data.login;
  el('profile-avatar').textContent = (data.fullName || data.login).slice(0, 1).toUpperCase();
  el<HTMLFieldSetElement>('profile-fields').disabled = false;
  if (fillProfile) {
    el<HTMLInputElement>('full-name').value = data.fullName ?? '';
    el<HTMLInputElement>('birth-date').value = data.birthDate ?? '';
    profileVersion = data.version;
  }
  if (!data.profileComplete && !queue?.current) {
    message('payment-message', 'Заполните имя и дату рождения в профиле, чтобы перейти к операциям.');
  }
  updatePaymentFields();
}

async function loadAccount(fillProfile = false): Promise<void> {
  const identity = ++accountRequest;
  try {
    const data = parseAccount(await request('/ui/account'));
    if (identity !== accountRequest) return;
    showAccount(data, fillProfile);
  } catch (error) {
    if (identity !== accountRequest) return;
    el('account-card').setAttribute('aria-busy', 'false');
    el('balance-caption').textContent = account ? 'Не удалось обновить. Показан последний полученный остаток.' : 'Остаток недоступен';
    throw error;
  }
}

function renderHistory(): void {
  const list = el<HTMLOListElement>('history-list');
  list.replaceChildren();
  for (const entry of historyItems) {
    const item = document.createElement('li'); item.className = 'history-row';
    const details = document.createElement('details');
    const summary = document.createElement('summary');
    const incoming = entry.amountMinor > 0;
    const icon = document.createElement('span'); icon.className = `history-icon${incoming ? ' incoming' : ''}`; icon.setAttribute('aria-hidden', 'true'); icon.textContent = incoming ? '↙' : '↗';
    const description = document.createElement('span'); description.className = 'history-description';
    const title = document.createElement('strong'); title.textContent = entry.counterparty || typeLabels[entry.type]!;
    const subtitle = document.createElement('span'); subtitle.textContent = `${typeLabels[entry.type]} · ${dateTime.format(new Date(entry.occurredAt))}`;
    description.append(title, subtitle);
    const amount = document.createElement('span'); amount.className = `history-amount${incoming ? ' incoming' : ''}`; amount.textContent = formatMoney(entry.amountMinor, true);
    summary.append(icon, description, amount);
    const detail = document.createElement('div'); detail.className = 'history-details';
    for (const text of [`Выполнено · ${dateTime.format(new Date(entry.occurredAt))}`, `Остаток после операции: ${formatMoney(entry.balanceMinor)}`, `Номер операции: ${entry.operationId}`]) { const paragraph = document.createElement('p'); paragraph.textContent = text; detail.append(paragraph); }
    details.append(summary, detail); item.append(details); list.append(item);
  }
  el('history-count').textContent = `${historyItems.length}`;
  el('history-state').hidden = historyItems.length > 0;
  if (!historyItems.length) message('history-state', 'Пока тихо. Выполненные операции появятся здесь.');
  el('history-more').hidden = !historyCursor;
  renderBalanceChart(el('balance-chart'), historyItems);
  el('balance-chart-caption').textContent = historyItems.length ? `Остаток после последних ${Math.min(20, historyItems.length)} операций из загруженной истории. Шаг по оси — операция, подписи — даты. Суммы по оси — ₽.` : 'График появится после первой выполненной операции.';
}

async function loadHistory(reset = true): Promise<void> {
  const identity = ++historyRequest;
  el<HTMLButtonElement>('history-more').disabled = true;
  el('history-retry').hidden = true;
  if (!historyItems.length) { el('history-state').hidden = false; message('history-state', 'Загружаем операции…'); }
  const cursor = reset ? null : historyCursor;
  try {
    const data = parsePage(await request(`/ui/history?limit=20${cursor ? `&before=${encodeURIComponent(cursor)}` : ''}`), parseHistoryItem);
    if (identity !== historyRequest) return;
    historyItems = reset ? data.items : [...historyItems, ...data.items];
    historyCursor = data.nextCursor;
    renderHistory();
  } catch (error) {
    if (identity !== historyRequest) return;
    el('history-state').hidden = false;
    message('history-state', `${historyItems.length ? 'Показана ранее загруженная история. ' : ''}${errorMessage(error)}`, 'error');
    el('history-retry').hidden = false;
    if (!historyItems.length) { el('balance-chart').replaceChildren(); const state = document.createElement('p'); state.className = 'chart-state'; state.textContent = 'История недоступна. График появится после успешной загрузки.'; el('balance-chart').append(state); }
  } finally {
    if (identity === historyRequest) el<HTMLButtonElement>('history-more').disabled = false;
  }
}

function renderNotifications(): void {
  const list = el<HTMLOListElement>('notifications-list');
  list.setAttribute('aria-busy', String(notifications.loading));
  if (renderedNotifications !== notifications.items) {
    const opened = new Set([...list.querySelectorAll<HTMLDetailsElement>('details[open]')].map(item => item.dataset.key));
    list.replaceChildren();
    for (const entry of notifications.items) {
      const item = document.createElement('li'); item.className = 'notification-row';
      const details = document.createElement('details');
      details.dataset.key = `${entry.source}:${entry.eventId}`; details.open = opened.has(details.dataset.key);
      const summary = document.createElement('summary');
      const rejected = entry.type.endsWith('_REJECTED');
      const icon = document.createElement('span'); icon.className = `notification-icon${rejected ? ' rejected' : ''}`; icon.setAttribute('aria-hidden', 'true'); icon.textContent = rejected ? '!' : '✓';
      const description = document.createElement('span'); description.className = 'notification-description';
      const title = document.createElement('strong'); title.textContent = notificationLabels[entry.type];
      const time = document.createElement('time'); time.dateTime = entry.occurredAt; time.textContent = notificationDateTime.format(new Date(entry.occurredAt));
      description.append(title, time);
      const label = document.createElement('span'); label.className = 'notification-label'; label.textContent = entry.type === 'PROFILE_UPDATED' ? 'Личные данные' : `Сумма: ${formatMoney(entry.amountMinor)}`;
      summary.append(icon, description, label);
      const detail = document.createElement('div'); detail.className = 'notification-details';
      const texts = entry.type === 'PROFILE_UPDATED' ? ['Изменения личных данных сохранены.'] : [`${rejected ? 'Запрос отклонён' : 'Операция выполнена'}. Сумма: ${formatMoney(entry.amountMinor)}.`, `Остаток на момент события: ${formatMoney(entry.balanceMinor)}. Это снимок, а не текущий баланс.`];
      for (const text of [...texts, `Номер события: ${entry.eventId}`]) { const line = document.createElement('p'); line.textContent = text; detail.append(line); }
      const read = document.createElement('button'); read.type = 'button'; read.className = 'text-button notification-read';
      read.textContent = entry.readAt ? `Прочитано · ${notificationDateTime.format(new Date(entry.readAt))}` : 'Отметить прочитанным';
      read.disabled = entry.readAt !== null;
      read.addEventListener('click', async () => {
        if (!account || !session || entry.readAt) return;
        const ownerId = account.accountId;
        read.disabled = true;
        read.textContent = 'Сохраняем отметку…';
        message('notifications-read-message', '');
        try {
          await notifications.markRead(ownerId, entry.source, entry.eventId, { [session.csrfHeader]: session.csrfToken });
          if (account?.accountId === ownerId) {
            const restoreFocus = document.activeElement === read || document.activeElement === document.body;
            renderNotifications();
            if (restoreFocus) [...list.querySelectorAll<HTMLDetailsElement>('details')].find(item => item.dataset.key === `${entry.source}:${entry.eventId}`)?.querySelector('summary')?.focus({ preventScroll: true });
            message('notifications-read-message', 'Уведомление отмечено прочитанным.', 'success');
          }
        } catch (error) {
          if (account?.accountId === ownerId) { message('notifications-read-message', errorMessage(error), 'error'); read.disabled = false; read.textContent = 'Отметить прочитанным'; }
        }
      });
      detail.append(read);
      details.append(summary, detail); item.append(details); list.append(item);
    }
    renderedNotifications = notifications.items;
  }
  el('notifications-count').textContent = notifications.loaded ? `Загружено: ${notifications.items.length}` : '—';
  const state = el('notifications-state');
  state.classList.toggle('error', notifications.error !== null);
  state.classList.toggle('sr-only', notifications.loaded && notifications.items.length > 0 && !notifications.loading && notifications.error === null);
  state.textContent = notifications.loading ? 'Загружаем уведомления…' : notifications.error !== null ? `${notifications.items.length ? 'Показаны ранее загруженные уведомления. ' : 'Уведомления временно недоступны. '}${errorMessage(notifications.error)}` : !notifications.loaded ? 'Уведомления появятся после подключения счёта.' : notifications.items.length ? `Загружено уведомлений: ${notifications.items.length}.` : 'Уведомлений пока нет. После изменения профиля или операции они появятся здесь с небольшой задержкой.';
  el<HTMLButtonElement>('notifications-refresh').disabled = notifications.loading || !account || !session;
  el('notifications-more').hidden = notifications.nextCursor === null;
  el<HTMLButtonElement>('notifications-more').disabled = notifications.loading || notifications.error !== null;
  el('notifications-retry').hidden = notifications.error === null;
  el<HTMLButtonElement>('notifications-retry').disabled = notifications.loading;
}

async function loadNotifications(reset = true, retry = false): Promise<void> {
  if (!account || !session) return;
  const ownerId = account.accountId;
  const login = session.login;
  const loading = retry ? notifications.retry(ownerId) : notifications.load(ownerId, reset);
  renderNotifications();
  await loading;
  if (account?.accountId !== ownerId || session?.login !== login) return;
  renderNotifications();
}

async function loadRecipients(reset = true): Promise<void> {
  const identity = ++recipientRequest;
  el<HTMLButtonElement>('recipient-search-button').disabled = true;
  el<HTMLButtonElement>('recipient-more').disabled = true;
  message('recipient-status', 'Загружаем получателей…');
  if (reset) {
    recipientSearch = el<HTMLInputElement>('recipient-search').value.trim();
  }
  const params = new URLSearchParams({ limit: '20' });
  if (recipientSearch) params.set('search', recipientSearch);
  if (!reset && recipientCursor) params.set('after', recipientCursor);
  try {
    const page = parsePage(await request(`/ui/recipients?${params}`), parseRecipient);
    if (identity !== recipientRequest) return;
    if (reset) {
      recipients = [];
      recipientSelect.replaceChildren(new Option('Выберите получателя', ''));
    }
    const seen = new Set(recipients.map(item => item.accountId));
    const next = page.items.filter(item => !seen.has(item.accountId) && item.accountId !== account?.accountId);
    recipients.push(...next);
    for (const recipient of next) recipientSelect.add(new Option(recipient.fullName ? `${recipient.fullName} · ${recipient.login}` : recipient.login, recipient.accountId));
    recipientCursor = page.nextCursor;
    el('recipient-more').hidden = !recipientCursor;
    message('recipient-status', recipients.length ? `Найдено: ${recipients.length}${recipientCursor ? ' · есть ещё' : ''}` : 'Получатели не найдены. Попробуйте другое имя.');
  } catch (error) {
    if (identity === recipientRequest) { message('recipient-status', errorMessage(error), 'error'); el('recipient-more').hidden = true; }
  } finally {
    if (identity === recipientRequest) { el<HTMLButtonElement>('recipient-search-button').disabled = false; el<HTMLButtonElement>('recipient-more').disabled = false; }
  }
}

function analyticsUnavailable(business: boolean, title: string, text: string): void {
  const prefix = business ? 'business' : 'analytics';
  el(`${prefix}-content`).hidden = true;
  const state = el(`${prefix}-state`); state.hidden = false;
  if (business) state.textContent = `${title}. ${text}`;
  else { state.querySelector('h3')!.textContent = title; state.querySelector('p')!.textContent = text; }
}

function personalAnalyticsData(): Analytics | null {
  if (!analyticsData || analyticsData.month !== el<HTMLSelectElement>('analytics-month').value) return null;
  const type = el<HTMLSelectElement>('analytics-type').value;
  if (type === 'ALL') return analyticsData;
  return filterAnalytics(analyticsData, type);
}

function renderAnalytics(data: Analytics, business = false): void {
  const prefix = business ? 'business' : 'analytics';
  el(`${prefix}-state`).hidden = true;
  el(`${prefix}-content`).hidden = false;
  el(`${prefix}-incoming`).textContent = formatMoney(data.days.reduce((sum, day) => sum + BigInt(day.incomingMinor), 0n));
  el(`${prefix}-outgoing`).textContent = formatMoney(data.days.reduce((sum, day) => sum + BigInt(day.outgoingMinor), 0n));
  el(`${prefix}-count`).textContent = new Intl.NumberFormat('ru-RU').format(data.operationsCount);
  if (business) el('business-accounts').textContent = data.accountsCount === undefined ? 'Недоступно' : new Intl.NumberFormat('ru-RU').format(data.accountsCount);
  el(`${prefix}-updated`).textContent = data.updatedAt ? `Последнее событие: ${dateTime.format(new Date(data.updatedAt))}. ${business ? 'Текущий месяц UTC.' : `Выбран месяц ${data.month}, время UTC.`} Данные могут поступать с задержкой.` : 'Событий за выбранный период пока нет. Данные могут поступать с задержкой.';
  renderAnalyticsChart(el(`${prefix}-chart`), data);
}

async function loadAnalytics(business = false): Promise<void> {
  const prefix = business ? 'business' : 'analytics';
  const identity = business ? ++businessRequest : ++analyticsRequest;
  const ownerId = account?.accountId;
  const month = el<HTMLSelectElement>('analytics-month').value;
  el(`${prefix}-retry`).hidden = true;
  if (!session?.analyticsEnabled) { analyticsUnavailable(business, 'Аналитика пока не подключена', 'Счёт, переводы и история доступны как обычно.'); return; }
  analyticsUnavailable(business, 'Загружаем аналитику', 'Получаем подтверждённые события за месяц.');
  try {
    const data = parseAnalytics(await request(`/ui/analytics${business ? '/business' : `?month=${encodeURIComponent(month)}`}`), business ? undefined : ownerId, business ? undefined : month);
    if (identity !== (business ? businessRequest : analyticsRequest) || ownerId !== account?.accountId) return;
    if (!data.available) { analyticsUnavailable(business, 'Аналитика временно недоступна', 'Данные не подменяются расчётными значениями. Попробуйте обновить позже.'); el(`${prefix}-retry`).hidden = false; return; }
    if (business) { businessData = data; renderAnalytics(data, true); }
    else { analyticsData = data; const filtered = personalAnalyticsData(); if (filtered) renderAnalytics(filtered); }
  } catch (error) {
    if (identity !== (business ? businessRequest : analyticsRequest) || ownerId !== account?.accountId) return;
    analyticsUnavailable(business, error instanceof ApiError && error.status === 403 ? 'Доступ к сводке ограничен' : 'Не удалось загрузить аналитику', errorMessage(error));
    el(`${prefix}-retry`).hidden = false;
  }
}

async function refreshData(): Promise<void> {
  el<HTMLButtonElement>('refresh-data').disabled = true;
  await Promise.allSettled([loadAccount().catch(error => { message('session-error-text', errorMessage(error)); el('session-error').hidden = false; }), loadHistory(), loadNotifications(), loadAnalytics(), ...(session?.analyst ? [loadAnalytics(true)] : [])]);
  el<HTMLButtonElement>('refresh-data').disabled = false;
}

function rejectionMessage(code: string | null): string {
  const messages: Record<string, string> = { INSUFFICIENT_FUNDS: 'Недостаточно средств на счёте.', RECIPIENT_NOT_FOUND: 'Получатель больше недоступен.', SELF_TRANSFER: 'Выберите другого получателя.', BALANCE_LIMIT: 'Сумма пополнения превысит допустимый остаток на счёте.', PROFILE_REQUIRED: 'Заполните профиль перед новой операцией.', OPERATION_CONFLICT: 'Данные операции не совпали с ранее отправленными. Проверьте историю.' };
  return code && messages[code] ? messages[code]! : 'Операция отклонена. Проверьте сумму, получателя и доступный остаток.';
}

async function sendPending(): Promise<void> {
  if (sending || !queue?.current || !session || !account) return;
  const pending = queue.current;
  sending = true;
  updatePaymentFields();
  message('payment-message', 'Отправляем операцию. Дождитесь результата.');
  el('pending-description').textContent = 'Получаем результат этой операции. Повторный запрос использует тот же номер и сумму.';
  try {
    const result = parseOperationResult(await request(pending.path, { method: 'POST', headers: { 'Content-Type': 'application/json', [session.csrfHeader]: session.csrfToken }, body: JSON.stringify(pending.command) }), account.accountId);
    queue.settle(result.operationId, result.status);
    if (result.status === 'PENDING') { message('payment-message', 'Операция ещё выполняется. Проверьте результат через некоторое время.'); void loadNotifications(); return; }
    ++accountRequest;
    if (result.accountId === account.accountId && result.balanceMinor !== null) showAccount({ ...account, balanceMinor: result.balanceMinor }, false);
    if (result.status === 'SUCCEEDED') { amountInput.value = ''; message('payment-message', `Операция выполнена: ${typeLabels[pending.command.type || 'TRANSFER'].toLowerCase()}. ${formatMoney(pending.command.amountMinor)}`, 'success'); }
    else message('payment-message', rejectionMessage(result.error), 'error');
    await refreshData();
  } catch (error) {
    message('payment-message', `${errorMessage(error)} Результат операции пока неизвестен.`, 'error');
    el('pending-description').textContent = 'Не создавайте новую операцию на эту сумму. Кнопка ниже проверит результат с тем же номером; повторного денежного эффекта не будет.';
  } finally {
    sending = false;
    updatePaymentFields();
  }
}

paymentForm.addEventListener('submit', event => {
  event.preventDefault();
  if (!account || !queue || queue.current || sending) return;
  let amountMinor: number;
  try { amountMinor = parseAmount(amountInput.value); }
  catch { message('payment-message', `Введите сумму от 0,01 ₽ до ${formatMoney(MAX_AMOUNT_MINOR)}, не более двух знаков после запятой.`, 'error'); amountInput.focus(); return; }
  const type = operationType();
  const recipient = type === 'TRANSFER' ? recipients.find(item => item.accountId === recipientSelect.value) : undefined;
  if (type === 'TRANSFER' && !recipient) { message('payment-message', 'Выберите получателя из списка.', 'error'); recipientSelect.focus(); return; }
  reviewed = { type, amountMinor, ...(recipient ? { recipientId: recipient.accountId } : {}) };
  el('confirm-amount').textContent = formatMoney(amountMinor);
  el('confirm-type').textContent = typeLabels[type]!;
  el('confirm-recipient-row').hidden = type !== 'TRANSFER';
  el('confirm-recipient').textContent = recipient ? `${recipient.fullName || recipient.login} · ${recipient.login}` : '';
  el('confirm-account').textContent = type === 'DEPOSIT' ? 'Текущий счёт · зачисление RUB' : 'Текущий счёт · RUB';
  el('confirm-payment').textContent = type === 'TRANSFER' ? 'Подтвердить перевод' : type === 'DEPOSIT' ? 'Подтвердить пополнение' : 'Подтвердить снятие';
  message('payment-message', '');
  confirmation.showModal();
  el('cancel-confirmation').focus();
});

el('confirm-payment').addEventListener('click', () => {
  if (!reviewed || !queue || sending || queue.current) return;
  try { queue.begin(reviewed.type, reviewed.amountMinor, reviewed.recipientId); }
  catch { confirmation.close(); message('payment-message', 'Не удалось безопасно сохранить номер операции. Разрешите хранилище вкладки в браузере и попробуйте снова. Запрос не отправлен.', 'error'); return; }
  reviewed = null;
  confirmation.close();
  void sendPending();
});
for (const id of ['cancel-confirmation', 'close-confirmation']) el(id).addEventListener('click', () => { confirmation.close(); reviewed = null; });
confirmation.addEventListener('cancel', () => { reviewed = null; });
paymentForm.querySelectorAll<HTMLInputElement>('input[name="operation-type"]').forEach(input => input.addEventListener('change', () => changeOperation(operationType())));
document.querySelectorAll<HTMLButtonElement>('[data-quick]').forEach(button => button.addEventListener('click', () => { changeOperation(button.dataset.quick as OperationType); el('payments').scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'center' }); (button.dataset.quick === 'TRANSFER' ? el('recipient-search') : amountInput).focus({ preventScroll: true }); }));
el('retry-payment').addEventListener('click', () => { void sendPending(); });
el('recipient-search-button').addEventListener('click', () => { void loadRecipients(); });
el('recipient-search').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); void loadRecipients(); } });
el('recipient-more').addEventListener('click', () => { void loadRecipients(false); });
el('history-more').addEventListener('click', () => { void loadHistory(false); });
el('history-retry').addEventListener('click', () => { void loadHistory(historyItems.length === 0); });
el('notifications-refresh').addEventListener('click', () => { void loadNotifications(); });
el('notifications-more').addEventListener('click', () => { void loadNotifications(false); });
el('notifications-retry').addEventListener('click', () => { void loadNotifications(true, true); });
el('refresh-data').addEventListener('click', () => { void refreshData(); });
el('analytics-retry').addEventListener('click', () => { void loadAnalytics(); });
el('analytics-month').addEventListener('change', () => { void loadAnalytics(); });
el('analytics-type').addEventListener('change', () => { const filtered = personalAnalyticsData(); if (filtered) renderAnalytics(filtered); });

function recordActivity(event: Event, type: 'SCREEN_OPENED' | 'FORM_OPENED', section: 'OVERVIEW' | 'HISTORY' | 'NOTIFICATIONS' | 'ANALYTICS' | 'PROFILE' | 'PAYMENTS'): void {
  if (!event.isTrusted || !session?.analyticsEnabled || !account) return;
  activity.record(type, section);
  void activity.flush({ [session.csrfHeader]: session.csrfToken });
}

for (const link of document.querySelectorAll<HTMLAnchorElement>('.glass-nav a')) link.addEventListener('click', event => {
  const section = link.hash.slice(1).toUpperCase() as 'OVERVIEW' | 'HISTORY' | 'NOTIFICATIONS' | 'ANALYTICS' | 'PROFILE';
  if (event.isTrusted && section !== activeScreen) { activeScreen = section; recordActivity(event, 'SCREEN_OPENED', section); }
});
for (const [id, section] of [['payment-form', 'PAYMENTS'], ['profile-form', 'PROFILE']] as const) {
  for (const name of ['pointerdown', 'keydown']) el(id).addEventListener(name, event => {
    if (!event.isTrusted || openedForms.has(id) || !account || !session?.analyticsEnabled) return;
    openedForms.add(id);
    recordActivity(event, 'FORM_OPENED', section);
  });
}

el('business-retry').addEventListener('click', () => { void loadAnalytics(true); });

el<HTMLFormElement>('profile-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (!session || profileVersion === null) return;
  const fullName = el<HTMLInputElement>('full-name').value.trim();
  const birthDate = el<HTMLInputElement>('birth-date').value;
  if (!fullName || !birthDate || birthDate > el<HTMLInputElement>('birth-date').max) { message('profile-message', 'Укажите имя и корректную дату рождения.', 'error'); return; }
  const fields = el<HTMLFieldSetElement>('profile-fields');
  fields.disabled = true;
  message('profile-message', 'Сохраняем изменения…');
  try {
    const result = await request('/ui/account', { method: 'PUT', headers: { 'Content-Type': 'application/json', [session.csrfHeader]: session.csrfToken }, body: JSON.stringify({ fullName, birthDate, version: profileVersion }) });
    ++accountRequest;
    if (result === null) await loadAccount(true); else showAccount(parseAccount(result), true);
    message('profile-message', 'Изменения сохранены.', 'success');
    el('profile-reload').hidden = true;
    if (!queue?.current) message('payment-message', '');
    void loadNotifications();
  } catch (error) {
    message('profile-message', errorMessage(error), 'error');
    if (error instanceof ApiError && error.status === 409) el('profile-reload').hidden = false;
  } finally { fields.disabled = false; }
});

el('profile-reload').addEventListener('click', async () => {
  try { await loadAccount(true); message('profile-message', 'Загружены актуальные данные. Проверьте их перед сохранением.'); el('profile-reload').hidden = true; }
  catch (error) { message('profile-message', errorMessage(error), 'error'); }
});

el<HTMLFormElement>('logout-form').addEventListener('submit', event => {
  if (!session) event.preventDefault();
});

const navLinks = [...document.querySelectorAll<HTMLAnchorElement>('.glass-nav a')];
if ('IntersectionObserver' in window) {
  const observer = new IntersectionObserver(entries => {
    for (const entry of entries) if (entry.isIntersecting) for (const link of navLinks) { if (link.hash === `#${entry.target.id}`) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current'); }
  }, { rootMargin: '-15% 0px -65% 0px', threshold: 0 });
  for (const id of ['overview', 'history', 'notifications', 'analytics', 'profile']) observer.observe(el(id));
}

let chartWidth = 0;
if ('ResizeObserver' in window) new ResizeObserver(entries => {
  const width = Math.round(entries[0]!.contentRect.width);
  if (width === chartWidth) return;
  chartWidth = width;
  if (historyItems.length) renderBalanceChart(el('balance-chart'), historyItems);
  const personal = personalAnalyticsData();
  if (personal && !el('analytics-content').hidden) renderAnalyticsChart(el('analytics-chart'), personal);
  if (businessData && !el('business-content').hidden) renderAnalyticsChart(el('business-chart'), businessData);
}).observe(el('main'));

async function initialize(): Promise<void> {
  if (sending) return;
  el('session-notice').hidden = false;
  el('session-error').hidden = true;
  el<HTMLButtonElement>('session-retry').disabled = true;
  try {
    const nextSession = parseSession(await request('/ui/session'));
    if (session && session.login !== nextSession.login) {
      document.body.hidden = true;
      window.location.replace('/cabinet');
      throw new Error('Session identity changed');
    }
    session = nextSession;
    const csrf = el<HTMLInputElement>('logout-csrf'); csrf.name = session.csrfParameter; csrf.value = session.csrfToken;
    el<HTMLFormElement>('logout-form').querySelector<HTMLButtonElement>('button')!.disabled = false;
    await loadAccount(profileVersion === null);
    if (!account) throw new Error('Account unavailable');
    try { queue = new OperationQueue(sessionStorage, (account as Account).accountId); }
    catch { queue = null; message('payment-message', 'Не удалось прочитать сохранённую операцию. Проверьте историю перед новой отправкой. Операции временно заблокированы в этой вкладке.', 'error'); }
    updatePaymentFields();
    el<HTMLButtonElement>('refresh-data').disabled = false;
    el('business').hidden = !session.analyst;
    await Promise.allSettled([loadRecipients(), loadHistory(), loadNotifications(), loadAnalytics(), ...(session.analyst ? [loadAnalytics(true)] : [])]);
  } catch (error) {
    message('session-error-text', errorMessage(error));
    el('session-error').hidden = false;
  } finally { el('session-notice').hidden = true; el<HTMLButtonElement>('session-retry').disabled = false; }
}
const monthLabels = new Intl.DateTimeFormat('ru-RU', { month: 'long', year: 'numeric', timeZone: 'UTC' });
const monthNow = new Date();
for (let offset = 0; offset < 4; offset++) {
  const month = new Date(Date.UTC(monthNow.getUTCFullYear(), monthNow.getUTCMonth() - offset, 1));
  el<HTMLSelectElement>('analytics-month').add(new Option(monthLabels.format(month), month.toISOString().slice(0, 7)));
}
const today = new Date();
el<HTMLInputElement>('birth-date').max = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
el('session-retry').addEventListener('click', () => { void initialize(); });
void initialize();
