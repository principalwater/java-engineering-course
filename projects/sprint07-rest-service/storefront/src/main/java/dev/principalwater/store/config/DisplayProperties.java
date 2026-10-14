package dev.principalwater.store.config;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("store.display")
public record DisplayProperties(@DefaultValue("Europe/Moscow") ZoneId zone) {
}
