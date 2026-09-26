package com.github.lisama569.versionedconfigreleasestorejava.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 统一 JSON 编解码配置。
 *
 * <p>关键兼容设置：
 * <ul>
 *   <li>注册 {@link JavaTimeModule}，{@code Instant} 稳定序列化为 ISO-8601 字符串；</li>
 *   <li>关闭日期写为时间戳，保证日志可读、跨版本一致；</li>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES=false}：<b>老数据 + 新代码</b>时，旧日志缺少新
 *       增加的字段不会报错（缺失的原始类型取默认 0），这是“补充新字段后兼容老数据”的
 *       核心保证；反过来新数据多出未知字段在读取侧同样宽容。</li>
 * </ul>
 */
public final class Json {

    private Json() {
    }

    /** 构造一个全新的、已注册时间模块的 mapper。 */
    public static ObjectMapper mapper() {
        return configure(new ObjectMapper());
    }

    /** 在调用方给定 mapper 上补齐必要模块与设置（幂等）。 */
    public static ObjectMapper configure(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
