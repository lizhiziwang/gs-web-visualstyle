/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.model;

/** 栅格符号化模式。 */
public enum RasterMode {

    /** 单波段灰度（带对比度拉伸）。 */
    GRAY("gray", "单波段灰度"),

    /** 多波段 RGB 合成。 */
    RGB("rgb", "多波段 RGB 合成"),

    /** 连续色带 Colormap。 */
    COLORMAP("colormap", "连续色带"),

    /** 离散分级色带（Ramp 的 interval 形式）。 */
    CLASSIFIED_RAMP("classified", "离散分级色带"),

    /** DEM 高程色阶。 */
    DEM("dem", "DEM 高程色阶");

    private final String id;
    private final String label;

    RasterMode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public boolean usesColorRamp() {
        return this == COLORMAP || this == CLASSIFIED_RAMP || this == DEM;
    }

    public static RasterMode fromId(String id) {
        if (id != null) {
            for (RasterMode m : values()) {
                if (m.id.equalsIgnoreCase(id) || m.name().equalsIgnoreCase(id)) {
                    return m;
                }
            }
        }
        return GRAY;
    }
}
