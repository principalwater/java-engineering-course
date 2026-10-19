import { ApiError, request } from './api.ts';

type ActivityType = 'SCREEN_OPENED' | 'FORM_OPENED';
type ActivitySection = 'OVERVIEW' | 'HISTORY' | 'NOTIFICATIONS' | 'ANALYTICS' | 'PROFILE' | 'PAYMENTS';
type Activity = Readonly<{ eventId: string; type: ActivityType; section: ActivitySection }>;

export class UiActivityBuffer {
  // Телеметрия не блокирует банк и не накапливается бесконечно при outage; очередь живёт только в этой вкладке.
  private static readonly MAX_PENDING = 16;
  private pending: Activity[] = [];
  private sending = false;

  record(type: ActivityType, section: ActivitySection): void {
    if (this.pending.length >= UiActivityBuffer.MAX_PENDING) { console.info('UI activity buffer full'); return; }
    this.pending.push(Object.freeze({ eventId: crypto.randomUUID(), type, section }));
  }

  async flush(headers: Record<string, string>): Promise<void> {
    if (this.sending) return;
    this.sending = true;
    try {
      // Повтор возможен только при следующем настоящем жесте; прежние UUID/body сохраняются до 204.
      for (let count = 0; count < UiActivityBuffer.MAX_PENDING && this.pending.length; count++) {
        await request('/ui/analytics/activities', { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(this.pending[0]) });
        this.pending.shift();
      }
    } catch (error) {
      // Сервер не принял окончательно отклонённое действие; сетевой сбой оставляет прежний UUID.
      if (error instanceof ApiError && [400, 403, 409, 429].includes(error.status)) this.pending.shift();
      console.info('UI activity unavailable');
    }
    finally { this.sending = false; }
  }
}
