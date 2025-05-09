package io.kailima.github.datadog.aspect;

import java.time.temporal.TemporalAccessor;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

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
@Component
public class DataDogTracingAspect {

    private final Tracer tracer = GlobalTracer.get();
    private final ObjectMapper mapper;
    private final Executor executor;

    public DataDogTracingAspect(ObjectMapper baseMapper) {
        this.mapper = baseMapper.copy()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        int cores = Runtime.getRuntime().availableProcessors();
        this.executor = new ThreadPoolExecutor(
            cores, cores * 2,
            30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1_000),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
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
        String[] names = extractParamNames(jp);
        Object[] args = jp.getArgs();

        CompletableFuture.runAsync(() -> {
            try (Scope s = tracer.activateSpan(span)) {
                MutableSpan m = span instanceof MutableSpan ? (MutableSpan) span : throwIllegal(span);

                if (cfg.captureInputs()) {
                    tagCollection(m, "context.input", args, names);
                }
                if (cfg.captureOutput()) {
                    tagObject(m, "context.output", result);
                }
                if (error != null) {
                    m.setTag("error", true);
                    tagValue(m, "error.message", error.getMessage());
                }
            } catch (Exception ex) {
                System.err.println("Erro no DataDogTracingAspect: " + ex.getMessage());
            } finally {
                if (finishOnComplete) span.finish();
            }
        }, executor);
    }

    private MutableSpan throwIllegal(Span span) {
        throw new IllegalStateException("Span não mutável: " + span);
    }

    private void tagCollection(MutableSpan span, String prefix, Object[] values, String[] names) {
        for (int i = 0; i < values.length; i++) {
            String key = names.length > i && names[i] != null
                ? prefix + "." + names[i]
                : prefix + ".arg" + i;
            tagObject(span, key, values[i]);
        }
    }

    @SuppressWarnings("unchecked")
    private void tagObject(MutableSpan span, String key, Object obj) {
        if (obj == null) {
            span.setTag(key, "null");
        } else if (obj instanceof Map<?, ?> map) {
            map.forEach((k, v) -> tagObject(span, key + "." + k, v));
        } else if (obj instanceof Iterable<?> it) {
            int idx = 0;
            for (Object item : it) {
                tagObject(span, key + "[" + idx++ + "]", item);
            }
        } else if (obj instanceof TemporalAccessor) {
            span.setTag(key, obj.toString());
        } else if (isPrimitiveOrWrapper(obj.getClass()) || obj instanceof String) {
            span.setTag(key, String.valueOf(obj));
        } else {
            try {
                Map<String, Object> map = mapper.convertValue(obj, Map.class);
                map.forEach((k, v) -> tagObject(span, key + "." + k, v));
            } catch (IllegalArgumentException e) {
                span.setTag(key, safeSerialize(obj));
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
        return !cfg.operationName().isEmpty() ? cfg.operationName() : jp.getSignature().getName();
    }

    private String resolveService(DataDogTraceable cfg) {
        return !cfg.serviceName().isEmpty() ? cfg.serviceName() : System.getenv().getOrDefault("DD_SERVICE", "default-service");
    }

    private String resolveResource(DataDogTraceable cfg) {
        return !cfg.resourceName().isEmpty() ? cfg.resourceName() : System.getenv().getOrDefault("DD_RESOURCE", "default-resource");
    }

    private String[] extractParamNames(ProceedingJoinPoint jp) {
        if (jp.getSignature() instanceof MethodSignature ms) {
            return ms.getParameterNames();
        }
        return new String[0];
    }
}
