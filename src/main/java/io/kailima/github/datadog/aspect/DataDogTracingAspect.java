package io.kailima.github.aspect;

import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
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
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;

@Aspect
@Component
public class DataDogTracingAspect {

    private final ObjectMapper objectMapper;
    private final Executor asyncExecutor;

    private static final Executor INTERNAL_ASYNC_EXECUTOR = createDefaultExecutor();

    @Autowired
    public DataDogTracingAspect(ObjectMapper objectMapper) {
        this.objectMapper = configureMapper(objectMapper);
        this.asyncExecutor = INTERNAL_ASYNC_EXECUTOR;
    }

    private static Executor createDefaultExecutor() {
        int corePoolSize = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
            corePoolSize,
            corePoolSize * 2,
            30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Around("@annotation(traceConfig)")
    public Object traceMethod(ProceedingJoinPoint jp,
                              DataDogTraceable traceConfig) throws Throwable {

        Tracer tracer = GlobalTracer.get();
        Span parent = tracer.activeSpan();
        boolean finishOnComplete = (parent == null);
        Span span = finishOnComplete
                ? createRootSpan(tracer, jp, traceConfig)
                : parent;

        Object result = null;
        Throwable error = null;

        try (Scope scope = tracer.activateSpan(span)) {
            result = jp.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            scheduleFinish(tracer, span, finishOnComplete, jp, traceConfig, result, error);
        }
    }

    private ObjectMapper configureMapper(ObjectMapper mapper) {
        ObjectMapper m = mapper.copy();
        m.findAndRegisterModules();
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return m;
    }

    private Span createRootSpan(Tracer tracer,
                                ProceedingJoinPoint jp,
                                DataDogTraceable cfg) {
        return tracer.buildSpan(resolveOperationName(jp, cfg))
                     .withTag(DDTags.SERVICE_NAME, resolveServiceName(cfg))
                     .withTag(DDTags.RESOURCE_NAME, resolveResourceName(cfg))
                     .start();
    }

    private void scheduleFinish(Tracer tracer,
                                Span span,
                                boolean finishOnComplete,
                                ProceedingJoinPoint jp,
                                DataDogTraceable cfg,
                                Object result,
                                Throwable error) {

        String[] paramNames = extractParameterNames(jp);
        Object[] args = jp.getArgs();

        Runnable processTask = () -> processSpan(tracer, span, cfg, args, paramNames, result, error);

        CompletableFuture.runAsync(processTask, asyncExecutor)
            .whenComplete((v, t) -> {
                if (finishOnComplete) {
                    span.finish();
                }
            });
    }


    private void processSpan(Tracer tracer,
                             Span span,
                             DataDogTraceable cfg,
                             Object[] args,
                             String[] paramNames,
                             Object result,
                             Throwable error) {
        try (Scope s = tracer.activateSpan(span)) {
            MutableSpan mspan = asMutable(span);

            if (cfg.captureInputs()) {
                tagInputs(mspan, args, paramNames);
            }
            if (cfg.captureOutput()) {
                tagOutput(mspan, result);
            }
            if (error != null) {
                tagError(mspan, error);
            }
        }
    }

    private MutableSpan asMutable(Span span) {
        if (span instanceof MutableSpan) {
            return (MutableSpan) span;
        }
        throw new IllegalStateException("Span não mutável: " + span);
    }

    private void tagInputs(MutableSpan span, Object[] args, String[] paramNames) {
        if (paramNames.length > 0) {
            int limit = Math.min(paramNames.length, args.length);
            for (int i = 0; i < limit; i++) {
                span.setTag("context.input." + paramNames[i],
                            safeSerialize(args[i]));
            }
        } else {
            for (int i = 0; i < args.length; i++) {
                span.setTag("context.input.arg" + i,
                            safeSerialize(args[i]));
            }
        }
    }

    private void tagOutput(MutableSpan span, Object returnValue) {
        Object processed = applyOutputRules(returnValue);
        span.setTag("context.output", safeSerialize(processed));

        if (processed instanceof List<?>) {
            iterateList(span, (List<?>) processed);
        } else if (processed instanceof Map<?, ?>) {
            iterateMap(span, convertToMapOrEmpty(processed));
        }
    }

    private void iterateList(MutableSpan span, List<?> list) {
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> flat = convertToMapOrEmpty(list.get(i));
            for (Map.Entry<String, Object> e : flat.entrySet()) {
                span.setTag(
                  String.format("context.output.fuck[%d].%s", i, e.getKey()),
                  String.valueOf(e.getValue())
                );
            }
        }
    }

    private void iterateMap(MutableSpan span, Map<String, Object> map) {
        Map<String, Object> sorted = new TreeMap<>(map);
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            span.setTag("context.output.detail" + e.getKey(),
                        String.valueOf(e.getValue()));
        }
    }

    private void tagError(MutableSpan span, Throwable error) {
        span.setTag(Tags.ERROR.getKey(), true);
        span.setTag("context.error.message", String.valueOf(error.getMessage()));
        span.setTag("context.error.stack", serializeStackTrace(error));
    }

    private Object applyOutputRules(Object returnValue) {
        if (returnValue instanceof List<?>) {
            return returnValue;
        }

        if (returnValue instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                if (v instanceof List<?>) {
                    return v;
                }
            }
            boolean allNumeric = true;
            TreeMap<Integer, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!key.matches("\\d+")) {
                    allNumeric = false;
                    break;
                }
                sorted.put(Integer.valueOf(key), entry.getValue());
            }
            if (allNumeric) {
                return new ArrayList<>(sorted.values());
            }
            return returnValue;
        }

        return returnValue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertToMapOrEmpty(Object obj) {
        if (obj instanceof Map<?, ?>) {
            return (Map<String, Object>) obj;
        }
        try {
            return objectMapper.convertValue(obj, Map.class);
        } catch (IllegalArgumentException ex) {
            return Collections.emptyMap();
        }
    }

    private String resolveServiceName(DataDogTraceable cfg) {
        return !cfg.serviceName().isEmpty()
                ? cfg.serviceName()
                : System.getenv().getOrDefault("DD_SERVICE", "default-service");
    }

    private String resolveResourceName(DataDogTraceable cfg) {
        return !cfg.resourceName().isEmpty()
                ? cfg.resourceName()
                : System.getenv().getOrDefault("DD_RESOURCE", "default-resource");
    }

    private String resolveOperationName(ProceedingJoinPoint jp,
                                        DataDogTraceable cfg) {
        return !cfg.operationName().isEmpty()
                ? cfg.operationName()
                : jp.getSignature().getName();
    }

    private String[] extractParameterNames(ProceedingJoinPoint jp) {
        Signature sig = jp.getSignature();
        if (sig instanceof MethodSignature) {
            return ((MethodSignature) sig).getParameterNames();
        }
        return new String[0];
    }

    private String safeSerialize(Object obj) {
        if (obj == null) {
            return "null";
        }
        if (obj instanceof TemporalAccessor) {
            return obj.toString();
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return String.valueOf(obj);
        }
    }

    private String serializeStackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : t.getStackTrace()) {
            sb.append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}
