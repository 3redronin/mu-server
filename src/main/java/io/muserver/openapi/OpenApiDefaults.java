package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

/** Conservative omission rules shared by manually built and generated documents. */
final class OpenApiDefaults {
    private OpenApiDefaults() { }

    static @Nullable Object parameter(String keyword, @Nullable Object value, String in, @Nullable String style) {
        if (value == null) return null;
        String effectiveStyle = style == null ? ParameterObject.defaultStyle(in) : style;
        if ("style".equals(keyword) && value.equals(ParameterObject.defaultStyle(in))) return null;
        if ("explode".equals(keyword) && value.equals("form".equals(effectiveStyle) || "cookie".equals(effectiveStyle))) return null;
        if (("deprecated".equals(keyword) || "allowReserved".equals(keyword) || "allowEmptyValue".equals(keyword)) && Boolean.FALSE.equals(value)) return null;
        return value;
    }
}
