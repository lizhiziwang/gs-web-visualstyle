/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.util;

import java.awt.Color;
import java.io.Serializable;
import java.util.Locale;

/** 颜色解析 / 归一化 / 插值工具。前端与后端统一使用 {@code #RRGGBB} 或 {@code #RRGGBBAA} 形式。 */
public final class Colors implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String DEFAULT_FILL = "#3D7EBB";
    public static final String DEFAULT_STROKE = "#1F4E79";
    public static final String DEFAULT_LABEL = "#222222";
    public static final String DEFAULT_HALO = "#FFFFFF";

    private Colors() {}

    /** 解析 CSS 颜色为 {@link Color}；支持 #rgb / #rrggbb / #rrggbbaa / 常见具名色 / rgb()。失败返回 null。 */
    public static Color parse(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        if (s.isEmpty()) {
            return null;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("#")) {
            String hex = lower.substring(1);
            try {
                if (hex.length() == 3) {
                    int r = Integer.parseInt(hex.substring(0, 1), 16) * 17;
                    int g = Integer.parseInt(hex.substring(1, 2), 16) * 17;
                    int b = Integer.parseInt(hex.substring(2, 3), 16) * 17;
                    return new Color(r, g, b);
                }
                if (hex.length() == 4) {
                    int r = Integer.parseInt(hex.substring(0, 1), 16) * 17;
                    int g = Integer.parseInt(hex.substring(1, 2), 16) * 17;
                    int b = Integer.parseInt(hex.substring(2, 3), 16) * 17;
                    int a = Integer.parseInt(hex.substring(3, 4), 16) * 17;
                    return new Color(r, g, b, a);
                }
                if (hex.length() == 6 || hex.length() == 8) {
                    int r = Integer.parseInt(hex.substring(0, 2), 16);
                    int g = Integer.parseInt(hex.substring(2, 4), 16);
                    int b = Integer.parseInt(hex.substring(4, 6), 16);
                    int a = hex.length() == 8 ? Integer.parseInt(hex.substring(6, 8), 16) : 255;
                    return new Color(r, g, b, a);
                }
            } catch (NumberFormatException nfe) {
                return null;
            }
            return null;
        }
        if (lower.startsWith("rgb")) {
            int open = lower.indexOf('(');
            int close = lower.lastIndexOf(')');
            if (open > 0 && close > open) {
                String[] parts = lower.substring(open + 1, close).split(",");
                try {
                    if (parts.length >= 3) {
                        int r = clamp255((int) Math.round(Double.parseDouble(parts[0].trim())));
                        int g = clamp255((int) Math.round(Double.parseDouble(parts[1].trim())));
                        int b = clamp255((int) Math.round(Double.parseDouble(parts[2].trim())));
                        int a = 255;
                        if (parts.length >= 4) {
                            double av = Double.parseDouble(parts[3].trim());
                            a = av <= 1.0d ? (int) Math.round(av * 255d) : clamp255((int) Math.round(av));
                        }
                        return new Color(r, g, b, a);
                    }
                } catch (NumberFormatException nfe) {
                    return null;
                }
            }
            return null;
        }
        switch (lower) {
            case "black":
                return Color.BLACK;
            case "white":
                return Color.WHITE;
            case "red":
                return Color.RED;
            case "green":
                return new Color(0, 128, 0);
            case "blue":
                return Color.BLUE;
            case "yellow":
                return Color.YELLOW;
            case "gray":
            case "grey":
                return Color.GRAY;
            case "orange":
                return Color.ORANGE;
            case "purple":
                return new Color(128, 0, 128);
            case "transparent":
                return new Color(0, 0, 0, 0);
            default:
                return null;
        }
    }

    /** 解析为 int ARGB，无法解析时返回 defaultArgb。 */
    public static int toArgb(String value, int defaultArgb) {
        Color c = parse(value);
        return c == null ? defaultArgb : c.getRGB();
    }

    /** 归一化为 {@code #RRGGBB}（丢弃 alpha）。 */
    public static String toHex(String value, String fallback) {
        Color c = parse(value);
        if (c == null) {
            c = parse(fallback);
        }
        if (c == null) {
            return "#000000";
        }
        return String.format("#%02x%02x%02x", Integer.valueOf(c.getRed()), Integer.valueOf(c.getGreen()),
                Integer.valueOf(c.getBlue()));
    }

    /** 归一化为 {@code #RRGGBBAA} 小写形式。 */
    public static String toHexAlpha(String value, String fallback, double alpha) {
        Color c = parse(value);
        if (c == null) {
            c = parse(fallback);
        }
        if (c == null) {
            c = Color.BLACK;
        }
        int a = (int) Math.round(Math.max(0d, Math.min(1d, alpha)) * 255d);
        return String.format("#%02x%02x%02x%02x", Integer.valueOf(c.getRed()), Integer.valueOf(c.getGreen()),
                Integer.valueOf(c.getBlue()), Integer.valueOf(a));
    }

    /** 十六进制颜色 + 独立透明度 → {@code #RRGGBBAA}。 */
    public static String withAlpha(String hex, double alpha) {
        String base = toHex(hex, "#000000");
        int a = (int) Math.round(Math.max(0d, Math.min(1d, alpha)) * 255d);
        return base + String.format("%02x", Integer.valueOf(a));
    }

    /** 线性插值两个颜色，t ∈ [0,1]。 */
    public static String interpolate(String fromHex, String toHex, double t) {
        Color a = parse(fromHex);
        Color b = parse(toHex);
        if (a == null) {
            a = Color.WHITE;
        }
        if (b == null) {
            b = Color.BLACK;
        }
        double k = Math.max(0d, Math.min(1d, t));
        int r = (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * k);
        int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * k);
        int bl = (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * k);
        int al = (int) Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * k);
        return String.format("#%02x%02x%02x%02x", Integer.valueOf(clamp255(r)), Integer.valueOf(clamp255(g)),
                Integer.valueOf(clamp255(bl)), Integer.valueOf(clamp255(al)));
    }

    /** 计算与给定颜色对比度较好的前景色（用于按钮上的文字）。 */
    public static String contrastingText(String hex) {
        Color c = parse(hex);
        if (c == null) {
            return "#000000";
        }
        double luminance = (0.299d * c.getRed() + 0.587d * c.getGreen() + 0.114d * c.getBlue()) / 255d;
        return luminance > 0.6d ? "#111111" : "#ffffff";
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
