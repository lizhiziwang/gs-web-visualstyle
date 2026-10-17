/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.model;

import java.io.Serializable;

import org.geoserver.web.style.util.Colors;

/** 色带控制点（栅格连续色带 / 离散分级色带的单个停靠点）。 */
public class ColorStop implements Serializable, Comparable<ColorStop> {

    private static final long serialVersionUID = 1L;

    /** 归一化位置，0..1。 */
    private double offset;

    /** 颜色，{@code #RRGGBB} 或 {@code #RRGGBBAA}。 */
    private String color;

    /** 可选标签，用于生成图例。 */
    private String label;

    public ColorStop() {
        this(0d, "#ffffff");
    }

    public ColorStop(double offset, String color) {
        this.offset = offset;
        this.color = color;
    }

    public double getOffset() {
        return offset;
    }

    public void setOffset(double offset) {
        this.offset = Math.max(0d, Math.min(1d, offset));
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    /** 归一化颜色的十六进制形式（保证非空且小写）。 */
    public String getNormalizedColor() {
        if (color == null || color.isBlank()) {
            return "#000000";
        }
        String c = color.trim().toLowerCase(java.util.Locale.ROOT);
        if (c.startsWith("#") && (c.length() == 7 || c.length() == 9 || c.length() == 4)) {
            return c;
        }
        String hex = Colors.toHex(c, "#000000");
        return hex;
    }

    /** 颜色部分（去掉 alpha）。 */
    public String getRgbHex() {
        String c = getNormalizedColor();
        return c.length() >= 7 ? c.substring(0, 7) : c;
    }

    /** alpha 分量，0..1。 */
    public double getAlpha() {
        String c = getNormalizedColor();
        if (c.length() == 9) {
            int a = Integer.parseInt(c.substring(7, 9), 16);
            return a / 255d;
        }
        return 1d;
    }

    @Override
    public int compareTo(ColorStop o) {
        return Double.compare(this.offset, o.offset);
    }

    @Override
    public String toString() {
        return offset + ":" + color;
    }
}
