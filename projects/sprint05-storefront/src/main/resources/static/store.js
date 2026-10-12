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
