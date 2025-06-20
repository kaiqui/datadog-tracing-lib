package io.kailima.github.datadog.aspect;

import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import datadog.trace.api.DDTags;
import datadog.trace.api.interceptor.MutableSpan;
import io.kailima.github.datadog.annotation.DataDogTraceable;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;

@Aspect
public class DataDogTracingAspect {

    private final Tracer tracer = GlobalTracer.get();
    private final ObjectMapper mapper;
    private Executor executor;
    
    public DataDogTracingAspect() {
        this.mapper = createObjectMapper();
    }

    // Permite configuração externa do executor
    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public Executor getExecutor() {
        return this.executor;
    }

    private ObjectMapper createObjectMapper() {
        return new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    @Around("@annotation(cfg)")
    public Object aroundTrace(ProceedingJoinPoint jp, DataDogTraceable cfg) throws Throwable {
        Span active = tracer.activeSpan();
        boolean isRoot = (active == null);
        Span span = isRoot
            ? tracer.buildSpan(resolveName(jp, cfg))
                    .withTag(DDTags.SERVICE_NAME, resolveService(cfg))
                    .withTag(DDTags.RESOURCE_NAME, resolveResource(cfg))
                    .start()
            : active;

        Object result = null;
        Throwable error = null;

        try (Scope scope = tracer.activateSpan(span)) {
            result = jp.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            finishAsync(span, isRoot, jp, cfg, result, error);
        }
    }

    private void finishAsync(Span span,
                             boolean finishOnComplete,
                             ProceedingJoinPoint jp,
                             DataDogTraceable cfg,
                             Object result,
                             Throwable error) {
        if (!cfg.captureInputs() && !cfg.captureOutput()) {
            if (finishOnComplete) span.finish();
            return;
        }

        String[] names = extractParamNames(jp);
        Object[] args = jp.getArgs();
        Set<String> excluded = new HashSet<>(Arrays.asList(cfg.excludedFields()));

        try {
            CompletableFuture.runAsync(() -> {
                try (Scope s = tracer.activateSpan(span)) {
                    MutableSpan m = (MutableSpan) span;

                    if (cfg.captureInputs())  tagCollection(m, "context.input",  args,   names, excluded);
                    if (cfg.captureOutput())  tagObject(   m, "context.output", result,       excluded);
                    if (error != null && !excluded.contains("error.message")) {
                        m.setTag("error", true);
                        m.setTag("error.message", error.getMessage());
                    }
                } catch (Exception ex) {
                    System.err.println("Erro no DataDogTracingAspect: " + ex.getMessage());
                } finally {
                    if (finishOnComplete) span.finish();
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            // Fallback síncrono
            try (Scope s = tracer.activateSpan(span)) {
                MutableSpan m = (MutableSpan) span;

                if (cfg.captureInputs())  tagCollection(m, "context.input",  args,   names, excluded);
                if (cfg.captureOutput())  tagObject(   m, "context.output", result,       excluded);
                if (error != null && !excluded.contains("error.message")) {
                    m.setTag("error", true);
                    m.setTag("error.message", error.getMessage());
                }
            } catch (Exception ex) {
                System.err.println("Erro no fallback síncrono: " + ex.getMessage());
            } finally {
                if (finishOnComplete) span.finish();
            }
        }
    }

    private void tagCollection(MutableSpan span,
                               String prefix,
                               Object[] values,
                               String[] names,
                               Set<String> excluded) {
        for (int i = 0; i < values.length; i++) {
            String field = names.length > i && names[i] != null ? names[i] : "arg" + i;
            if (excluded.contains(field)) continue;
            String key = prefix + "." + field;
            tagObject(span, key, values[i], excluded);
        }
    }

    private void tagObject(MutableSpan span,
                           String key,
                           Object obj,
                           Set<String> excluded) {
        String fieldName = key.contains(".")
            ? key.substring(key.lastIndexOf('.') + 1).replaceAll("\\[\\d+\\]", "")
            : key;
        if (excluded.contains(fieldName)) return;

        if (obj == null) {
            return;
        } else if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                tagObject(span, key + "." + entry.getKey(), entry.getValue(), excluded);
            }
        } else if (obj instanceof Iterable) {
            Iterable<?> it = (Iterable<?>) obj;
            int idx = 0;
            for (Object item : it) {
                tagObject(span, key + "[" + idx++ + "]", item, excluded);
            }
        } else if (obj instanceof TemporalAccessor) {
            tagValue(span, key, obj.toString());
        } else if (isPrimitiveOrWrapper(obj.getClass()) || obj instanceof String) {
            tagValue(span, key, String.valueOf(obj));
        } else {
            try {
                Map<String, Object> map = mapper.convertValue(obj, Map.class);
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    tagObject(span, key + "." + entry.getKey(), entry.getValue(), excluded);
                }
            } catch (IllegalArgumentException e) {
                tagValue(span, key, safeSerialize(obj));
            }
        }
    }

    private boolean isPrimitiveOrWrapper(Class<?> cls) {
        return cls.isPrimitive() ||
               cls == Boolean.class || cls == Byte.class ||
               cls == Character.class || cls == Short.class ||
               cls == Integer.class || cls == Long.class ||
               cls == Float.class || cls == Double.class;
    }

    private void tagValue(MutableSpan span, String key, String value) {
        span.setTag(key, value);
    }

    private String safeSerialize(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return String.valueOf(obj);
        }
    }

    private String resolveName(ProceedingJoinPoint jp, DataDogTraceable cfg) {
        return !cfg.operationName().isEmpty()
            ? cfg.operationName()
            : jp.getSignature().getName();
    }

    private String resolveService(DataDogTraceable cfg) {
        return !cfg.serviceName().isEmpty()
            ? cfg.serviceName()
            : System.getenv().getOrDefault("DD_SERVICE", "default-service");
    }

    private String resolveResource(DataDogTraceable cfg) {
        return !cfg.resourceName().isEmpty()
            ? cfg.resourceName()
            : System.getenv().getOrDefault("DD_RESOURCE", "default-resource");
    }

    private String[] extractParamNames(ProceedingJoinPoint jp) {
        if (jp.getSignature() instanceof MethodSignature) {
            MethodSignature ms = (MethodSignature) jp.getSignature();
            return ms.getParameterNames();
        }
        return new String[0];
    }
}