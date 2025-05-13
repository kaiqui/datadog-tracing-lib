package io.kailima.github.datadog.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DataDogTraceable {
    String serviceName() default "";
    String resourceName() default "";
    String operationName() default "";
    boolean captureInputs() default true;
    boolean captureOutput() default true;
    String[] excludedFields() default {};
}