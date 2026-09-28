package com.ke.nhservice.aimianshi.common.util;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jackson 3 的静态封装。
 * 用静态工具而不是到处注入 ObjectMapper，是因为 DAO / 引擎 / 解析器都要用，
 * 注入会把构造器参数搞得很长。
 *
 * 注意：这里是 Jackson 3（tools.jackson 包），不是 Jackson 2（com.fasterxml.jackson）。
 * 唯一的例外是注解，Jackson 3 仍复用 com.fasterxml.jackson.annotation 包。
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private JsonUtil() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + value.getClass().getName(), e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败，目标类型 " + type.getName()
                    + "，原文: " + abbreviate(json), e);
        }
    }

    /** 日志里打超长 JSON 会刷屏 */
    public static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...(" + text.length() + " 字符)";
    }
}