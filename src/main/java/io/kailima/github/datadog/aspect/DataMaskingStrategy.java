package io.kailima.github.datadog.aspect;

import java.util.Set;

public interface DataMaskingStrategy {
    Object mask(String fieldName, Object rawValue, Set<String> excludedFields);
}