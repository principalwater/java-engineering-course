import type { Analytics } from './api.ts';

export function filterAnalytics(data: Analytics, type: string): Analytics {
  if (type === 'ALL') return data;
  if (!['DEPOSIT', 'WITHDRAW', 'TRANSFER_IN', 'TRANSFER_OUT'].includes(type)) throw new Error('Invalid analytics filter');
  const rows = data.breakdown.filter(row => row.type === type);
  return { ...data, days: rows.map(row => ({ date: row.date, incomingMinor: row.incomingMinor, outgoingMinor: row.outgoingMinor })), operationsCount: rows.reduce((sum, row) => sum + row.operationsCount, 0) };
}
