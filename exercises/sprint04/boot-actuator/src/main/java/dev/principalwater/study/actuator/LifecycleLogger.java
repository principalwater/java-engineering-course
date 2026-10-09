package dev.principalwater.study.actuator;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;

public class LifecycleLogger implements ApplicationListener<ApplicationEvent> {
    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof AvailabilityChangeEvent<?> availability) {
            System.out.println("Availability: " + availability.getState().getClass().getSimpleName()
                    + "." + availability.getState());
        } else {
            System.out.println("Event: " + event.getClass().getSimpleName());
        }
    }
}
