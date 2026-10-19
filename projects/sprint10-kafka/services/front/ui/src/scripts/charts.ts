import { axisBottom, axisLeft, extent, line, scaleBand, scaleLinear, scalePoint, select } from 'd3';
import { formatMoney } from './money.ts';
import type { Analytics, HistoryItem } from './api.ts';

const shortDate = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short' });
const fullDate = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' });
const compactMoney = new Intl.NumberFormat('ru-RU', { notation: 'compact', maximumFractionDigits: 1 });

function chart(container: HTMLElement, label: string, height: number) {
  container.replaceChildren();
  const width = Math.max(250, container.clientWidth);
  const margin = { left: 49, right: 12, top: 14, bottom: 30 };
  const svg = select(container).append('svg').attr('viewBox', `0 0 ${width} ${height}`).attr('role', 'img').attr('aria-label', label);
  svg.append('title').text(label);
  const tooltip = document.createElement('p');
  tooltip.className = 'chart-tooltip';
  tooltip.setAttribute('aria-live', 'polite');
  tooltip.textContent = 'Наведите на точку или выберите её клавишей Tab';
  container.append(tooltip);
  return { svg, width, height, margin, tooltip };
}

export function renderBalanceChart(container: HTMLElement, entries: HistoryItem[]): void {
  const points = entries.slice(0, 20).toReversed();
  if (!points.length) {
    container.replaceChildren();
    const message = document.createElement('p');
    message.className = 'chart-state';
    message.textContent = 'Здесь будет ваш финансовый маршрут. Начните с первой операции.';
    container.append(message);
    return;
  }
  const { svg, width, height, margin, tooltip } = chart(container, `Остаток после последних ${points.length} выполненных операций. Точные суммы доступны в истории.`, 205);
  const [minimum, maximum] = extent(points, entry => entry.balanceMinor) as [number, number];
  const padding = Math.max((maximum - minimum) * .15, 100);
  const y = scaleLinear().domain([Math.max(0, minimum - padding), maximum + padding]).nice(3).range([height - margin.bottom, margin.top]);
  const x = scalePoint<number>().domain(points.map((_, index) => index)).range([margin.left, width - margin.right]).padding(points.length === 1 ? .5 : .05);
  svg.append('g').attr('class', 'chart-grid').attr('transform', `translate(${margin.left},0)`).call(axisLeft(y).ticks(3).tickSize(-(width - margin.left - margin.right)).tickFormat(value => compactMoney.format(Number(value) / 100)));
  const tickIndexes = [...new Set([0, Math.floor((points.length - 1) / 2), points.length - 1])];
  svg.append('g').attr('class', 'chart-axis').attr('transform', `translate(0,${height - margin.bottom + 5})`).call(axisBottom(x).tickValues(tickIndexes).tickFormat(index => shortDate.format(new Date(points[index]!.occurredAt))));
  if (points.length > 1) svg.append('path').datum(points).attr('class', 'chart-line').attr('d', line<HistoryItem>().x((_, index) => x(index)!).y(entry => y(entry.balanceMinor)));
  const detail = (entry: HistoryItem) => `${fullDate.format(new Date(entry.occurredAt))} · остаток ${formatMoney(entry.balanceMinor)}`;
  svg.selectAll('circle').data(points).join('circle').attr('class', 'chart-point').attr('cx', (_, index) => x(index)!).attr('cy', entry => y(entry.balanceMinor)).attr('r', points.length === 1 ? 5 : 3.5).attr('tabindex', 0).attr('role', 'img').attr('aria-label', detail).on('pointerenter focus', (_, entry) => { tooltip.textContent = detail(entry); }).append('title').text(detail);
}

export function renderAnalyticsChart(container: HTMLElement, data: Analytics): void {
  if (!data.days.length) {
    container.replaceChildren();
    const empty = document.createElement('p');
    empty.className = 'chart-state';
    empty.textContent = 'За этот период пока нет данных об операциях.';
    container.append(empty);
    return;
  }
  const { svg, width, height, margin, tooltip } = chart(container, 'Поступления и списания по дням. Точные суммы доступны в таблице под графиком.', 215);
  tooltip.textContent = 'Наведите на столбец или выберите его клавишей Tab';
  const x = scaleBand().domain(data.days.map(day => day.date)).range([margin.left, width - margin.right]).padding(.25);
  const maximum = Math.max(100, ...data.days.flatMap(day => [day.incomingMinor, day.outgoingMinor]));
  const y = scaleLinear().domain([0, maximum]).nice(3).range([height - margin.bottom, margin.top]);
  svg.append('g').attr('class', 'chart-grid').attr('transform', `translate(${margin.left},0)`).call(axisLeft(y).ticks(3).tickSize(-(width - margin.left - margin.right)).tickFormat(value => compactMoney.format(Number(value) / 100)));
  const tickDays = data.days.filter((_, index) => index % Math.max(1, Math.ceil(data.days.length / 5)) === 0).map(day => day.date);
  svg.append('g').attr('class', 'chart-axis').attr('transform', `translate(0,${height - margin.bottom + 5})`).call(axisBottom(x).tickValues(tickDays).tickFormat(date => shortDate.format(new Date(`${date}T12:00:00`))));
  for (const [index, field, label, color] of [[0, 'incomingMinor', 'Поступления', '#4d7757'], [1, 'outgoingMinor', 'Списания', '#7a946e']] as const) {
    const detail = (day: Analytics['days'][number]) => `${shortDate.format(new Date(`${day.date}T12:00:00`))} · ${label.toLowerCase()} ${formatMoney(day[field])}`;
    svg.append('g').selectAll('rect').data(data.days).join('rect').attr('class', 'chart-bar').attr('x', day => x(day.date)! + index * x.bandwidth() / 2).attr('y', day => y(day[field])).attr('height', day => y(0) - y(day[field])).attr('width', Math.max(1, x.bandwidth() / 2 - 1)).attr('fill', color).attr('rx', 2).attr('tabindex', 0).attr('role', 'img').attr('aria-label', detail).on('pointerenter focus', (_, day) => { tooltip.textContent = detail(day); }).append('title').text(detail);
  }
  const legend = document.createElement('div');
  legend.className = 'chart-legend';
  for (const label of ['Поступления', 'Списания']) { const item = document.createElement('span'); item.textContent = label; legend.append(item); }
  container.append(legend);
  const details = document.createElement('details');
  details.className = 'chart-data';
  const summary = document.createElement('summary');
  summary.textContent = 'Данные графика в таблице';
  const table = document.createElement('table');
  const caption = document.createElement('caption');
  caption.className = 'sr-only'; caption.textContent = 'Поступления и списания по дням, рубли'; table.append(caption);
  const header = table.createTHead().insertRow();
  for (const label of ['Дата', 'Поступления', 'Списания']) { const cell = document.createElement('th'); cell.scope = 'col'; cell.textContent = label; header.append(cell); }
  const body = table.createTBody();
  for (const day of data.days) { const row = body.insertRow(); for (const value of [day.date.split('-').toReversed().join('.'), formatMoney(day.incomingMinor), formatMoney(day.outgoingMinor)]) { row.insertCell().textContent = value; } }
  details.append(summary, table); container.append(details);
}
