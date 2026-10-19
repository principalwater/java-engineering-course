import { parseNotificationPage, parseNotificationRead, request } from './api.ts';
import type { NotificationItem, NotificationSource } from './api.ts';

export class NotificationFeed {
  items: NotificationItem[] = [];
  nextCursor: string | null = null;
  loading = false;
  loaded = false;
  error: unknown = null;
  private ownerId: string | null = null;
  private requestId = 0;
  private retryReset = true;
  private confirmedReads = new Map<string, NotificationItem>();

  async load(ownerId: string, reset = true): Promise<void> {
    if (this.ownerId !== ownerId) {
      this.ownerId = ownerId;
      this.items = [];
      this.nextCursor = null;
      this.loaded = false;
      this.confirmedReads.clear();
      reset = true;
    }
    const cursor = reset ? null : this.nextCursor;
    if (!reset && cursor === null) return;
    const identity = ++this.requestId;
    this.loading = true;
    this.error = null;
    this.retryReset = reset;
    try {
      const page = parseNotificationPage(await request(`/ui/notifications?limit=20${cursor ? `&before=${encodeURIComponent(cursor)}` : ''}`), ownerId);
      if (identity !== this.requestId || ownerId !== this.ownerId) return;
      const items = page.items.map(item => this.confirmedReads.get(`${item.source}:${item.eventId}`) ?? item);
      this.items = reset ? items : [...this.items, ...items];
      this.nextCursor = page.nextCursor;
      this.loaded = true;
    } catch (error) {
      if (identity === this.requestId && ownerId === this.ownerId) this.error = error;
    } finally {
      if (identity === this.requestId && ownerId === this.ownerId) this.loading = false;
    }
  }

  retry(ownerId: string): Promise<void> {
    // Повторяем именно неудачную страницу; ошибка обновления не должна незаметно превратиться в «ещё».
    return this.load(ownerId, this.retryReset);
  }

  async markRead(ownerId: string, source: NotificationSource, eventId: string, headers: Record<string, string>): Promise<void> {
    const item = parseNotificationRead(await request('/ui/notifications/read', { method: 'POST',
      headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify({ source, eventId }) }), ownerId);
    if (item.source !== source || item.eventId !== eventId || item.readAt === null) throw new Error('Invalid notification read acknowledgement');
    if (ownerId !== this.ownerId) return;
    this.confirmedReads.set(`${source}:${eventId}`, item);
    // Позднее чтение страницы может предшествовать POST: подтверждённый сервером readAt не откатываем в null.
    this.items = this.items.map(previous => previous.source === source && previous.eventId === eventId ? item : previous);
  }
}
