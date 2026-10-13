(() => {
  const root = document.documentElement;
  const button = document.getElementById("theme-switch");
  let saved;
  try { saved = localStorage.getItem("store-theme"); } catch {}
  const systemDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  let dark = saved ? saved === "dark" : systemDark;
  const apply = () => {
    root.dataset.theme = dark ? "dark" : "light";
    button?.setAttribute("aria-pressed", String(dark));
  };
  apply();
  button?.addEventListener("click", () => {
    dark = !dark;
    apply();
    try { localStorage.setItem("store-theme", root.dataset.theme); } catch {}
  });
})();
document.querySelectorAll('.chart-column').forEach(column => {
  column.addEventListener('focus', () => column.classList.remove('tooltip-dismissed'));
  column.addEventListener('mouseenter', () => column.classList.remove('tooltip-dismissed'));
});
document.addEventListener('keydown', event => {
  if (event.key === 'Escape') {
    // Подсказка скрывается без потери фокуса и позиции в клавиатурной навигации.
    document.querySelector('.chart-column:focus, .chart-column:hover')?.classList.add('tooltip-dismissed');
  }
});
