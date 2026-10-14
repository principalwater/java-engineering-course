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
  const MINOR_UNITS_PER_RUBLE = 100n;
  const checkoutKeyPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
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
  let refreshPayment = null;
  let confirmedCheckoutKey = null;
  let cartNeedsReload = false;

  class RequestError extends Error {
    constructor(status, code) {
      super(`Cart request failed with HTTP ${status}`);
      this.status = status;
      this.code = code;
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
        row.dataset.price = String(item.price);
        row.querySelector("[data-unit-price]").textContent = `${money(big(item.price))} за предмет`;
        const lineTotal = row.querySelector("[data-line-total]");
        if (lineTotal) lineTotal.textContent = money(big(item.price) * big(item.count));
        return;
      }
      // Промежуточный ноль не уничтожает форму, если для неё уже принято следующее изменение.
      if (queue.some(task => task.form.dataset.itemId === row.dataset.productId)) {
        row.dataset.count = "0";
        row.querySelector("[data-line-total]").textContent = money(0n);
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
    const hasRows = cartNeedsReload || layout.querySelector(".cart-line") !== null;
    layout.hidden = !hasRows;
    const empty = document.querySelector("[data-cart-empty]");
    if (empty) empty.hidden = hasRows;
    if (!hasRows) {
      if (empty) {
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
    if (stateOf(form).pending > 1) {
      say(form, "Сохраняем…", { sticky: true });
      return;
    }
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

  const failureMessage = (form, status, code) => {
    switch (status) {
      case 400: return { text: `Не удалось принять количество. Введите число от 0 до ${form.dataset.max}.` };
      case 403: return { text: "Страница устарела. Обновите её и повторите.", reload: true };
      case 404: return { text: "Эта вещь уже недоступна. Обновите страницу.", reload: true };
      case 409:
        if (code === "CHECKOUT_IN_PROGRESS") {
          return { text: "Корзина заблокирована на время проверки платежа. Изменение не применено.", cart: true };
        }
        if (code === "QUANTITY_LIMIT" || code === "CART_LIMIT") {
          return { text: "Корзина не приняла изменение: достигнут предел количества или числа позиций. Количество прежнее." };
        }
        return { text: "Корзина изменилась. Обновите страницу и проверьте её состав.", reload: true };
      default: return { text: "Не удалось подтвердить изменение. Обновите страницу, чтобы увидеть актуальную корзину.", reload: true };
    }
  };

  const reportFailed = (form, error) => {
    console.warn("Cart update failed:", error);
    syncInput(form);
    const message = failureMessage(form, error instanceof RequestError ? error.status : 0, error.code);
    const content = [message.text];
    if (message.cart) {
      const link = document.createElement("a");
      link.href = form.dataset.endpoint;
      link.textContent = "Проверить платёж в корзине";
      content.push(" ", link);
    }
    say(form, content, { error: true, reload: message.reload });
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
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new RequestError(response.status, problem?.code);
      }
      if (response.redirected || !(response.headers.get("content-type") ?? "").includes("json")) {
        // Сервер применил изменение, но ответил страницей: актуальное состояние покажет перезагрузка.
        location.reload();
        return false;
      }
      const result = await response.json();
      if (!result?.cart || !Array.isArray(result.cart.items) || typeof result.checkoutKey !== "string"
          || !checkoutKeyPattern.test(result.checkoutKey)) {
        throw new Error("Unexpected cart response shape");
      }
      const layout = document.querySelector("[data-cart-layout]");
      if (layout && !cartNeedsReload) {
        const rows = new Map([...layout.querySelectorAll(".cart-line")].map(row => [row.dataset.productId, row]));
        cartNeedsReload = result.cart.items.some(item => {
          const row = rows.get(String(item.id));
          return !row || row.querySelector(".item-title").textContent !== item.title
            || row.querySelector(".cart-art img").getAttribute("src") !== item.imgPath;
        });
      }
      confirmedCheckoutKey = cartNeedsReload ? null : result.checkoutKey;
      if (cartNeedsReload && refreshPayment) void refreshPayment();
      applyCart(result.cart);
      reportSaved(form, before, Number(result.count));
      return result.cart;
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
    let lastCart = null;
    while (queue.length > 0) {
      const task = queue.shift();
      const cart = await perform(task);
      if (cart) lastCart = cart;
      else success = false;
      const state = stateOf(task.form);
      state.pending -= 1;
      if (state.pending === 0) {
        state.intended = undefined;
        task.form.removeAttribute("aria-busy");
      }
    }
    draining = false;
    if (lastCart) applyCart(lastCart);
    if (lastCart && !purchasing && refreshPayment) void refreshPayment({ fresh: true });
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
    const message = document.querySelector("[data-payment-message]");
    const balance = document.querySelector("[data-wallet-balance]");
    const refreshButton = document.querySelector("[data-payment-refresh]");
    const keyInput = form.elements.namedItem("checkoutKey");
    const amountInput = form.elements.namedItem("amountMinor");
    const pendingAmount = document.querySelector("[data-pending-amount]");
    const paymentStates = new Set(["AVAILABLE", "INSUFFICIENT", "UNAVAILABLE", "PENDING"]);
    let payment = { status: form.dataset.paymentState, canBuy: !button.disabled, pending: form.dataset.pending === "true" };
    let paymentRequest = null;
    let paymentController = null;
    let paymentVersion = 0;
    let checking = false;
    let stale = form.dataset.stale === "true";
    const permitted = () => !cartNeedsReload && !stale && (payment.pending || (payment.status === "AVAILABLE" && payment.canBuy));
    const renderButton = () => {
      if (cartNeedsReload) {
        stale = true;
        message.textContent = "Изменение сохранено. Состав или описание товаров изменились в другом окне. Перезагрузите корзину и проверьте заказ перед оплатой.";
        message.classList.add("is-error");
        if (refreshButton) refreshButton.textContent = "Перезагрузить корзину";
      }
      button.disabled = purchasing || checking || !permitted();
      setDisabled(button, button.disabled);
      button.textContent = payment.pending ? "Проверить платёж" : "Оплатить и оформить";
      button.setAttribute("aria-describedby", payment.pending ? "payment-message pending-amount" : "payment-message");
      document.querySelector("[data-cart-total-label]").textContent = payment.pending ? "Корзина по текущим ценам" : "Итого";
      form.closest("[data-cart-layout]").querySelector(".cart-lines").inert = purchasing || payment.pending || stale;
    };
    // Баланс не кэшируется в браузере: после изменений корзины и перед оплатой перечитываем состояние сервера.
    refreshPayment = ({ fresh = false } = {}) => {
      if (cartNeedsReload) {
        paymentController?.abort();
        renderButton();
        return Promise.resolve(false);
      }
      if (paymentRequest && !fresh) return paymentRequest;
      paymentController?.abort();
      const ticket = ++paymentVersion;
      const controller = new AbortController();
      paymentController = controller;
      const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
      checking = true;
      renderButton();
      if (refreshButton) refreshButton.disabled = true;
      message.textContent = "Проверяем баланс и состояние оплаты…";
      paymentRequest = (async () => {
        try {
          const response = await fetch(form.dataset.availability, {
            headers: { Accept: "application/json" }, credentials: "same-origin", cache: "no-store", signal: controller.signal,
          });
          if (!response.ok) throw new Error(`Payment availability request failed with HTTP ${response.status}`);
          const next = await response.json();
          if (!paymentStates.has(next?.status) || typeof next.canBuy !== "boolean" || typeof next.pending !== "boolean"
              || typeof next.message !== "string" || typeof next.balanceLabel !== "string" || typeof next.requiredLabel !== "string"
              || typeof next.checkoutKey !== "string" || !checkoutKeyPattern.test(next.checkoutKey)
              || typeof next.requiredMinor !== "string" || !/^\d+$/.test(next.requiredMinor)
              || (next.balanceMinor != null && (typeof next.balanceMinor !== "string" || !/^\d+$/.test(next.balanceMinor)))) {
            throw new Error("Invalid payment availability response");
          }
          if (ticket !== paymentVersion) return false;
          if (draining) {
            await queueResult;
            return ticket === paymentVersion ? refreshPayment({ fresh: true }) : false;
          }
          payment = next;
          form.dataset.paymentState = next.status;
          form.dataset.pending = String(next.pending);
          const total = document.querySelector("[data-cart-total]").dataset.cartTotal;
          const expectedKey = confirmedCheckoutKey ?? keyInput.value;
          // Только ACK нашего изменения разрешает новый ключ; чужую корзину нельзя оплатить из старой страницы.
          stale = cartNeedsReload || (!next.pending && (next.checkoutKey !== expectedKey || big(next.requiredMinor) !== big(total) * MINOR_UNITS_PER_RUBLE));
          if (!stale) {
            keyInput.value = next.checkoutKey;
            amountInput.value = next.requiredMinor;
            confirmedCheckoutKey = null;
          }
          pendingAmount.hidden = !next.pending;
          pendingAmount.querySelector("[data-pending-amount-value]").textContent = next.requiredLabel;
          message.textContent = stale
            ? "Корзина или цены изменились в другом окне. Перезагрузите корзину и проверьте состав перед оплатой."
            : next.message;
          message.classList.toggle("is-error", stale || next.status === "INSUFFICIENT" || next.status === "UNAVAILABLE");
          if (refreshButton) refreshButton.textContent = stale ? "Перезагрузить корзину" : "Обновить";
          balance.textContent = next.balanceLabel;
          return permitted();
        } catch (error) {
          if (ticket !== paymentVersion) return false;
          payment = { status: "UNAVAILABLE", canBuy: false, pending: payment.pending };
          message.textContent = payment.pending
            ? "Платёж пока не подтверждён. Проверьте его ещё раз: повторного списания не будет."
            : "Не удалось проверить баланс. Покупка недоступна. Нажмите «Обновить», чтобы повторить проверку.";
          message.classList.add("is-error");
          balance.textContent = "Не удалось проверить";
          console.warn("Payment availability refresh failed:", error);
          return permitted();
        } finally {
          clearTimeout(timer);
          if (ticket === paymentVersion) {
            checking = false;
            paymentRequest = null;
            paymentController = null;
            if (refreshButton) refreshButton.disabled = false;
            renderButton();
          }
        }
      })();
      return paymentRequest;
    };
    if (refreshButton) {
      refreshButton.hidden = false;
      refreshButton.textContent = stale ? "Перезагрузить корзину" : "Обновить";
      refreshButton.addEventListener("click", () => {
        if (purchasing) return;
        if (stale) location.reload();
        else void refreshPayment();
      });
    }
    window.addEventListener("focus", () => { if (!purchasing) void refreshPayment(); });
    renderButton();
    form.addEventListener("submit", async event => {
      event.preventDefault();
      if (purchasing || button.disabled) return;
      const quantities = [...document.querySelectorAll(".quantity-form")];
      const recovery = payment.pending;
      if (!recovery) quantities.forEach(quantity => { if (quantity.querySelector("input[data-dirty]")) commit(quantity); });
      if (!recovery && quantities.some(quantity => stateOf(quantity).invalid)) {
        status.textContent = "Проверьте количество перед оформлением заказа.";
        return;
      }
      purchasing = true;
      quantities.forEach(quantity => { quantity.inert = true; });
      renderButton();
      status.textContent = "Сохраняем изменения корзины…";
      // Покупка отправляется после всей очереди; один FOR UPDATE не сохраняет порядок HTTP-запросов.
      const saved = await queueResult;
      const nonempty = !form.closest("[data-cart-layout]").hidden;
      const ready = (saved || recovery) && nonempty && await refreshPayment({ fresh: true });
      if (ready) {
        status.textContent = payment.pending ? "Проверяем платёж…" : "Подтверждаем оплату…";
        form.submit();
      } else {
        status.textContent = !saved
          ? "Покупка остановлена: изменение не подтверждено. Обновите страницу и проверьте корзину."
          : nonempty ? "Покупка не отправлена. Проверьте сообщение о балансе выше." : "Корзина пуста.";
        purchasing = false;
        quantities.forEach(quantity => { quantity.inert = false; });
        renderButton();
        if (saved && !nonempty) document.querySelector("[data-cart-empty] h2")?.focus();
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
