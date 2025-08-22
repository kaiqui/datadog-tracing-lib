package io.kailima.github.datadog.service;

import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.*;
import java.util.*;

public class SpanObjectProcessor {
    private static final Logger logger = LoggerFactory.getLogger(SpanObjectProcessor.class);
    
    public static void processObject(Object obj, String prefix) {
        try {
            if (obj == null) {
                logger.warn("Attempted to process null object");
                return;
            }
            
            Class<?> clazz = obj.getClass();
            
            if (isSimpleType(clazz)) {
                String snakeKey = camelToSnake(prefix != null ? prefix : "value");
                String valueStr = obj.toString();
                
                logger.debug("Processing simple type: {} = {}", snakeKey, valueStr);
                
                setDatadogTag(snakeKey, valueStr);
                return;
            }
            
            Map<String, Object> fields = extractFields(obj);
            
            logger.info("Processing object of type: {}", clazz.getSimpleName());
            
            fields.forEach((key, value) -> {
                try {
                    String snakeKey = camelToSnake(key);
                    String fullKey = (prefix != null && !prefix.isEmpty()) ? prefix + "." + snakeKey : snakeKey;
                    String valueStr = (value != null) ? value.toString() : "null";
                    
                    logger.debug("Field processed: {} = {}", fullKey, valueStr);
                    
                    setDatadogTag(fullKey, valueStr);
                } catch (Exception e) {
                    logger.warn("Error processing field {}: {}", key, e.getMessage(), e);
                }
            });
            
            logger.info("Completed processing object of type: {}", clazz.getSimpleName());
            
        } catch (Exception e) {
            logger.error("Unexpected error processing object: {}", e.getMessage(), e);
        }
    }
    
    public static void processObject(Object obj) {
        processObject(obj, null);
    }
    
    private static void setDatadogTag(String key, String value) {
        try {
            Span span = GlobalTracer.get().activeSpan();
            if (span != null) {
                span.setTag(key, value);
                logger.debug("Tag sent to Datadog: {} = {}", key, value);
            } else {
                logger.trace("No active span found, skipping Datadog tag: {}", key);
            }
        } catch (Exception e) {
            logger.warn("Failed to send tag to Datadog ({}): {}", key, e.getMessage());
        }
    }
    
    private static Map<String, Object> extractFields(Object obj) {
        Map<String, Object> fieldMap = new LinkedHashMap<>();
        Class<?> clazz = obj.getClass();
        
        try {
            extractViaGetters(obj, clazz, fieldMap);
            
            if (fieldMap.isEmpty()) {
                extractViaFields(obj, clazz, fieldMap);
            }
        } catch (Exception e) {
            logger.error("Error extracting fields from object: {}", e.getMessage(), e);
        }
        
        return fieldMap;
    }
    
    private static void extractViaGetters(Object obj, Class<?> clazz, Map<String, Object> fieldMap) {
        for (Method method : clazz.getMethods()) {
            if (isGetter(method)) {
                try {
                    String fieldName = resolveFieldNameFromGetter(method.getName());
                    Object value = method.invoke(obj);
                    fieldMap.put(fieldName, value);
                } catch (Exception e) {
                    logger.trace("Failed to extract via getter {}: {}", method.getName(), e.getMessage());
                }
            }
        }
    }
    
    private static void extractViaFields(Object obj, Class<?> clazz, Map<String, Object> fieldMap) {
        for (Field field : getAllFields(clazz)) {
            try {
                field.setAccessible(true);
                fieldMap.put(field.getName(), field.get(obj));
            } catch (Exception e) {
                logger.trace("Failed to extract field {}: {}", field.getName(), e.getMessage());
            }
        }
    }
    
    private static boolean isGetter(Method method) {
        return method.getParameterCount() == 0 &&
               (method.getName().startsWith("get") || 
                (method.getName().startsWith("is") && 
                 method.getReturnType() == boolean.class));
    }
    
    private static String resolveFieldNameFromGetter(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            return Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        }
        return methodName;
    }
    
    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            try {
                fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            } catch (Exception e) {
                logger.warn("Failed to get fields from class {}: {}", clazz.getSimpleName(), e.getMessage());
            }
            clazz = clazz.getSuperclass();
        }
        return fields;
    }
    
    private static boolean isSimpleType(Class<?> clazz) {
        return clazz.isPrimitive() || 
               clazz == String.class || 
               clazz == Integer.class || 
               clazz == Long.class || 
               clazz == Double.class || 
               clazz == Float.class || 
               clazz == Boolean.class || 
               clazz == Character.class || 
               clazz == Byte.class || 
               clazz == Short.class ||
               clazz == Void.class;
    }
    
    private static String camelToSnake(String str) {
        if (str == null || str.isEmpty()) return str;
        
        StringBuilder result = new StringBuilder();
        result.append(Character.toLowerCase(str.charAt(0)));
        
        for (int i = 1; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c)) {
                result.append('_').append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        
        return result.toString();
    }
    
    public static void processVariable(String variableName, Object value, String prefix) {
        try {
            String snakeName = camelToSnake(variableName);
            String fullKey = (prefix != null && !prefix.isEmpty()) ? prefix + "." + snakeName : snakeName;
            String valueStr = (value != null) ? value.toString() : "null";
            
            logger.debug("Variable processed: {} = {}", fullKey, valueStr);
            
            setDatadogTag(fullKey, valueStr);
        } catch (Exception e) {
            logger.error("Error processing variable {}: {}", variableName, e.getMessage(), e);
        }
    }
    
    public static void processVariable(String variableName, Object value) {
        processVariable(variableName, value, null);
    }
}