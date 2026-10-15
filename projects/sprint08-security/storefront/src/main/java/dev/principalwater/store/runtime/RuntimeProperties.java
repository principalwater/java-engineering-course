package dev.principalwater.store.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("store.runtime")
public record RuntimeProperties(@DefaultValue("2") int ioWorkers, @DefaultValue("1") int codecWorkers,
                                @DefaultValue("32") int codecQueue, @DefaultValue("256") int redisQueue, @DefaultValue("64") int maxInFlight) {
    public RuntimeProperties {
        if (ioWorkers < 1 || ioWorkers > 8 || codecWorkers < 1 || codecWorkers > 4
                || codecQueue < 1 || codecQueue > 256 || redisQueue < 1 || redisQueue > 1024 || maxInFlight < 8 || maxInFlight > 512) {
            throw new IllegalArgumentException("Runtime worker or queue budget is outside the supported range");
        }
    }
}
