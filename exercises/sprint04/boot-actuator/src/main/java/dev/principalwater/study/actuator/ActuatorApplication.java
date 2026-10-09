package dev.principalwater.study.actuator;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ActuatorApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ActuatorApplication.class);
        application.addListeners(new LifecycleLogger());
        application.run(args);
    }

    @Bean
    CommandLineRunner showBuildTime(BuildProperties build) {
        return args -> System.out.println("Build time: " + build.getTime());
    }
}
