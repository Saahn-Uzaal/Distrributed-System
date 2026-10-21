package com.ridehailing.identity.config;

import com.ridehailing.identity.domain.Role;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;

/**
 * T07-4 (F03) — ObjectMapper nghiêm ngặt (Jackson 3 / Boot 4).
 *
 * QUAN TRỌNG: ở Boot 4, MVC request binding dùng JsonMapper tự động được build từ
 * {@code JacksonAutoConfiguration} (JsonFactory + tất cả {@link JsonMapperBuilderCustomizer}).
 * Bean {@code @Bean ObjectMapper} riêng KHÔNG được MVC sử dụng (đã kiểm chứng: role=0 vẫn
 * trả 201 dù bean strict tồn tại) -> phải custom qua SPI này để mọi request binding nghiêm ngặt.
 * <ul>
 *   <li>int/long: chuỗi và số thập phân -> Fail ("10", 1.9 -> 400)</li>
 *   <li>double: chuỗi số -> Fail (tọa độ "10.7" -> 400)</li>
 *   <li>String: số nguyên/thập phân -> Fail (password/fullName dạng số -> 400)</li>
 *   <li>enum Role: số -> Fail (role=0/1 -> 400; Jackson 3 không có FAIL_ON_NUMBERS_FOR_ENUMS,
 *       dùng per-class coercion thay thế)</li>
 * </ul>
 * + FAIL_ON_NULL_FOR_PRIMITIVES (thiếu field nguyên thủy -> 400, không âm thầm thành 0)
 * + FAIL_ON_UNKNOWN_PROPERTIES.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer strictCoercionCustomizer() {
        return builder -> {
            failOn(builder, int.class, CoercionInputShape.String, CoercionInputShape.Float);
            failOn(builder, Integer.class, CoercionInputShape.String, CoercionInputShape.Float);
            failOn(builder, long.class, CoercionInputShape.String, CoercionInputShape.Float);
            failOn(builder, Long.class, CoercionInputShape.String, CoercionInputShape.Float);
            builder.withCoercionConfig(double.class, c -> c.setCoercion(CoercionInputShape.String, CoercionAction.Fail));
            builder.withCoercionConfig(Double.class, c -> c.setCoercion(CoercionInputShape.String, CoercionAction.Fail));
            failOn(builder, String.class, CoercionInputShape.Integer, CoercionInputShape.Float);
            failOn(builder, Role.class, CoercionInputShape.Integer, CoercionInputShape.Float);
            builder.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
            builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        };
    }

    private static void failOn(tools.jackson.databind.json.JsonMapper.Builder b, Class<?> type, CoercionInputShape... shapes) {
        for (CoercionInputShape shape : shapes) {
            b.withCoercionConfig(type, c -> c.setCoercion(shape, CoercionAction.Fail));
        }
    }
}
