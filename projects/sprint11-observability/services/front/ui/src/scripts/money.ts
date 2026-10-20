export const MAX_AMOUNT_MINOR = 1_000_000_000_000;
const integerFormatter = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 });

export function parseAmount(value: string): number {
  const normalized = value.trim();
  if (!/^(?:\d+|\d{1,3}(?:[ \u00a0\u202f]\d{3})+)(?:[.,]\d{1,2})?$/.test(normalized)) {
    throw new Error('Invalid decimal amount');
  }
  const [whole, fraction = ''] = normalized.replace(/[ \u00a0\u202f]/g, '').split(/[.,]/);
  const minor = BigInt(whole!) * 100n + BigInt(fraction.padEnd(2, '0'));
  if (minor <= 0n || minor > BigInt(MAX_AMOUNT_MINOR)) throw new Error('Amount out of range');
  return Number(minor);
}

export function formatMoney(minor: number | bigint, signed = false): string {
  if (typeof minor === 'number' && !Number.isSafeInteger(minor)) throw new Error('Unsafe minor amount');
  const value = BigInt(minor);
  const absolute = value < 0n ? -value : value;
  const fraction = (absolute % 100n).toString().padStart(2, '0');
  const sign = value < 0n ? '−' : signed && value > 0n ? '+' : '';
  return `${sign}${integerFormatter.format(absolute / 100n)},${fraction}\u00a0₽`;
}
