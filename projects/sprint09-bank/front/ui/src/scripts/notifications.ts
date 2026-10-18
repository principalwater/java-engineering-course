import { parseNotificationPage, request } from './api.ts';
import type { NotificationItem } from './api.ts';

export class NotificationFeed {
  items: NotificationItem[] = [];
  nextCursor: string | null = null;
  loading = false;
  loaded = false;
  error: unknown = null;
  private ownerId: string | null = null;
  private requestId = 0;
  private retryReset = true;

  async load(ownerId: string, reset = true): Promise<void> {
    if (this.ownerId !== ownerId) {
      this.ownerId = ownerId;
      this.items = [];
      this.nextCursor = null;
      this.loaded = false;
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
      this.items = reset ? page.items : [...this.items, ...page.items];
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
}
