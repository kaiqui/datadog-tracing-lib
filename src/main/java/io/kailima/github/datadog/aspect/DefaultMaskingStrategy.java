package io.kailima.github.datadog.aspect;

import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class DefaultMaskingStrategy implements DataMaskingStrategy {
    @Override
    public Object mask(String fieldName, Object rawValue, Set<String> excludedFields) {
        return rawValue;
    }
}