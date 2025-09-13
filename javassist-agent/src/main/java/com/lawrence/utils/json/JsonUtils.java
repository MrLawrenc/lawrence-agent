package com.lawrence.utils.json;

import com.eclipsesource.json.*;

import java.lang.reflect.*;
import java.util.*;

public class JsonUtils {

    // =============== 序列化 ===============
    public static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) return Json.value((String) obj).toString();
        //todo
        if (obj instanceof Number) return Json.value(((Number) obj).doubleValue()).toString();
        if (obj instanceof Boolean) return Json.value((Boolean) obj).toString();
        if (obj instanceof Map) return mapToJson((Map<?, ?>) obj).toString();
        if (obj instanceof Collection) return collectionToJson((Collection<?>) obj).toString();
        if (obj.getClass().isArray()) return arrayToJson(obj).toString();
        return beanToJson(obj).toString();
    }

    private static JsonObject beanToJson(Object bean) {
        JsonObject obj = new JsonObject();
        for (Field f : bean.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            try {
                Object v = f.get(bean);
                obj.add(f.getName(), toJsonValue(v));
            } catch (Exception ignored) {
            }
        }
        return obj;
    }

    private static JsonObject mapToJson(Map<?, ?> map) {
        JsonObject obj = new JsonObject();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            obj.add(String.valueOf(e.getKey()), toJsonValue(e.getValue()));
        }
        return obj;
    }

    private static JsonArray collectionToJson(Collection<?> coll) {
        JsonArray arr = new JsonArray();
        for (Object o : coll) arr.add(toJsonValue(o));
        return arr;
    }

    private static JsonArray arrayToJson(Object arr) {
        JsonArray jsonArray = new JsonArray();
        int len = Array.getLength(arr);
        for (int i = 0; i < len; i++) jsonArray.add(toJsonValue(Array.get(arr, i)));
        return jsonArray;
    }

    private static JsonValue toJsonValue(Object v) {
        if (v == null) return Json.NULL;
        if (v instanceof String) return Json.value((String) v);
        if (v instanceof Number) return Json.value(((Number) v).doubleValue());
        if (v instanceof Boolean) return Json.value((Boolean) v);
        if (v instanceof Map) return mapToJson((Map<?, ?>) v);
        if (v instanceof Collection) return collectionToJson((Collection<?>) v);
        if (v.getClass().isArray()) return arrayToJson(v);
        return beanToJson(v);
    }


    // =============== 反序列化 ===============
    public static <T> T fromJson(String json, Class<T> clazz) {
        JsonValue root = Json.parse(json);
        if (clazz == Map.class) {
            return clazz.cast(jsonToMap(root.asObject()));
        }
        return jsonToBean(root.asObject(), clazz);
    }

    private static Map<String, Object> jsonToMap(JsonObject obj) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String name : obj.names()) {
            map.put(name, jsonValueToJava(obj.get(name)));
        }
        return map;
    }

    private static Object jsonValueToJava(JsonValue v) {
        if (v.isNull()) return null;
        if (v.isString()) return v.asString();
        if (v.isNumber()) return v.asDouble();
        if (v.isBoolean()) return v.asBoolean();
        if (v.isObject()) return jsonToMap(v.asObject());
        if (v.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonValue e : v.asArray()) list.add(jsonValueToJava(e));
            return list;
        }
        return null;
    }

    private static <T> T jsonToBean(JsonObject obj, Class<T> clazz) {
        try {
            T bean = clazz.getDeclaredConstructor().newInstance();
            for (Field f : clazz.getDeclaredFields()) {
                f.setAccessible(true);
                JsonValue val = obj.get(f.getName());
                if (val == null || val.isNull()) continue;
                Object converted = convertValue(val, f.getType());
                if (converted != null) f.set(bean, converted);
            }
            return bean;
        } catch (Exception e) {
            throw new RuntimeException("json -> bean 失败", e);
        }
    }

    private static Object convertValue(JsonValue v, Class<?> type) {
        if (type == String.class) return v.asString();
        if (type == int.class || type == Integer.class) return (int) v.asDouble();
        if (type == long.class || type == Long.class) return (long) v.asDouble();
        if (type == double.class || type == Double.class) return v.asDouble();
        if (type == boolean.class || type == Boolean.class) return v.asBoolean();
        if (type == Map.class) return jsonToMap(v.asObject());
        if (type == List.class) return jsonValueToJava(v.asArray());
        if (v.isObject()) return jsonToBean(v.asObject(), type);
        return null;
    }
}
