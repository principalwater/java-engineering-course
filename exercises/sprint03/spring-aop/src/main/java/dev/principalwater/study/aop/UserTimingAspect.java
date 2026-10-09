package dev.principalwater.study.aop;

import java.util.Arrays;
import java.util.logging.Logger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

@Aspect
public class UserTimingAspect {
    private static final Logger LOGGER = Logger.getLogger(UserTimingAspect.class.getName());

    @Around("execution(public * dev.principalwater.study.aop.UserController.*(java.lang.String))")
    public Object measure(ProceedingJoinPoint point) throws Throwable {
        long started = System.nanoTime();
        try {
            return point.proceed();
        } finally {
            // Замер включает ошибочный вызов, но не подменяет его исключение.
            long elapsed = System.nanoTime() - started;
            LOGGER.info("method=" + point.getSignature().toShortString()
                    + " args=" + Arrays.toString(point.getArgs()) + " elapsedNanos=" + elapsed);
        }
    }
}
