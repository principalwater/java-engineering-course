package dev.principalwater.study.http;

import org.slf4j.event.Level;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("application.http.logging")
public class HttpLoggerProperties {
    /** Уровень записи входящих запросов. */
    private Level level = Level.INFO;
    /** Включение логгера; отсутствие свойства также включает автоконфигурацию. */
    private boolean enabled = true;

    public Level getLevel() { return level; }
    public void setLevel(Level level) { this.level = level; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
