package com.enjoyiot.framework.common.util.json.databind;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Long 毫秒时间戳 -> "yyyy-MM-dd HH:mm:ss" 序列化器
 *
 * @author EnjoyIot
 */
public class TimestampLongSerializer extends JsonSerializer<Long> {

    public static final TimestampLongSerializer INSTANCE = new TimestampLongSerializer();

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null || value <= 0) {
            gen.writeNull();
            return;
        }
        String formatted = FORMATTER.format(
                Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDateTime());
        gen.writeString(formatted);
    }
}
