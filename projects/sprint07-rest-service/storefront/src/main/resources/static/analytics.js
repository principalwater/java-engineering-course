// Аналитика покупок: график и сравнение на D3 поверх полной серверной разметки.
// Без D3 или JavaScript страница остаётся целой: суммы, таблицы и очередь уже в HTML.
(() => {
  "use strict";

  const page = document.querySelector("[data-analytics]");
  if (!page || page.dataset.state !== "AVAILABLE") return;
  const d3 = window.d3;
  if (!d3) {
    console.warn("Analytics charts are disabled: d3 is not loaded");
    return;
  }

  const ZONE = document.querySelector('meta[name="display-zone"]').content;
  const valueLabel = page.dataset.valueLabel;
  const REQUEST_TIMEOUT_MS = 10000;
  const SERIES = {
    value: { caption: `${valueLabel} по дням, ₽` },
    orders: { caption: "Число заказов по дням" },
    units: { caption: "Число предметов по дням" },
  };
  const CARDS = [
    { key: "orders", format: value => fmtInt(value) },
    { key: "value", format: value => fmtMoney(value) },
    { key: "units", format: value => fmtInt(value) },
    { key: "average", format: value => fmtCents(value) },
  ];

  /* ---------- Числа и даты ---------- */

  // Суммы и счётчики приходят как long и могут быть строками: точные подписи считаются на BigInt,
  // а Number используется только для координат графика.
  const numbers = new Intl.NumberFormat("ru-RU");
  const dayFormat = new Intl.DateTimeFormat("ru-RU", { weekday: "short", day: "numeric", month: "long", timeZone: "UTC" });
  const monthFormat = new Intl.DateTimeFormat("ru-RU", { month: "short", timeZone: "UTC" });
  const stampFormat = new Intl.DateTimeFormat("ru-RU", {
    day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23", timeZone: ZONE,
  });

  const int = value => {
    const text = String(value).trim();
    if (!/^\d+$/.test(text)) throw new Error("Invalid analytics integer");
    return BigInt(text);
  };
  // «1250.5» или число → копейки; округление как у BigDecimal HALF_UP на втором знаке.
  const cents = value => {
    const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(String(value).trim());
    if (!match) throw new Error("Invalid analytics amount");
    const fraction = `${match[3] ?? ""}000`.slice(0, 3);
    let result = BigInt(match[2]) * 100n + BigInt(fraction.slice(0, 2));
    if (fraction[2] >= "5") result += 1n;
    return match[1] ? -result : result;
  };
  const fmtInt = value => numbers.format(value);
  const fmtMoney = value => `${numbers.format(value)} ₽`;
  const fmtCents = value => {
    const abs = value < 0n ? -value : value;
    return `${value < 0n ? "−" : ""}${numbers.format(abs / 100n)},${String(abs % 100n).padStart(2, "0")} ₽`;
  };
  const absolute = value => (value < 0n ? -value : value);
  const parseDay = text => {
    const [year, month, day] = String(text).slice(0, 10).split("-").map(Number);
    return new Date(Date.UTC(year, month - 1, day));
  };
  const instant = value => {
    if (value == null || value === "") return null;
    // Время из JSON может нести наносекунды; Date надёжно разбирает только миллисекунды.
    const date = typeof value === "number"
      ? new Date(value < 1e11 ? value * 1000 : value)
      : new Date(String(value).replace(/(\.\d{3})\d+/, "$1"));
    return Number.isNaN(date.getTime()) ? null : date;
  };

  // Узлы создаются через DOM API: пользовательский текст (названия товаров) не попадает в разметку как HTML.
  const h = (tag, props = {}, ...children) => {
    const node = document.createElement(tag);
    for (const [name, value] of Object.entries(props)) {
      if (value == null || value === false) continue;
      node.setAttribute(name, value === true ? "" : String(value));
    }
    node.append(...children.flat().filter(child => child != null));
    return node;
  };

  /* ---------- Модель: из разметки при загрузке, из JSON при обновлении ---------- */

  const summaryOf = source => {
    if (!source) return null;
    const orders = int(source.orders);
    const value = int(source.orderValue);
    return {
      orders,
      units: int(source.units),
      value,
      average: orders === 0n ? null : cents(source.averageOrder),
    };
  };

  const periodDays = (period, fallback) => {
    if (period && typeof period === "object" && "days" in period) return Number(period.days) || fallback;
    if (typeof period === "number") return period;
    const match = /(\d+)/.exec(String(period ?? ""));
    return match ? Number(match[1]) : fallback;
  };

  const fromDto = (dto, fallbackDays) => ({
    state: String(dto.state),
    days: periodDays(dto.period, fallbackDays),
    queue: {
      pending: int(dto.queue?.pendingOrders),
      last: instant(dto.queue?.lastDelivery),
      oldest: instant(dto.queue?.oldestPending),
    },
    summary: summaryOf(dto.summary),
    previous: summaryOf(dto.previous),
    daily: (dto.daily ?? []).map(day => ({
      date: String(day.date).slice(0, 10), orders: int(day.orders), units: int(day.units), value: int(day.orderValue),
    })),
    products: (dto.products ?? []).map(product => ({
      title: String(product.title), sku: String(product.sku), orders: int(product.orders),
      units: int(product.units), value: int(product.order_value ?? product.orderValue),
    })),
  });

  const readPage = () => {
    const card = key => page.querySelector(`[data-key="${key}"]`);
    const stamp = name => instant(page.querySelector(`[data-queue="${name}"] time`)?.getAttribute("datetime"));
    const side = attribute => {
      if (card("orders").dataset[attribute] === undefined) return null;
      const orders = int(card("orders").dataset[attribute]);
      return {
        orders, value: int(card("value").dataset[attribute]),
        units: int(card("units").dataset[attribute]),
        average: orders === 0n ? null : cents(card("average").dataset[attribute]),
      };
    };
    return {
      state: "AVAILABLE",
      days: Number(page.dataset.days),
      queue: { pending: int(page.querySelector('[data-queue="pending"]')?.textContent ?? "0"), last: stamp("last"), oldest: stamp("oldest") },
      summary: side("current"),
      previous: side("previous"),
      daily: [...page.querySelectorAll("[data-daily-table] tbody tr")].map(row => ({
        date: row.dataset.date, orders: int(row.dataset.orders), units: int(row.dataset.units), value: int(row.dataset.value),
      })),
      products: [...page.querySelectorAll("[data-ranking] > li")].map(item => ({
        title: item.querySelector(".ranking-title")?.textContent ?? "",
        sku: item.querySelector(".ranking-sku")?.textContent ?? "",
        orders: int(item.dataset.orders), units: int(item.dataset.units), value: int(item.dataset.value),
      })),
    };
  };

  /* ---------- Узлы страницы ---------- */

  const periodForm = page.querySelector("[data-period-form]");
  const refreshButton = page.querySelector("[data-refresh]");
  const syncDays = page.querySelector("[data-sync-days]");
  const statusLine = page.querySelector("[data-status]");
  const tabs = page.querySelector("[data-tabs]");
  const tabButtons = tabs ? [...tabs.querySelectorAll('[role="tab"]')] : [];
  const panel = page.querySelector("[data-chart-panel]");
  const canvas = page.querySelector("[data-chart-canvas]");
  const caption = page.querySelector("[data-chart-caption]");
  const live = page.querySelector("[data-chart-live]");
  const exact = page.querySelector("[data-exact]");
  const tableBody = page.querySelector("[data-daily-table] tbody");
  const ranking = page.querySelector("[data-ranking]");
  const rankingEmpty = page.querySelector("[data-ranking-empty]");
  if (!tabs || !panel || !canvas || !caption || !tableBody || !ranking || !page.querySelector('[data-key="orders"]')) {
    console.warn("Analytics charts are disabled: expected markup is missing");
    return;
  }

  let model = readPage();
  if (model.daily.length === 0) return;
  const defaultDays = new URLSearchParams(location.search).has("days") ? 7 : model.days;

  const say = text => {
    if (!statusLine) return;
    statusLine.textContent = "";
    setTimeout(() => { statusLine.textContent = text; }, 60);
  };

  /* ---------- Таблицы, карточки и очередь ---------- */

  const setStamp = (name, date, empty) => {
    const target = page.querySelector(`[data-queue="${name}"]`);
    target?.replaceChildren(date ? h("time", { datetime: date.toISOString() }, stampFormat.format(date)) : empty);
  };

  const renderQueue = () => {
    page.querySelector('[data-queue="pending"]')?.replaceChildren(fmtInt(model.queue.pending));
    setStamp("last", model.queue.last, "Пока не было");
    setStamp("oldest", model.queue.oldest, "Очередь пуста");
  };

  const renderPeriod = () => {
    periodForm?.querySelectorAll('button[name="days"]').forEach(button => {
      const selected = Number(button.value) === model.days;
      button.setAttribute("aria-pressed", String(selected));
      button.classList.toggle("is-selected", selected);
    });
    if (syncDays) syncDays.value = String(model.days);
  };

  // При запуске тексты уже выставлены сервером; перерисовываются только полосы сравнения.
  const renderMetrics = ({ texts = true } = {}) => {
    for (const { key, format } of CARDS) {
      const card = page.querySelector(`[data-key="${key}"]`);
      if (!card) continue;
      const current = model.summary[key];
      const previous = model.previous?.[key];
      const comparable = current != null && previous != null;
      const change = card.querySelector(".metric-change");
      if (change) change.hidden = !comparable;
      if (texts) {
        card.querySelector("[data-metric]").textContent = current == null ? "—" : format(current);
        if (change) {
          if (comparable) {
            const delta = current - previous;
            change.querySelector(".metric-delta").textContent = delta === 0n
              ? "Без изменений" : `${delta > 0n ? "+" : "−"}${format(absolute(delta))}`;
            change.querySelector(".metric-was").textContent = `Было ${format(previous)} за предыдущие ${model.days} дней`;
          }
        }
      }
      // Две полосы «было / стало» на общей шкале; сами числа уже есть в тексте, поэтому полосы декоративны.
      const bars = card.querySelector(".compare-bars");
      if (bars) {
        const peak = comparable ? Math.max(Number(current), Number(previous)) : 0;
        bars.hidden = !comparable || peak === 0;
        if (!bars.hidden) {
          const scale = d3.scaleLinear().domain([0, peak]).range([0, 100]);
          bars.querySelector(".bar-previous i").style.width = `${scale(Number(previous)).toFixed(1)}%`;
          bars.querySelector(".bar-current i").style.width = `${scale(Number(current)).toFixed(1)}%`;
        }
      }
    }
  };

  const renderTable = () => {
    const peak = model.daily.reduce((max, day) => (day.value > max ? day.value : max), 0n);
    tableBody.replaceChildren(...model.daily.map(day => {
      const [year, month, date] = day.date.split("-");
      const percent = peak === 0n ? 0 : Math.round(Number(day.value) * 100 / Number(peak));
      return h("tr", { "data-date": day.date, "data-orders": day.orders, "data-units": day.units, "data-value": day.value },
        h("th", { scope: "row" }, h("time", { datetime: day.date }, `${date}.${month}.${year}`)),
        h("td", {}, fmtInt(day.orders)),
        h("td", {}, fmtInt(day.units)),
        h("td", { class: "cell-with-bar" }, h("span", {}, fmtMoney(day.value)),
          h("span", { class: "cell-bar", "aria-hidden": "true" }, h("i", { style: `width:${percent}%` }))));
    }));
  };

  const renderRanking = () => {
    const top = model.products;
    ranking.hidden = top.length === 0;
    if (rankingEmpty) rankingEmpty.hidden = top.length > 0;
    const leader = top.reduce((max, product) => (product.units > max ? product.units : max), 0n);
    const width = d3.scaleLinear().domain([0, Number(leader) || 1]).range([0, 100]);
    d3.select(ranking).selectAll("li").data(top).join("li").each(function (product) {
      this.dataset.orders = String(product.orders);
      this.dataset.units = String(product.units);
      this.dataset.value = String(product.value);
      this.replaceChildren(
        h("div", { class: "ranking-head" }, h("span", { class: "ranking-title" }, product.title),
          h("span", { class: "ranking-sku muted" }, product.sku)),
        h("div", { class: "ranking-bar", "aria-hidden": "true" },
          h("i", { style: `width:${width(Number(product.units)).toFixed(1)}%` })),
        h("p", { class: "ranking-values" }, h("strong", {}, `${fmtInt(product.units)} шт.`), " ",
          h("span", { class: "muted" }, fmtMoney(product.value))),
      );
    });
  };

  /* ---------- График по дням ---------- */

  const svg = d3.select(canvas).append("svg").attr("aria-hidden", "true").attr("focusable", "false");
  const tooltip = h("div", { class: "chart-tooltip", "aria-hidden": "true", hidden: true });
  canvas.append(tooltip);
  const chart = { series: "value", active: null, x: null, width: 0, bars: [] };

  const tipRow = (label, value) => h("span", { class: "tip-row" }, h("span", {}, label), h("b", {}, value));

  const hideTip = () => {
    chart.active = null;
    tooltip.hidden = true;
    chart.bars.forEach(bar => bar.classList.remove("is-active"));
  };

  const selectDay = (index, { speak = false } = {}) => {
    const day = model.daily[index];
    if (!day || !chart.x) {
      hideTip();
      return;
    }
    chart.active = index;
    chart.bars.forEach((bar, position) => bar.classList.toggle("is-active", position === index));
    const title = dayFormat.format(parseDay(day.date));
    tooltip.replaceChildren(
      h("strong", {}, title),
      tipRow(valueLabel, fmtMoney(day.value)),
      tipRow("Заказов", fmtInt(day.orders)),
      tipRow("Предметов", fmtInt(day.units)),
    );
    tooltip.hidden = false;
    const center = chart.x(index) + chart.x.bandwidth() / 2;
    const left = Math.min(Math.max(center - tooltip.offsetWidth / 2, 4), chart.width - tooltip.offsetWidth - 4);
    tooltip.style.left = `${Math.round(left)}px`;
    if (speak && live) {
      live.textContent = `${title}: ${valueLabel.toLocaleLowerCase("ru-RU")} ${fmtMoney(day.value)}, заказов ${fmtInt(day.orders)}, предметов ${fmtInt(day.units)}`;
    }
  };

  const drawChart = () => {
    const data = model.daily;
    const width = Math.floor(canvas.clientWidth);
    if (width < 120 || data.length === 0) return;
    const key = chart.series;
    const height = width < 520 ? 230 : 300;
    const peak = data.reduce((max, day) => Math.max(max, Number(day[key])), 0);
    const y = d3.scaleLinear().domain([0, peak || 1]).nice(4).range([height - 40, 12]);
    const ticks = y.ticks(4).filter(Number.isInteger);
    const left = 16 + 7.5 * Math.max(...ticks.map(tick => numbers.format(tick).length));
    const x = d3.scaleBand().domain(d3.range(data.length)).range([left, width - 8])
      .paddingInner(data.length > 30 ? 0.18 : 0.32).paddingOuter(0.1);
    const base = y(0);
    const isZero = day => day[key] === 0n;

    svg.attr("viewBox", `0 0 ${width} ${height}`);
    svg.selectAll("*").remove(); // перерисовка заменяет узлы, а не наращивает их

    svg.append("g").attr("class", "chart-grid").selectAll("line").data(ticks).join("line")
      .attr("x1", left).attr("x2", width - 8).attr("y1", tick => y(tick)).attr("y2", tick => y(tick));
    svg.append("g").attr("class", "chart-ticks").selectAll("text").data(ticks).join("text")
      .attr("x", left - 8).attr("y", tick => y(tick)).attr("dy", "0.32em").attr("text-anchor", "end")
      .text(tick => numbers.format(tick));

    chart.bars = svg.append("g").attr("class", "chart-bars").selectAll("rect").data(data).join("rect")
      .attr("class", day => (isZero(day) ? "bar is-zero" : "bar"))
      .attr("x", (_, index) => x(index))
      .attr("width", x.bandwidth())
      .attr("y", day => (isZero(day) ? base - 2 : Math.min(base - 3, y(Number(day[key])))))
      .attr("height", day => (isZero(day) ? 2 : Math.max(3, base - y(Number(day[key])))))
      .attr("rx", Math.min(6, x.bandwidth() / 2))
      .nodes();

    // Подписи считаются от последнего дня: свежая дата видна всегда, шаг растёт с числом дней.
    const slots = Math.max(2, Math.floor((width - left) / 40));
    const step = Math.ceil(data.length / slots);
    const labelled = d3.range(data.length).filter(index => (data.length - 1 - index) % step === 0);
    svg.append("g").attr("class", "chart-days").selectAll("text").data(labelled).join("text")
      .attr("x", index => x(index) + x.bandwidth() / 2).attr("y", height - 40 + 18).attr("text-anchor", "middle")
      .each(function (index) {
        const date = parseDay(data[index].date);
        const label = d3.select(this).text(date.getUTCDate());
        if (index === labelled[0] || date.getUTCDate() === 1) {
          label.append("tspan").attr("x", x(index) + x.bandwidth() / 2).attr("dy", "1.25em").text(monthFormat.format(date));
        }
      });

    chart.x = x;
    chart.width = width;
    canvas.dataset.width = String(width);
    if (chart.active !== null && data[chart.active]) selectDay(chart.active);
    else hideTip();
  };

  const selectSeries = (series, { focus = false } = {}) => {
    chart.series = series;
    tabButtons.forEach(tab => {
      const selected = tab.dataset.series === series;
      tab.setAttribute("aria-selected", String(selected));
      tab.tabIndex = selected ? 0 : -1;
      if (selected) {
        panel.setAttribute("aria-labelledby", tab.id);
        if (focus) tab.focus();
      }
    });
    caption.textContent = SERIES[series].caption;
    canvas.setAttribute("aria-label", SERIES[series].caption);
    drawChart();
  };

  tabs.addEventListener("click", event => {
    const tab = event.target.closest('[role="tab"]');
    if (tab) selectSeries(tab.dataset.series);
  });
  tabs.addEventListener("keydown", event => {
    const index = tabButtons.indexOf(document.activeElement);
    if (index < 0) return;
    const last = tabButtons.length - 1;
    const target = { ArrowRight: index === last ? 0 : index + 1, ArrowLeft: index === 0 ? last : index - 1, Home: 0, End: last }[event.key];
    if (target === undefined) return;
    event.preventDefault();
    selectSeries(tabButtons[target].dataset.series, { focus: true });
  });

  const indexAt = clientX => {
    if (!chart.x) return null;
    const gap = chart.x.step() - chart.x.bandwidth();
    const offset = clientX - canvas.getBoundingClientRect().left - chart.x(0) + gap / 2;
    const index = Math.floor(offset / chart.x.step());
    return index >= 0 && index < model.daily.length ? index : null;
  };
  const pointTo = event => {
    const index = indexAt(event.clientX);
    if (index === null) hideTip();
    else if (index !== chart.active) selectDay(index);
  };
  canvas.addEventListener("pointermove", pointTo);
  canvas.addEventListener("pointerdown", pointTo);
  canvas.addEventListener("pointerleave", () => { if (document.activeElement !== canvas) hideTip(); });
  canvas.addEventListener("focus", () => { if (chart.active === null) selectDay(model.daily.length - 1); });
  canvas.addEventListener("blur", hideTip);
  canvas.addEventListener("keydown", event => {
    const last = model.daily.length - 1;
    const current = chart.active ?? last;
    const next = {
      ArrowRight: chart.active === null ? last : Math.min(last, current + 1),
      ArrowLeft: chart.active === null ? last : Math.max(0, current - 1),
      Home: 0,
      End: last,
    }[event.key];
    if (event.key === "Escape") {
      // Подсказка закрывается, фокус остаётся на графике.
      hideTip();
      event.preventDefault();
    } else if (next !== undefined) {
      event.preventDefault();
      selectDay(next, { speak: true });
    }
  });

  let frame = 0;
  const redrawOnResize = () => {
    cancelAnimationFrame(frame);
    frame = requestAnimationFrame(() => {
      if (Math.floor(canvas.clientWidth) !== Number(canvas.dataset.width)) drawChart();
    });
  };
  if ("ResizeObserver" in window) new ResizeObserver(redrawOnResize).observe(canvas);
  else window.addEventListener("resize", redrawOnResize);

  /* ---------- Смена периода и обновление ---------- */

  const render = () => {
    page.dataset.days = String(model.days);
    renderPeriod();
    renderQueue();
    renderMetrics();
    renderTable();
    renderRanking();
    drawChart();
  };

  const setBusy = busy => {
    if (busy) page.setAttribute("aria-busy", "true");
    else page.removeAttribute("aria-busy");
    refreshButton?.setAttribute("aria-disabled", String(busy));
  };

  let sequence = 0;
  const load = async (days, { push = false } = {}) => {
    const ticket = ++sequence;
    setBusy(true);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      const response = await fetch(`${page.dataset.source}?days=${encodeURIComponent(days)}`, {
        headers: { Accept: "application/json" }, credentials: "same-origin", signal: controller.signal,
      });
      if (!response.ok) throw new Error(`Analytics request failed with HTTP ${response.status}`);
      const next = fromDto(await response.json(), days);
      if (ticket !== sequence) return; // пришёл ответ на уже устаревший запрос
      if (next.state !== "AVAILABLE" || !next.summary || next.daily.length === 0) throw new Error("Analytics are not available");
      model = next;
      render();
      if (push) history.pushState({ days: next.days }, "", `${page.dataset.page}?days=${next.days}`);
      say(`Показатели обновлены: период ${next.days} дней`);
    } catch (error) {
      if (ticket !== sequence) return;
      console.warn("Analytics refresh failed:", error);
      // Серверная страница сама покажет актуальное состояние, включая недоступную аналитику.
      location.replace(`${page.dataset.page}?days=${encodeURIComponent(days)}`);
    } finally {
      clearTimeout(timer);
      if (ticket === sequence) setBusy(false);
    }
  };

  periodForm?.addEventListener("submit", event => {
    const button = event.submitter;
    if (!button || !button.value) return; // без submitter форма уходит обычным GET
    event.preventDefault();
    const days = Number(button.value);
    load(days, { push: days !== model.days });
  });
  refreshButton?.addEventListener("click", () => {
    if (refreshButton.getAttribute("aria-disabled") !== "true") load(model.days);
  });
  window.addEventListener("popstate", () => {
    const days = Number(new URLSearchParams(location.search).get("days")) || defaultDays;
    if (days !== model.days) load(days);
  });

  /* ---------- Запуск ---------- */

  tabs.hidden = false;
  panel.hidden = false;
  if (refreshButton) refreshButton.hidden = false;
  if (exact) exact.open = false; // таблица остаётся под графиком, раскрывается по запросу
  renderMetrics({ texts: false });
  selectSeries("value");
})();
