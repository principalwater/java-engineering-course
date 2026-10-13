// Тема и корзина. Скрипт подключён в <head> без defer, поэтому тема применяется до первой отрисовки.
(() => {
  "use strict";

  const root = document.documentElement;

  /* ---------- Тема ---------- */

  const THEME_KEY = "store-theme";
  const systemDark = window.matchMedia("(prefers-color-scheme: dark)");
  let storedTheme = null;
  try { storedTheme = localStorage.getItem(THEME_KEY); } catch { /* хранилище закрыто: остаёмся на системной теме */ }
  if (storedTheme !== "dark" && storedTheme !== "light") storedTheme = null;
  const isDark = () => (storedTheme ? storedTheme === "dark" : systemDark.matches);
  const applyTheme = () => {
    root.dataset.theme = isDark() ? "dark" : "light";
    document.getElementById("theme-switch")?.setAttribute("aria-pressed", String(isDark()));
  };
  applyTheme();
  systemDark.addEventListener?.("change", () => { if (!storedTheme) applyTheme(); });
  // Класс нужен CSS с первого кадра: кнопка «Применить» для режима без скрипта не должна мигать.
  if ("fetch" in window && "AbortController" in window) root.classList.add("js");

  /* ---------- Корзина ---------- */

  // Изменения уходят на сервер по одному, в порядке нажатий: ответ несёт весь снимок корзины,
  // и обгоняющий ответ перетёр бы свежие значения. Сверх лимита новые нажатия отклоняются.
  const MAX_QUEUED = 12;
  const REQUEST_TIMEOUT_MS = 10000;
  const STATUS_VISIBLE_MS = 3500;
  const numbers = new Intl.NumberFormat("ru-RU");
  const money = value => `${numbers.format(value)} ₽`;
  const big = value => {
    if ((typeof value === "number" && !Number.isSafeInteger(value)) || !/^\d+$/.test(String(value))) {
      throw new Error("Invalid cart amount");
    }
    return BigInt(value);
  };

  const queue = [];
  const states = new WeakMap();
  let draining = false;
  let queueResult = Promise.resolve(true);
  let purchasing = false;

  class RequestError extends Error {
    constructor(status) {
      super(`Cart request failed with HTTP ${status}`);
      this.status = status;
    }
  }

  const stateOf = form => {
    let state = states.get(form);
    if (!state) {
        state = { pending: 0, intended: undefined, timer: 0, invalid: false };
      states.set(form, state);
    }
    return state;
  };

  const announce = text => {
    const region = document.querySelector("[data-live-region]");
    if (!region) return;
    region.textContent = "";
    setTimeout(() => { region.textContent = text; }, 60);
  };

  // Сообщение под полем количества — живая область самой формы.
  const say = (form, content, { error = false, sticky = false, reload = false } = {}) => {
    const status = form.querySelector(".quantity-status");
    if (!status) return;
    const state = stateOf(form);
    clearTimeout(state.timer);
    status.classList.toggle("is-error", error);
    status.setAttribute("aria-live", error ? "assertive" : "polite");
    const nodes = Array.isArray(content) ? [...content] : [content];
    if (reload) {
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = "Обновить страницу";
      button.addEventListener("click", () => location.reload());
      nodes.push(button);
    }
    status.replaceChildren(...nodes);
    if (!error && !sticky) state.timer = setTimeout(() => status.replaceChildren(), STATUS_VISIBLE_MS);
  };

  const setDisabled = (button, disabled) => {
    if (!button) return;
    if (disabled) button.setAttribute("aria-disabled", "true");
    else button.removeAttribute("aria-disabled");
  };

  const syncInput = form => {
    const input = form.querySelector(".quantity-input");
    if (!input) return;
    input.value = form.dataset.count;
    delete input.dataset.dirty;
  };

  // Показывает подтверждённое сервером количество; ничего не выдумывает заранее.
  const syncForm = (form, count) => {
    const input = form.querySelector(".quantity-input");
    form.dataset.count = String(count);
    if (input && !(document.activeElement === input && input.dataset.dirty)) syncInput(form);
    setDisabled(form.querySelector(".quantity-minus"), count === 0);
    const plus = form.querySelector(".quantity-plus");
    setDisabled(plus, count >= Number(form.dataset.max));
    plus?.setAttribute("aria-label", count === 0
      ? `Добавить «${form.dataset.title}» в корзину` : `Увеличить количество «${form.dataset.title}»`);
    // Фокус не должен пропасть вместе со скрытым элементом.
    const active = document.activeElement;
    if (form.contains(active) && active.offsetParent === null) form.querySelector(".quantity-plus")?.focus();
  };

  const setBadge = (badge, count) => {
    const changed = badge.textContent !== String(count);
    badge.textContent = String(count);
    badge.hidden = count === 0;
    if (changed && count > 0) {
      badge.classList.remove("bump");
      void badge.offsetWidth;
      badge.classList.add("bump");
    }
  };

  const syncCartPage = (lines, total, count) => {
    const layout = document.querySelector("[data-cart-layout]");
    if (!layout) return;
    let hadFocus = false;
    let neighbour = null;
    layout.querySelectorAll(".cart-line").forEach(row => {
      const item = lines.get(row.dataset.productId);
      if (item) {
        row.dataset.count = String(item.count);
        const lineTotal = row.querySelector("[data-line-total]");
        if (lineTotal) lineTotal.textContent = money(big(item.price) * big(item.count));
        return;
      }
      if (row.contains(document.activeElement)) {
        hadFocus = true;
        neighbour = row.nextElementSibling ?? row.previousElementSibling;
      }
      row.remove();
    });
    layout.querySelectorAll("[data-cart-item-count]").forEach(node => { node.textContent = String(count); });
    layout.querySelectorAll("[data-cart-total]").forEach(node => {
      node.dataset.cartTotal = String(total);
      node.textContent = money(total);
    });
    if (lines.size === 0) {
      layout.hidden = true;
      const empty = document.querySelector("[data-cart-empty]");
      if (empty) {
        empty.hidden = false;
        if (hadFocus) empty.querySelector("h2")?.focus();
      }
    } else if (hadFocus) {
      const next = neighbour?.isConnected ? neighbour : layout.querySelector(".cart-line");
      next?.querySelector(".item-title")?.focus();
    }
  };

  const applyCart = cart => {
    const lines = new Map(cart.items.map(item => [String(item.id), item]));
    let total = 0n;
    let count = 0;
    for (const item of lines.values()) {
      total += big(item.price) * big(item.count);
      count += Number(item.count);
    }
    // Итоги берутся у сервера; пересчёт по позициям нужен, только если поле не пришло.
    if (cart.total != null) total = big(cart.total);
    if (cart.itemCount != null) count = Number(cart.itemCount);
    document.querySelectorAll(".quantity-form").forEach(form => {
      syncForm(form, Number(lines.get(form.dataset.itemId)?.count ?? 0));
    });
    document.querySelectorAll("[data-cart-count]").forEach(badge => setBadge(badge, count));
    syncCartPage(lines, total, count);
  };

  const reportSaved = (form, before, after) => {
    if (!form.isConnected) {
      announce(`«${form.dataset.title}» удалено из корзины`);
      return;
    }
    if (after === 0) {
      say(form, "Удалено из корзины");
    } else if (before === 0 && !form.closest("[data-cart-layout]")) {
      const link = document.createElement("a");
      link.href = form.dataset.endpoint;
      link.textContent = "Открыть корзину";
      say(form, ["Добавлено в корзину. ", link], { sticky: true });
    } else {
      say(form, `В корзине: ${after}`);
    }
  };

  const failureMessage = (form, status) => {
    switch (status) {
      case 400: return { text: `Не удалось принять количество. Введите число от 0 до ${form.dataset.max}.` };
      case 403: return { text: "Страница устарела. Обновите её и повторите.", reload: true };
      case 404: return { text: "Эта вещь уже недоступна. Обновите страницу.", reload: true };
      case 409: return { text: "Корзина не приняла изменение: достигнут предел количества или числа позиций. Количество прежнее." };
      default: return { text: "Не удалось подтвердить изменение. Обновите страницу, чтобы увидеть актуальную корзину.", reload: true };
    }
  };

  const reportFailed = (form, error) => {
    console.warn("Cart update failed:", error);
    syncInput(form);
    const message = failureMessage(form, error instanceof RequestError ? error.status : 0);
    say(form, message.text, { error: true, reload: message.reload });
  };

  const perform = async ({ form, action, quantity }) => {
    const before = Number(form.dataset.count);
    const body = new URLSearchParams({ id: form.dataset.itemId, action });
    if (action === "SET") body.set("quantity", String(quantity));
    const token = form.querySelector('input[name="_csrf"]')?.value;
    if (token) body.set("_csrf", token);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      const response = await fetch(form.dataset.endpoint, {
        method: "POST",
        credentials: "same-origin",
        signal: controller.signal,
        headers: { Accept: "application/json", "Content-Type": "application/x-www-form-urlencoded" },
        body,
      });
      if (!response.ok) throw new RequestError(response.status);
      if (response.redirected || !(response.headers.get("content-type") ?? "").includes("json")) {
        // Сервер применил изменение, но ответил страницей: актуальное состояние покажет перезагрузка.
        location.reload();
        return false;
      }
      const result = await response.json();
      if (!result?.cart || !Array.isArray(result.cart.items)) throw new Error("Unexpected cart response shape");
      applyCart(result.cart);
      reportSaved(form, before, Number(result.count));
      return true;
    } catch (error) {
      reportFailed(form, error);
      return false;
    } finally {
      clearTimeout(timer);
    }
  };

  const drain = async () => {
    if (draining) return;
    draining = true;
    let success = true;
    while (queue.length > 0) {
      const task = queue.shift();
      success = await perform(task) && success;
      const state = stateOf(task.form);
      state.pending -= 1;
      if (state.pending === 0) {
        state.intended = undefined;
        task.form.removeAttribute("aria-busy");
      }
    }
    draining = false;
    return success;
  };

  const enqueue = task => {
    if (queue.length >= MAX_QUEUED) {
      say(task.form, "Слишком много изменений подряд. Подождите секунду и повторите.", { error: true });
      stateOf(task.form).invalid = true;
      return false;
    }
    stateOf(task.form).pending += 1;
    task.form.setAttribute("aria-busy", "true");
    say(task.form, "Сохраняем…", { sticky: true });
    queue.push(task);
    if (!draining) queueResult = drain();
    return true;
  };

  // Введённое число проверяется до отправки: в запрос попадает только целое от 0 до максимума.
  const commit = form => {
    const input = form.querySelector(".quantity-input");
    const max = Number(form.dataset.max);
    const raw = input.value.trim();
    const state = stateOf(form);
    delete input.dataset.dirty;
    if (!/^\d{1,4}$/.test(raw) || Number(raw) > max) {
      syncInput(form);
      say(form, `Введите число от 0 до ${max}.`, { error: true });
      state.invalid = true;
      return false;
    }
    const quantity = Number(raw);
    state.invalid = false;
    // Enter даёт change, затем submit: второй вызов с тем же значением ничего не отправляет.
    if (quantity === state.intended || (quantity === Number(form.dataset.count) && state.pending === 0)) return;
    state.intended = quantity;
    return enqueue({ form, action: "SET", quantity });
  };

  const request = (form, action) => {
    stateOf(form).invalid = false;
    stateOf(form).intended = undefined;
    if (action === "MINUS" && Number(form.dataset.count) === 0) return;
    enqueue({ form, action });
  };

  const enhance = form => {
    form.dataset.enhanced = "";
    // Недоступная кнопка остаётся в порядке фокуса: нажатие, после которого она гаснет, не теряет место.
    form.querySelectorAll("button[disabled]").forEach(button => {
      button.disabled = false;
      button.setAttribute("aria-disabled", "true");
    });
    const input = form.querySelector(".quantity-input");
    let lastButton = null;
    form.addEventListener("click", event => {
      const button = event.target.closest("button");
      if (!button) return;
      if (button.getAttribute("aria-disabled") === "true") {
        event.preventDefault();
        return;
      }
      lastButton = button;
    });
    form.addEventListener("submit", event => {
      event.preventDefault();
      const button = event.submitter ?? lastButton;
      if (button?.getAttribute("aria-disabled") === "true") return;
      const action = button?.value ?? "SET";
      if (action === "SET") commit(form);
      else request(form, action);
    });
    if (!input) return;
    input.addEventListener("input", () => { input.dataset.dirty = "true"; stateOf(form).invalid = false; });
    input.addEventListener("change", () => commit(form));
    input.addEventListener("keydown", event => { if (event.key === "Escape") syncInput(form); });
    // Колесо над активным полем не должно менять количество.
    input.addEventListener("wheel", () => { if (document.activeElement === input) input.blur(); }, { passive: true });
  };

  const enhanceCheckout = form => {
    const button = form.querySelector('button[type="submit"]');
    const status = form.querySelector("[data-checkout-status]");
    form.addEventListener("submit", async event => {
      event.preventDefault();
      if (purchasing) return;
      const quantities = [...document.querySelectorAll(".quantity-form")];
      quantities.forEach(quantity => { if (quantity.querySelector("input[data-dirty]")) commit(quantity); });
      if (quantities.some(quantity => stateOf(quantity).invalid)) {
        status.textContent = "Проверьте количество перед оформлением заказа.";
        return;
      }
      purchasing = true;
      quantities.forEach(quantity => { quantity.inert = true; });
      setDisabled(button, true);
      status.textContent = "Сохраняем изменения корзины…";
      // Покупка отправляется после всей очереди; один FOR UPDATE не сохраняет порядок HTTP-запросов.
      const saved = await queueResult;
      if (saved && !form.closest("[data-cart-layout]").hidden) {
        status.textContent = "Оформляем заказ…";
        form.submit();
      } else {
        status.textContent = saved ? "Корзина пуста." : "Покупка остановлена: изменение не подтверждено. Обновите страницу и проверьте корзину.";
        purchasing = false;
        quantities.forEach(quantity => { quantity.inert = false; });
        setDisabled(button, false);
        if (saved) document.querySelector("[data-cart-empty] h2")?.focus();
      }
    });
  };

  const start = () => {
    document.getElementById("theme-switch")?.addEventListener("click", () => {
      storedTheme = isDark() ? "light" : "dark";
      applyTheme();
      try { localStorage.setItem(THEME_KEY, storedTheme); } catch { /* значение просто не сохранится */ }
    });
    applyTheme();
    if ("fetch" in window && "AbortController" in window) {
      document.querySelectorAll(".quantity-form").forEach(enhance);
      document.querySelectorAll("[data-checkout]").forEach(enhanceCheckout);
    }
    // Страница из кэша «назад/вперёд» хранит устаревшие количества.
    window.addEventListener("pageshow", event => { if (event.persisted) location.reload(); });
  };

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start, { once: true });
  else start();
})();
