package dev.principalwater.study.cloud.printer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.bus.BusProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RefreshScope
class PrinterController {
    private final String text;
    private final BusProperties bus;

    PrinterController(@Value("${text-to-print}") String text, BusProperties bus) {
        this.text = text;
        this.bus = bus;
    }

    @GetMapping("/texts")
    String text() {
        return bus.getId() + ":\n" + text;
    }
}
