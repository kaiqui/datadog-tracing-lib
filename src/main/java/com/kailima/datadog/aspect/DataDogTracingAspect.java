package com.kailima.datadog.aspect;

import com.kailima.datadog.annotation.DataDogTraceable;
import com.kailima.datadog.exception.DataDogTracingException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.trace.api.DDTags;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

@Aspect
@Component
public class DataDogTracingAspect {

    private final Executor asyncExecutor;
    private final ObjectMapper objectMapper;

    @Autowired
    public DataDogTracingAspect(Executor asyncExecutor, ObjectMapper objectMapper) {
        this.asyncExecutor = asyncExecutor;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(traceConfig)")
    public Object traceMethod(ProceedingJoinPoint joinPoint, DataDogTraceable traceConfig) throws Throwable {
        final Tracer tracer = GlobalTracer.get();
        final Span parentSpan = tracer.activeSpan();

        // Configurar tags principais
        final Map<String, String> tracingTags = resolveTags(traceConfig);

        // Criar span principal
        Span span = tracer.buildSpan(resolveOperationName(joinPoint, traceConfig))
                .withTag(DDTags.SERVICE_NAME, tracingTags.get("service"))
                .withTag(DDTags.RESOURCE_NAME, tracingTags.get("resource"))
                .start();

        try (Scope scope = tracer.activateSpan(span)) {
            final Object[] inputs = joinPoint.getArgs();
            Object output = null;
            Throwable exception = null;

            try {
                output = joinPoint.proceed();
                return output;
            } catch (Throwable t) {
                exception = t;
                throw t;
            } finally {
                final Object finalOutput = output;
                final Throwable finalException = exception;

                asyncExecutor.execute(() ->
                        processAsync(
                                tracer,
                                parentSpan,
                                inputs,
                                finalOutput,
                                finalException,
                                tracingTags,
                                traceConfig
                        )
                );
            }
        } finally {
            span.finish();
        }
    }

    private Map<String, String> resolveTags(DataDogTraceable config) {
        Map<String, String> tags = new HashMap<>();

        tags.put("service",
                !config.serviceName().isEmpty() ? config.serviceName() :
                        System.getenv().getOrDefault("DD_SERVICE", "default-service"));

        tags.put("resource",
                !config.resourceName().isEmpty() ? config.resourceName() :
                        System.getenv().getOrDefault("DD_RESOURCE", "default-resource"));

        tags.put("operation",
                !config.operationName().isEmpty() ? config.operationName() :
                        System.getenv().getOrDefault("DD_OPERATION", "default-operation"));

        return tags;
    }

    private String resolveOperationName(ProceedingJoinPoint joinPoint, DataDogTraceable config) {
        return !config.operationName().isEmpty() ?
                config.operationName() :
                joinPoint.getSignature().getName();
    }

    private void processAsync(Tracer tracer,
                              Span parentSpan,
                              Object[] inputs,
                              Object output,
                              Throwable exception,
                              Map<String, String> tags,
                              DataDogTraceable config) {

        Span asyncSpan = tracer.buildSpan(tags.get("operation"))
                .asChildOf(parentSpan)
                .withTag(DDTags.SERVICE_NAME, tags.get("service"))
                .withTag(DDTags.RESOURCE_NAME, tags.get("resource"))
                .start();

        try (Scope scope = tracer.activateSpan(asyncSpan)) {
            // Processar inputs
            if (config.captureInputs()) {
                processInputs(asyncSpan, inputs);
            }

            // Processar output
            if (config.captureOutput()) {
                processOutput(asyncSpan, output, exception);
            }
        } finally {
            asyncSpan.finish();
        }
    }

    private void processInputs(Span span, Object[] inputs) {
        try {
            for (int i = 0; i < inputs.length; i++) {
                String serialized = objectMapper.writeValueAsString(inputs[i]);
                span.setTag("context.input." + i, serialized);

                // Adicionar campos individualmente
                Map<String, Object> fields = objectMapper.convertValue(inputs[i], Map.class);
                int finalI = i;
                fields.forEach((key, value) ->
                        span.setTag("context.input." + finalI + "." + key, value.toString())
                );
            }
        } catch (IllegalArgumentException | JsonProcessingException e) {
            span.setTag("context.input.error", "Serialization failed: " + e.getMessage());
        }
    }

    private void processOutput(Span span, Object output, Throwable exception) {
        try {
            if (exception != null) {
                span.setTag(Tags.ERROR, true);
                span.setTag("context.error.message", exception.getMessage());
                span.setTag("context.error.stack", serializeStackTrace(exception));
                return;
            }

            if (output != null) {
                String serialized = objectMapper.writeValueAsString(output);
                span.setTag("context.output", serialized);

                Map<String, Object> fields = objectMapper.convertValue(output, Map.class);
                fields.forEach((key, value) ->
                        span.setTag("context.output." + key, value.toString())
                );
            }
        } catch (IllegalArgumentException | JsonProcessingException e) {
            span.setTag("context.output.error", "Serialization failed: " + e.getMessage());
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