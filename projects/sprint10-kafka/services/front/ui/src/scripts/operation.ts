import { MAX_AMOUNT_MINOR } from './money.ts';

export type OperationType = 'TRANSFER' | 'DEPOSIT' | 'WITHDRAW';
export type Command = Readonly<{ operationId: string; amountMinor: number; type?: 'DEPOSIT' | 'WITHDRAW'; recipientId?: string }>;
export type PendingOperation = Readonly<{ path: '/ui/cash' | '/ui/transfers'; command: Command }>;
export const isUuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);

function validatePending(value: unknown): PendingOperation {
  if (!value || typeof value !== 'object') throw new Error('Invalid pending operation');
  const pending = value as Record<string, unknown>;
  if (!pending.command || typeof pending.command !== 'object') throw new Error('Invalid pending command');
  const command = pending.command as Record<string, unknown>;
  if (!isUuid(command.operationId) || !Number.isSafeInteger(command.amountMinor) ||
      Number(command.amountMinor) <= 0 || Number(command.amountMinor) > MAX_AMOUNT_MINOR) throw new Error('Invalid pending amount');
  if (pending.path === '/ui/transfers' && isUuid(command.recipientId) && command.type === undefined) {
    return Object.freeze({ path: pending.path, command: Object.freeze({ operationId: command.operationId, amountMinor: Number(command.amountMinor), recipientId: command.recipientId }) });
  }
  if (pending.path === '/ui/cash' && (command.type === 'DEPOSIT' || command.type === 'WITHDRAW') && command.recipientId === undefined) {
    return Object.freeze({ path: pending.path, command: Object.freeze({ operationId: command.operationId, amountMinor: Number(command.amountMinor), type: command.type }) });
  }
  throw new Error('Invalid pending operation type');
}

export class OperationQueue {
  readonly storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;
  readonly key: string;
  current: PendingOperation | null;

  constructor(storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>, accountId: string) {
    this.storage = storage;
    this.key = `bereg.operation.${accountId}`;
    const saved = storage.getItem(this.key);
    this.current = saved === null ? null : validatePending(JSON.parse(saved));
  }

  begin(type: OperationType, amountMinor: number, recipientId?: string): PendingOperation {
    if (this.current) throw new Error('An operation is already pending');
    const command: Command = type === 'TRANSFER'
      ? { operationId: crypto.randomUUID(), amountMinor, recipientId }
      : { operationId: crypto.randomUUID(), amountMinor, type };
    const pending = validatePending({ path: type === 'TRANSFER' ? '/ui/transfers' : '/ui/cash', command });
    // Сохраняем до отправки: потеря ответа и перезагрузка не создают новый денежный эффект.
    this.storage.setItem(this.key, JSON.stringify(pending));
    this.current = pending;
    return pending;
  }

  settle(operationId: string, status: 'PENDING' | 'SUCCEEDED' | 'REJECTED'): void {
    if (!this.current || this.current.command.operationId !== operationId) throw new Error('Operation result mismatch');
    if (status === 'PENDING') return;
    this.storage.removeItem(this.key);
    this.current = null;
  }
}
