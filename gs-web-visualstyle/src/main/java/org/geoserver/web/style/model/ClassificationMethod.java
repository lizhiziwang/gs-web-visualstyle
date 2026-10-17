/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.model;

/** 分类/分级方式。 */
public enum ClassificationMethod {

    /** 单一符号，不分类。 */
    SINGLE("single", "单一符号"),

    /** 唯一值分类 UniqueValues。 */
    UNIQUE_VALUES("unique", "唯一值分类"),

    /** 等间隔分级。 */
    EQUAL_INTERVAL("equal", "等间隔分级"),

    /** 分位数分级。 */
    QUANTILE("quantile", "分位数分级"),

    /** 自然断点（Jenks）分级。 */
    JENKS("jenks", "自然断点 Jenks"),

    /** 手动指定断点。 */
    MANUAL("manual", "手动断点");

    private final String id;
    private final String label;

    ClassificationMethod(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public boolean isNumericBreakMethod() {
        return this == EQUAL_INTERVAL || this == QUANTILE || this == JENKS || this == MANUAL;
    }

    public static ClassificationMethod fromId(String id) {
        if (id != null) {
            for (ClassificationMethod m : values()) {
                if (m.id.equalsIgnoreCase(id) || m.name().equalsIgnoreCase(id)) {
                    return m;
                }
            }
        }
        return SINGLE;
    }
}
