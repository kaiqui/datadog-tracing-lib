package io.kailima.github.datadog.aspect;

import java.util.Set;

public class DefaultMaskingStrategy implements DataMaskingStrategy {
    @Override
    public Object mask(String fieldName, Object rawValue, Set<String> excludedFields) {
        return rawValue;
    }
}