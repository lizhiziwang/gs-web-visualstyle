/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.util;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;

/**
 * 极简 JSON 序列化器。
 *
 * <p>插件刻意不引入 Jackson / Gson：GeoServer 3.0.1 内置的是 Jackson 3（{@code tools.jackson.*}），
 * 与 2.x 包名不兼容，直接依赖会带来版本耦合风险。本类仅需覆盖插件自身的数据传输场景
 * （把服务端计算出的状态/分级断点回推给前端 JS），手写即可，约 150 行。
 */
public final class Json implements Serializable {

    private static final long serialVersionUID = 1L;

    private Json() {}

    /** 把任意受支持的对象写成 JSON 文本。 */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Number) {
            Number n = (Number) value;
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (value instanceof Integer
                    || value instanceof Long
                    || value instanceof Short
                    || value instanceof Byte) {
                sb.append(n.longValue());
            } else {
                sb.append(trimDouble(d));
            }
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Map) {
            writeMap(sb, (Map<?, ?>) value);
        } else if (value instanceof Collection) {
            writeCollection(sb, (Collection<?>) value);
        } else if (value instanceof Object[]) {
            Object[] arr = (Object[]) value;
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(sb, arr[i]);
            }
            sb.append(']');
        } else if (value instanceof double[]) {
            double[] arr = (double[]) value;
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(sb, Double.valueOf(arr[i]));
            }
            sb.append(']');
        } else if (value instanceof int[]) {
            int[] arr = (int[]) value;
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(arr[i]);
            }
            sb.append(']');
        } else {
            // 其它类型（如枚举）按字符串处理
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeCollection(StringBuilder sb, Collection<?> coll) {
        sb.append('[');
        boolean first = true;
        for (Object o : coll) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, o);
        }
        sb.append(']');
    }

    private static String trimDouble(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1.0e15) {
            return Long.toString((long) d);
        }
        String s = Double.toString(d);
        if (s.indexOf('E') >= 0) {
            // 避免 JSON 中出现科学计数法（部分浏览器解析无问题，但保守处理后端一致性）
            java.math.BigDecimal bd = new java.math.BigDecimal(d);
            s = bd.stripTrailingZeros().toPlainString();
        }
        return s;
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        sb.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** 便于构造 JSON 对象字面量的小工具。 */
    public static String quoted(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        writeString(sb, raw);
        return sb.toString();
    }
}
