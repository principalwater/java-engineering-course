(() => {
  const iconNames = {
    "bi-pencil": "Редактировать",
    "bi-trash3": "Удалить",
    "bi-plus-square": "Добавить комментарий",
    "bi-heart": "Добавить лайк",
    "bi-chat-left": "Комментарии",
    "bi-chevron-double-left": "Первая страница",
    "bi-chevron-left": "Предыдущая страница",
    "bi-chevron-right": "Следующая страница",
    "bi-chevron-double-right": "Последняя страница"
  };

  function enhance() {
    const root = document.getElementById("root");
    root.setAttribute("role", "main");
    root.tabIndex = -1;
    const heading = root.querySelector("h1 .badge");
    if (heading && heading.textContent === "Мой блог") heading.textContent = "Тетрадь инженера";
    const pageSizeLabel = root.querySelector('label[for="floatingSelect"]');
    if (pageSizeLabel && pageSizeLabel.textContent === "Постов на странице") pageSizeLabel.textContent = "На странице";
    root.querySelectorAll("button:not([aria-label])").forEach(button => {
      const icon = button.querySelector("svg");
      if (!icon) return;
      const name = Object.keys(iconNames).find(value => icon.classList.contains(value));
      if (!name) return;
      const toolbar = button.closest(".row") === root.querySelector(".container-md > .row:first-child");
      button.setAttribute("aria-label", toolbar && name === "bi-pencil" && /^\/(posts\/?)?$/.test(location.pathname) ? "Добавить пост" : iconNames[name]);
      icon.setAttribute("aria-hidden", "true");
      if (toolbar && name === "bi-trash3") {
        // В исходном клиенте удаление поста привязано к SVG, поэтому клавиатура должна активировать его.
        button.addEventListener("click", event => { if (event.target === button) icon.dispatchEvent(new MouseEvent("click", { bubbles: true })); });
      }
    });
    root.querySelectorAll(".card-img-top:not([alt])").forEach(image => {
      image.alt = image.closest(".card")?.querySelector(".card-title")?.textContent || "Иллюстрация поста";
    });
    document.querySelectorAll(".modal :is(input, textarea, select)").forEach((control, index) => {
      const label = control.parentElement.querySelector("label");
      if (!label) return;
      control.id = `notebook-field-${index}`;
      label.htmlFor = control.id;
    });
    document.querySelectorAll(".modal .btn-close").forEach(button => button.setAttribute("aria-label", "Закрыть"));
  }

  const skip = document.createElement("a");
  skip.className = "skip-link";
  skip.href = "#root";
  skip.textContent = "Перейти к содержимому";
  document.body.prepend(skip);
  enhance();
  new MutationObserver(enhance).observe(document.body, { childList: true, subtree: true });
})();
