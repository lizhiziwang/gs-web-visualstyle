/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.model;

/** 符号化类型（决定中间配置面板与生成哪种 Symbolizer）。 */
public enum SymbolizerKind {

    /** 点符号 PointSymbolizer。 */
    POINT("point", "点符号", "PointSymbolizer"),

    /** 线符号 LineSymbolizer。 */
    LINE("line", "线符号", "LineSymbolizer"),

    /** 面符号 PolygonSymbolizer。 */
    POLYGON("polygon", "面符号", "PolygonSymbolizer"),

    /** 栅格符号 RasterSymbolizer。 */
    RASTER("raster", "栅格符号", "RasterSymbolizer");

    private final String id;
    private final String label;
    private final String sldName;

    SymbolizerKind(String id, String label, String sldName) {
        this.id = id;
        this.label = label;
        this.sldName = sldName;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    /** 对应的 SLD 元素名，用于源码高亮与提示。 */
    public String getSldName() {
        return sldName;
    }

    public boolean isVector() {
        return this != RASTER;
    }

    public static SymbolizerKind fromId(String id) {
        if (id != null) {
            for (SymbolizerKind k : values()) {
                if (k.id.equalsIgnoreCase(id) || k.name().equalsIgnoreCase(id)) {
                    return k;
                }
            }
        }
        return POLYGON;
    }
}
