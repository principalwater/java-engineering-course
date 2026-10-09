package dev.principalwater.study.aop;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration(proxyBeanMethods = false)
@EnableAspectJAutoProxy
public class AppConfiguration {
    @Bean
    UserService userService() {
        return new UserService();
    }

    @Bean
    UserController userController(UserService service) {
        return new UserController(service);
    }

    @Bean
    UserTimingAspect userTimingAspect() {
        return new UserTimingAspect();
    }
}
