package dev.principalwater.store.util;

import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.result.view.RedirectView;

public final class StoreRedirects {
    private StoreRedirects() {
    }

    public static RedirectView to(String path) {
        return new RedirectView(path, HttpStatus.SEE_OTHER);
    }
}
