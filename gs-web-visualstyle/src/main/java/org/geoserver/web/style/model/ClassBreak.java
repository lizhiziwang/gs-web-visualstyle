/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.model;

import java.io.Serializable;

/**
 * 一个分级/分类区间。
 *
 * <p>既用于「分级设色」（数值区间，{@code min}/{@code max} 有效），
 * 也用于「唯一值分类」（{@code value} 有效，区间字段被忽略）。
 */
public class ClassBreak implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 区间下界（含）。 */
    private double min;

    /** 区间上界（不含，最后一个区间为闭区间）。 */
    private double max;

    /** 唯一值分类时的字面值。 */
    private String value;

    /** 图例标签。 */
    private String label;

    /** 填充色 {@code #RRGGBB}。 */
    private String fill;

    /** 填充透明度 0..1。 */
    private double fillOpacity = 1d;

    /** 描边色。 */
    private String stroke;

    /** 描边宽度（线要素为线宽，点要素为轮廓宽度）。 */
    private double strokeWidth = 1d;

    /** 点符号大小。 */
    private double size = 8d;

    public ClassBreak() {}

    public ClassBreak(String label, double min, double max, String fill) {
        this.label = label;
        this.min = min;
        this.max = max;
        this.fill = fill;
    }

    public double getMin() {
        return min;
    }

    public void setMin(double min) {
        this.min = min;
    }

    public double getMax() {
        return max;
    }

    public void setMax(double max) {
        this.max = max;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getFill() {
        return fill;
    }

    public void setFill(String fill) {
        this.fill = fill;
    }

    public double getFillOpacity() {
        return fillOpacity;
    }

    public void setFillOpacity(double fillOpacity) {
        this.fillOpacity = Math.max(0d, Math.min(1d, fillOpacity));
    }

    public String getStroke() {
        return stroke;
    }

    public void setStroke(String stroke) {
        this.stroke = stroke;
    }

    public double getStrokeWidth() {
        return strokeWidth;
    }

    public void setStrokeWidth(double strokeWidth) {
        this.strokeWidth = strokeWidth;
    }

    public double getSize() {
        return size;
    }

    public void setSize(double size) {
        this.size = size;
    }

    /** 是否唯一值分类条目。 */
    public boolean isUniqueValue() {
        return value != null;
    }

    @Override
    public String toString() {
        if (isUniqueValue()) {
            return "ClassBreak[" + value + " -> " + fill + "]";
        }
        return "ClassBreak[" + min + ".." + max + " -> " + fill + "]";
    }
}
