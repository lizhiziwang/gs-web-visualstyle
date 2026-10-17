/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.util;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.geoserver.web.style.model.ColorStop;

/**
 * 内置色带库。
 *
 * <p>覆盖三类用途：
 * <ul>
 *   <li>分类设色用顺序/发散/感知均匀色带；</li>
 *   <li>栅格连续色带（Colormap）与离散分级色带；</li>
 *   <li>DEM 高程色阶（地形晕渲风格）。</li>
 * </ul>
 *
 * <p>所有色带以「等间距控制点」形式给出，渲染时按偏移量线性插值。
 */
public final class Ramps implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 色带定义。 */
    public static class Def implements Serializable {
        private static final long serialVersionUID = 1L;

        public final String id;
        public final String label;
        public final String group;
        /** 形如 "0:#440154,0.25:#3b528b,...,1:#fde725"。 */
        public final String stops;

        Def(String id, String label, String group, String stops) {
            this.id = id;
            this.label = label;
            this.group = group;
            this.stops = stops;
        }
    }

    private static final Map<String, Def> REGISTRY = new LinkedHashMap<>();
    private static final Map<String, String> GROUPS = new LinkedHashMap<>();

    private static void reg(String id, String label, String group, String... colors) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < colors.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            double offset = colors.length == 1 ? 0d : (double) i / (double) (colors.length - 1);
            sb.append(trim(offset)).append(':').append(colors[i]);
        }
        REGISTRY.put(id, new Def(id, label, group, sb.toString()));
        GROUPS.put(group, group);
    }

    static {
        // ---------------- 顺序色带（单色渐变，适合数量/密度） ----------------
        reg("blues", "蓝色 Blues", "sequential", "#f7fbff", "#deebf7", "#c6dbef", "#9ecae1", "#6baed6", "#4292c6",
                "#2171b5", "#08519c", "#08306b");
        reg("greens", "绿色 Greens", "sequential", "#f7fcf5", "#e5f5e0", "#c7e9c0", "#a1d99b", "#74c476", "#41ab5d",
                "#238b45", "#006d2c", "#00441b");
        reg("oranges", "橙色 Oranges", "sequential", "#fff5eb", "#fee6ce", "#fdd0a2", "#fdae6b", "#fd8d3c", "#f16913",
                "#d94801", "#a63603", "#7f2704");
        reg("reds", "红色 Reds", "sequential", "#fff5f0", "#fee0d2", "#fcbba1", "#fc9272", "#fb6a4a", "#ef3b2c",
                "#cb181d", "#a50f15", "#67000d");
        reg("purples", "紫色 Purples", "sequential", "#fcfbfd", "#efedf5", "#dadaeb", "#bcbddc", "#9e9ac8", "#807dba",
                "#6a51a3", "#54278f", "#3f007d");
        reg("greys", "灰度 Greys", "sequential", "#ffffff", "#f0f0f0", "#d9d9d9", "#bdbdbd", "#969696", "#737373",
                "#525252", "#252525", "#000000");
        reg("ylorrd", "黄-橙-红 YlOrRd", "sequential", "#ffffcc", "#ffeda0", "#fed976", "#feb24c", "#fd8d3c",
                "#fc4e2a", "#e31a1c", "#bd0026", "#800026");

        // ---------------- 发散色带（适合相对中值的偏离） ----------------
        reg("rdbu", "红-蓝 RdBu", "diverging", "#67001f", "#b2182b", "#d6604d", "#f4a582", "#fddbc7", "#f7f7f7",
                "#d1e5f0", "#92c5de", "#4393c3", "#2166ac", "#053061");
        reg("rdylbu", "红-黄-蓝 RdYlBu", "diverging", "#a50026", "#d73027", "#f46d43", "#fdae61", "#fee090",
                "#ffffbf", "#e0f3f8", "#abd9e9", "#74add1", "#4575b4", "#313695");
        reg("piyg", "粉-绿 PiYG", "diverging", "#8e0152", "#c51b7d", "#de77ae", "#f1b6da", "#fde0ef", "#f7f7f7",
                "#e6f5d0", "#b8e186", "#7fbc41", "#4d9221", "#276419");
        reg("spectral", "光谱 Spectral", "diverging", "#9e0142", "#d53e4f", "#f46d43", "#fdae61", "#fee08b",
                "#ffffbf", "#e6f598", "#abdda4", "#66c2a5", "#3288bd", "#5e4fa2");

        // ---------------- 感知均匀色带（推荐用于连续栅格） ----------------
        reg("viridis", "Viridis", "perceptual", "#440154", "#482878", "#3e4989", "#31688e", "#26828e", "#1f9e89",
                "#35b779", "#6ece58", "#b5de2b", "#fde725");
        reg("magma", "Magma", "perceptual", "#000004", "#180f3d", "#440f76", "#721f81", "#9e2f7f", "#cd4071",
                "#f1605d", "#fd9668", "#feca8d", "#fcfdbf");
        reg("plasma", "Plasma", "perceptual", "#0d0887", "#46039f", "#7201a8", "#9c179e", "#bd3786", "#d8576b",
                "#ed7953", "#fb9f3a", "#fdca26", "#f0f921");
        reg("turbo", "Turbo", "perceptual", "#30123b", "#4145ab", "#4675ed", "#39a2fc", "#1bcfd4", "#24eca6",
                "#61fc6c", "#a4fc3b", "#d1e834", "#f3c63a", "#fe9b2d", "#f36315", "#cb2a04", "#7a0403");
        reg("cividis", "Cividis", "perceptual", "#00204d", "#00336e", "#39486b", "#575d6d", "#707173", "#8a8779",
                "#a69d75", "#c4b56c", "#e4cf5b", "#ffea46");

        // ---------------- DEM 高程色阶 / 地形 ----------------
        reg("dem_hypso", "DEM 高程（分层设色）", "dem", "#1a3a6b", "#2e6fa7", "#4f9ac9", "#7ec8e3", "#b6e2c8",
                "#e8f0a4", "#f7d97a", "#e0a95a", "#b5793f", "#8a5a34", "#dcd7cf", "#ffffff");
        reg("dem_terrain", "地形 Terrain", "dem", "#333399", "#00a0c6", "#4dbe9b", "#8fd18a", "#c9dc85", "#f3e07a",
                "#e0b768", "#c08c52", "#9c7048", "#7d6044", "#b9b0a2", "#ffffff");
        reg("dem_gnuplot", "DEM Gnuplot", "dem", "#0000ff", "#00ffff", "#00ff00", "#ffff00", "#ff7f00", "#ff0000",
                "#bf3f3f", "#ffffff");
        reg("dem_elevation", "高程 Elevation", "dem", "#00a600", "#63c600", "#e6e600", "#e8bd00", "#e7b300",
                "#df6b00", "#e55700", "#d73300", "#b90000", "#960000", "#7d0000", "#600000", "#c9c9c9",
                "#ffffff");

        // ---------------- 单色 / 特殊 ----------------
        reg("grayscale", "灰阶", "special", "#000000", "#ffffff");
        reg("bwr", "蓝-白-红 BWR", "special", "#0000ff", "#ffffff", "#ff0000");
        reg("hsv", "HSV 环", "special", "#ff0000", "#ffff00", "#00ff00", "#00ffff", "#0000ff", "#ff00ff", "#ff0000");
        reg("categorical", "定性分类（12 色）", "special", "#e41a1c", "#377eb8", "#4daf4a", "#984ea3", "#ff7f00",
                "#ffff33", "#a65628", "#f781bf", "#999999", "#66c2a5", "#fc8d62", "#8da0cb");
        reg("categorical_pastel", "定性分类（柔和）", "special", "#fbb4ae", "#b3cde3", "#ccebc5", "#decbe4", "#fed9a6",
                "#ffffcc", "#e5d8bd", "#fddaec", "#f2f2f2", "#b3e2cd", "#fdcdac", "#cbd5e8");
    }

    private Ramps() {}

    public static List<Def> all() {
        return new ArrayList<>(REGISTRY.values());
    }

    public static Def get(String id) {
        Def d = REGISTRY.get(id);
        return d == null ? REGISTRY.get("viridis") : d;
    }

    public static boolean exists(String id) {
        return id != null && REGISTRY.containsKey(id);
    }

    public static List<String> groupNames() {
        return new ArrayList<>(GROUPS.keySet());
    }

    /** 解析 "0:#fff,1:#000" 形式的色带为 {@link ColorStop} 列表。 */
    public static List<ColorStop> parseStops(String stops) {
        List<ColorStop> out = new ArrayList<>();
        if (stops == null || stops.isBlank()) {
            return out;
        }
        for (String part : stops.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int idx = p.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            try {
                double offset = Double.parseDouble(p.substring(0, idx).trim());
                String color = p.substring(idx + 1).trim();
                out.add(new ColorStop(offset, color));
            } catch (NumberFormatException ignore) {
                // 跳过格式错误的控制点
            }
        }
        return out;
    }

    /** 把色带按 n 等分采样为 n 个离散颜色（分类设色用）。 */
    public static List<String> sample(Def def, int n) {
        List<String> colors = new ArrayList<>();
        if (def == null || n <= 0) {
            return colors;
        }
        return sample(parseStops(def.stops), n);
    }

    /** 在任意控制点列表上按 n 等分采样。 */
    public static List<String> sample(List<ColorStop> stops, int n) {
        List<String> colors = new ArrayList<>();
        if (n <= 0) {
            return colors;
        }
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0.5d : (double) i / (double) (n - 1);
            colors.add(sampleAt(stops, t));
        }
        return colors;
    }

    /** 由调用方给定的控制点构建临时色带定义（用于复用 sample(Def, int) 之外的场景）。 */
    public static Def customDef(String id, String label, List<ColorStop> stops) {
        StringBuilder sb = new StringBuilder();
        if (stops != null) {
            for (int i = 0; i < stops.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(trim(stops.get(i).getOffset())).append(':').append(stops.get(i).getNormalizedColor());
            }
        }
        return new Def(id, label, "custom", sb.toString());
    }

    /** 在给定控制点列表上按 t 采样（线性插值）。 */
    public static String sampleAt(List<ColorStop> stops, double t) {
        if (stops == null || stops.isEmpty()) {
            return "#808080";
        }
        if (stops.size() == 1) {
            return stops.get(0).getNormalizedColor();
        }
        List<ColorStop> sorted = new ArrayList<>(stops);
        sorted.sort((a, b) -> Double.compare(a.getOffset(), b.getOffset()));
        double k = Math.max(0d, Math.min(1d, t));
        if (k <= sorted.get(0).getOffset()) {
            return sorted.get(0).getNormalizedColor();
        }
        ColorStop last = sorted.get(sorted.size() - 1);
        if (k >= last.getOffset()) {
            return last.getNormalizedColor();
        }
        for (int i = 0; i < sorted.size() - 1; i++) {
            ColorStop a = sorted.get(i);
            ColorStop b = sorted.get(i + 1);
            if (k >= a.getOffset() && k <= b.getOffset()) {
                double span = b.getOffset() - a.getOffset();
                double local = span <= 0d ? 0d : (k - a.getOffset()) / span;
                return Colors.interpolate(a.getNormalizedColor(), b.getNormalizedColor(), local);
            }
        }
        return last.getNormalizedColor();
    }

    /** 由 CSS 渐变字符串（前端色带编辑器回传）生成 {@link ColorStop} 列表。 */
    public static List<ColorStop> fromCss(String css) {
        List<ColorStop> out = new ArrayList<>();
        if (css == null || css.isBlank()) {
            return out;
        }
        // 形如 "0% #ff0000,50% #00ff00,100% #0000ff"
        for (String part : css.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String[] bits = p.split("\\s+");
            if (bits.length < 2) {
                continue;
            }
            try {
                double offset = Double.parseDouble(bits[0].replace("%", "").trim()) / 100d;
                out.add(new ColorStop(offset, bits[1].trim()));
            } catch (NumberFormatException ignore) {
                // 忽略
            }
        }
        return out;
    }

    private static String trim(double d) {
        if (d == Math.rint(d)) {
            return Long.toString((long) d);
        }
        return String.format(java.util.Locale.ROOT, "%.4f", Double.valueOf(d));
    }
}
