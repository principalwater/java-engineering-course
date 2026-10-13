package dev.principalwater.store.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.view.RedirectView;

public final class StoreRedirects {
    private StoreRedirects() {
    }

    public static RedirectView to(String path) {
        var view = new RedirectView(path, true);
        view.setStatusCode(HttpStatus.SEE_OTHER);
        // Общая модель содержит корзину: её нельзя сериализовать в query-параметры редиректа.
        view.setExposeModelAttributes(false);
        return view;
    }
}
