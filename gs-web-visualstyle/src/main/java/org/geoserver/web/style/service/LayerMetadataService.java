/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.service;

import java.awt.image.Raster;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.web.style.util.Colors;
import org.geotools.api.data.FeatureSource;
import org.geotools.api.feature.Feature;
import org.geotools.feature.FeatureIterator;
import org.geotools.api.feature.type.FeatureType;
import org.geotools.api.feature.type.PropertyDescriptor;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.GeneralGridEnvelope;
import org.geotools.api.parameter.GeneralParameterValue;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.util.logging.Logging;

/**
 * 图层元数据读取服务。
 *
 * <p>数据来源全部走 GeoServer 自己的 Catalog / ResourcePool，不直连数据库：
 * <ul>
 *   <li>工作区、样式、图层列表：{@code Catalog}</li>
 *   <li>矢量字段名 / 类型：{@code FeatureTypeInfo.getAttributes()}</li>
 *   <li>字段唯一值、数值分布：{@code ResourcePool.getFeatureSource(...)} 顺序扫描（带上限）</li>
 *   <li>栅格波段数、值域、直方图：{@code GridCoverage2DReader} 降采样读取</li>
 * </ul>
 */
public class LayerMetadataService implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logging.getLogger(LayerMetadataService.class);

    /** 扫描要素数量的安全上限（防止大表拖垮后台 UI）。 */
    private static final int MAX_FEATURES_SCANNED = 30000;

    /** 栅格降采样读取的边长，用于统计直方图。 */
    private static final int RASTER_SAMPLE_SIZE = 256;

    // ==================================================================
    // DTO
    // ==================================================================

    /** 字段信息。 */
    public static class FieldInfo implements Serializable {
        private static final long serialVersionUID = 1L;

        public String name;
        public String type;
        public boolean numeric;
        /** 供下拉框显示：{@code name (type)}。 */
        public String label;

        public FieldInfo() {}

        public FieldInfo(String name, String type) {
            this.name = name;
            this.type = type;
            this.numeric = isNumericType(type);
            this.label = name + " (" + shortType(type) + ")";
        }

        private static boolean isNumericType(String t) {
            if (t == null) {
                return false;
            }
            String s = t.toLowerCase(java.util.Locale.ROOT);
            return s.contains("integer") || s.contains("double") || s.contains("float")
                    || s.contains("long") || s.contains("short") || s.contains("bigdecimal")
                    || s.contains("decimal") || s.contains("number");
        }

        private static String shortType(String t) {
            if (t == null) {
                return "unknown";
            }
            int idx = t.lastIndexOf('.');
            return idx >= 0 ? t.substring(idx + 1) : t;
        }
    }

    /** 栅格统计信息。 */
    public static class RasterStats implements Serializable {
        private static final long serialVersionUID = 1L;

        public int bandCount = 1;
        public List<String> bandNames = new ArrayList<>();
        public double min = 0d;
        public double max = 255d;
        public double mean = 0d;
        /** 直方图计数。 */
        public int[] histogram = new int[0];
        public double histogramMin;
        public double histogramMax;
        /** 栅格尺寸。 */
        public int width;
        public int height;
        public boolean available;
        public String message = "";
    }

    // ==================================================================
    // 目录浏览
    // ==================================================================

    /** 全部工作区（按名称排序）。 */
    public List<WorkspaceInfo> workspaces(Catalog catalog) {
        List<WorkspaceInfo> list = new ArrayList<>(catalog.getWorkspaces());
        list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()));
        return list;
    }

    /**
     * 某个工作区下的样式列表。
     *
     * @param workspace 工作区名；{@code null} / 空串表示仅全局样式
     */
    public List<StyleInfo> styles(Catalog catalog, String workspace) {
        List<StyleInfo> list;
        if (workspace == null || workspace.isBlank()) {
            list = new ArrayList<>();
            for (StyleInfo s : catalog.getStyles()) {
                if (s.getWorkspace() == null) {
                    list.add(s);
                }
            }
        } else {
            WorkspaceInfo ws = catalog.getWorkspaceByName(workspace);
            list = ws == null ? new ArrayList<>() : new ArrayList<>(catalog.getStylesByWorkspace(ws));
        }
        list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()));
        return list;
    }

    /** 某个工作区下的图层（含全局图层回退）。 */
    public List<LayerInfo> layers(Catalog catalog, String workspace) {
        List<LayerInfo> list = new ArrayList<>();
        if (workspace == null || workspace.isBlank()) {
            list.addAll(catalog.getLayers());
        } else {
            WorkspaceInfo ws = catalog.getWorkspaceByName(workspace);
            if (ws != null) {
                for (LayerInfo l : catalog.getLayers()) {
                    ResourceInfo r = l.getResource();
                    if (r == null) {
                        continue;
                    }
                    if (r.getStore() != null && ws.equals(r.getStore().getWorkspace())) {
                        list.add(l);
                    }
                }
            }
            if (list.isEmpty()) {
                list.addAll(catalog.getLayers());
            }
        }
        list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.prefixedName(), b.prefixedName()));
        return list;
    }

    /** 图层名 → LayerInfo。 */
    public LayerInfo layer(Catalog catalog, String prefixedName) {
        if (prefixedName == null || prefixedName.isBlank()) {
            return null;
        }
        if (prefixedName.indexOf(':') > 0) {
            return catalog.getLayerByName(prefixedName);
        }
        return catalog.getLayerByName(prefixedName);
    }

    /** 样式是否可绑定到该图层（矢量 ↔ 矢量，栅格 ↔ 栅格）。 */
    public boolean isCompatible(LayerInfo layer, boolean rasterStyle) {
        if (layer == null || layer.getResource() == null) {
            return false;
        }
        boolean isCoverage = layer.getResource() instanceof CoverageInfo;
        return rasterStyle == isCoverage;
    }

    // ==================================================================
    // 矢量字段
    // ==================================================================

    /** 读取矢量字段列表；非矢量图层返回空列表。 */
    public List<FieldInfo> fields(LayerInfo layer) {
        List<FieldInfo> out = new ArrayList<>();
        if (layer == null || !(layer.getResource() instanceof FeatureTypeInfo)) {
            return out;
        }
        FeatureTypeInfo fti = (FeatureTypeInfo) layer.getResource();
        try {
            for (org.geoserver.catalog.AttributeTypeInfo at : fti.attributes()) {
                Class<?> binding = at.getBinding();
                out.add(new FieldInfo(at.getName(), binding == null ? "unknown" : binding.getName()));
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "读取 FeatureType 属性失败: " + layer.prefixedName(), e);
        }
        return out;
    }

    /** 通过要素类型读取字段（Catalog 属性为空时的兜底）。 */
    public List<FieldInfo> fieldsFromSchema(LayerInfo layer, Catalog catalog) {
        List<FieldInfo> out = new ArrayList<>();
        if (layer == null || !(layer.getResource() instanceof FeatureTypeInfo)) {
            return out;
        }
        try {
            FeatureSource<? extends FeatureType, ? extends Feature> fs =
                    catalog.getResourcePool().getFeatureSource((FeatureTypeInfo) layer.getResource(), null);
            if (fs == null || fs.getSchema() == null) {
                return out;
            }
            for (PropertyDescriptor pd : fs.getSchema().getDescriptors()) {
                Class<?> binding = pd.getType() == null ? null : pd.getType().getBinding();
                out.add(new FieldInfo(pd.getName().toString(),
                        binding == null ? "unknown" : binding.getName()));
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "兜底读取字段失败: " + layer.prefixedName(), e);
        }
        return out;
    }

    // ==================================================================
    // 字段取值
    // ==================================================================

    /** 读取字段的唯一值（用于唯一值分类）。 */
    public List<String> uniqueValues(LayerInfo layer, Catalog catalog, String field, int limit) {
        Set<String> values = new LinkedHashSet<>();
        if (layer == null || field == null || field.isBlank()) {
            return new ArrayList<>();
        }
        try {
            FeatureSource<? extends FeatureType, ? extends Feature> fs =
                    catalog.getResourcePool().getFeatureSource((FeatureTypeInfo) layer.getResource(), null);
            if (fs == null) {
                return new ArrayList<>();
            }
            int scanned = 0;
            try (FeatureIterator<? extends Feature> it =
                    fs.getFeatures().features()) {
                while (it.hasNext() && scanned < MAX_FEATURES_SCANNED) {
                    scanned++;
                    Feature f = it.next();
                    Object v = f.getProperty(field) == null ? null : f.getProperty(field).getValue();
                    String s = v == null ? "" : String.valueOf(v);
                    values.add(s);
                    if (values.size() > Math.max(1, limit)) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "枚举字段唯一值失败: " + field, e);
        }
        List<String> out = new ArrayList<>(values);
        java.util.Collections.sort(out);
        return out;
    }

    /** 读取字段的数值序列（用于分级统计）。 */
    public double[] numericValues(LayerInfo layer, Catalog catalog, String field) {
        List<Double> values = new ArrayList<>();
        if (layer == null || field == null || field.isBlank()) {
            return new double[0];
        }
        try {
            FeatureSource<? extends FeatureType, ? extends Feature> fs =
                    catalog.getResourcePool().getFeatureSource((FeatureTypeInfo) layer.getResource(), null);
            if (fs == null) {
                return new double[0];
            }
            int scanned = 0;
            try (FeatureIterator<? extends Feature> it =
                    fs.getFeatures().features()) {
                while (it.hasNext() && scanned < MAX_FEATURES_SCANNED) {
                    scanned++;
                    Feature f = it.next();
                    Object v = f.getProperty(field) == null ? null : f.getProperty(field).getValue();
                    if (v instanceof Number) {
                        values.add(Double.valueOf(((Number) v).doubleValue()));
                    } else if (v != null) {
                        try {
                            values.add(Double.valueOf(Double.parseDouble(String.valueOf(v).trim())));
                        } catch (NumberFormatException ignore) {
                            // 非数值跳过
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "读取字段数值失败: " + field, e);
        }
        double[] arr = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            arr[i] = values.get(i).doubleValue();
        }
        return arr;
    }

    // ==================================================================
    // 栅格统计
    // ==================================================================

    /**
     * 读取栅格统计信息（波段数、值域、直方图）。
     *
     * <p>为避免读取整幅大影像，这里用 {@code READ_GRIDGEOMETRY2D} 参数把读取范围
     * 限制在 {@link #RASTER_SAMPLE_SIZE}×{@link #RASTER_SAMPLE_SIZE} 的降采样网格上。
     */
    public RasterStats rasterStats(LayerInfo layer, Catalog catalog) {
        RasterStats stats = new RasterStats();
        if (layer == null || !(layer.getResource() instanceof CoverageInfo)) {
            stats.message = "当前图层不是 Coverage 类型。";
            return stats;
        }
        CoverageInfo ci = (CoverageInfo) layer.getResource();
        GridCoverage2D coverage = null;
        try {
            GridCoverage2DReader reader = (GridCoverage2DReader) catalog.getResourcePool()
                    .getGridCoverageReader(ci, null);
            if (reader == null) {
                stats.message = "无法打开栅格读取器。";
                return stats;
            }
            GeneralParameterValue[] params = downsampleParams(reader);
            coverage = reader.read(params);
            if (coverage == null) {
                coverage = (GridCoverage2D) ci.getGridCoverage(null, null);
            }
            if (coverage == null) {
                stats.message = "读取栅格数据失败。";
                return stats;
            }
            analyze(coverage, stats);
            stats.available = true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "读取栅格统计失败: " + layer.prefixedName(), e);
            stats.message = "读取栅格统计失败: " + e.getMessage();
        } finally {
            if (coverage != null) {
                try {
                    coverage.dispose(true);
                } catch (Exception ignore) {
                    // 释放失败不影响结果
                }
            }
        }
        return stats;
    }

    private GeneralParameterValue[] downsampleParams(GridCoverage2DReader reader) {
        try {
            org.geotools.api.geometry.Bounds env = reader.getOriginalEnvelope();
            org.geotools.api.parameter.ParameterValue<GridGeometry2D> readGeometry =
                    AbstractGridFormat.READ_GRIDGEOMETRY2D.createValue();
            readGeometry.setValue(new GridGeometry2D(
                    new GeneralGridEnvelope(new int[] {0, 0},
                            new int[] {RASTER_SAMPLE_SIZE - 1, RASTER_SAMPLE_SIZE - 1}),
                    env));
            return new GeneralParameterValue[] {readGeometry};
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "构建降采样读取参数失败，回退为整幅读取", e);
            return new GeneralParameterValue[0];
        }
    }

    private void analyze(GridCoverage2D coverage, RasterStats stats) {
        java.awt.Rectangle gridEnv = coverage.getGridGeometry().getGridRange2D();
        stats.width = gridEnv.width;
        stats.height = gridEnv.height;

        int bands = coverage.getNumSampleDimensions();
        stats.bandCount = Math.max(1, bands);
        for (int b = 0; b < stats.bandCount; b++) {
            org.geotools.api.coverage.SampleDimension sd = coverage.getSampleDimension(b);
            String name = sd == null || sd.getDescription() == null ? null : sd.getDescription().toString();
            stats.bandNames.add(name == null || name.isBlank() ? ("Band" + (b + 1)) : name);
        }

        // 只统计第 1 波段（灰度/色带/DEM 都是单波段流程）
        Object data = coverage.getRenderedImage().getData();
        if (!(data instanceof Raster)) {
            stats.histogram = new int[0];
            return;
        }
        Raster raster = (Raster) data;
        int w = raster.getWidth();
        int h = raster.getHeight();
        int bins = 64;
        int[] hist = new int[bins];

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double sum = 0d;
        long n = 0L;

        // 进一步抽样，控制循环次数
        int stepX = Math.max(1, w / RASTER_SAMPLE_SIZE);
        int stepY = Math.max(1, h / RASTER_SAMPLE_SIZE);

        // 先求值域
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                double v = raster.getSampleDouble(x, y, 0);
                if (Double.isNaN(v)) {
                    continue;
                }
                min = Math.min(min, v);
                max = Math.max(max, v);
                sum += v;
                n++;
            }
        }
        if (n == 0 || max <= min) {
            stats.min = 0d;
            stats.max = 255d;
            stats.histogramMin = 0d;
            stats.histogramMax = 255d;
            stats.histogram = hist;
            return;
        }
        stats.min = min;
        stats.max = max;
        stats.mean = sum / n;
        stats.histogramMin = min;
        stats.histogramMax = max;

        double span = max - min;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                double v = raster.getSampleDouble(x, y, 0);
                if (Double.isNaN(v)) {
                    continue;
                }
                int idx = (int) ((v - min) / span * bins);
                if (idx < 0) {
                    idx = 0;
                }
                if (idx >= bins) {
                    idx = bins - 1;
                }
                hist[idx]++;
            }
        }
        stats.histogram = hist;
    }

    // ==================================================================
    // 预览所需信息
    // ==================================================================

    /** 预览图层包围盒（经纬度），供 OpenLayers 初始化视图。 */
    public double[] previewBounds(LayerInfo layer) {
        if (layer == null || layer.getResource() == null) {
            return new double[] {-180d, -90d, 180d, 90d};
        }
        ReferencedEnvelope env = layer.getResource().getLatLonBoundingBox();
        if (env == null || env.isNull()) {
            return new double[] {-180d, -90d, 180d, 90d};
        }
        return new double[] {env.getMinX(), env.getMinY(), env.getMaxX(), env.getMaxY()};
    }

    /** 预览所需的比例尺参考分辨率。 */
    public double previewResolution(LayerInfo layer) {
        double[] b = previewBounds(layer);
        double span = Math.max(Math.abs(b[2] - b[0]), Math.abs(b[3] - b[1]));
        return span <= 0d ? 1d : span / 256d;
    }

    /** 该图层可用的样式列表（已绑定样式，用于快速切换）。 */
    public List<StyleInfo> layerStyles(Catalog catalog, LayerInfo layer) {
        List<StyleInfo> out = new ArrayList<>();
        if (layer == null) {
            return out;
        }
        if (layer.getDefaultStyle() != null) {
            out.add(layer.getDefaultStyle());
        }
        for (StyleInfo s : layer.getStyles()) {
            boolean dup = false;
            for (StyleInfo e : out) {
                if (e.getId() != null && e.getId().equals(s.getId())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out.add(s);
            }
        }
        return out;
    }

    /** 建议的符号化类型（依据图层类型）。 */
    public String suggestSymbolizerKind(LayerInfo layer) {
        if (layer == null || layer.getResource() == null) {
            return "polygon";
        }
        if (layer.getResource() instanceof CoverageInfo) {
            return "raster";
        }
        try {
            FeatureTypeInfo fti = (FeatureTypeInfo) layer.getResource();
            // 通过几何字段判断最准确，但需要打开数据源；这里用轻量启发式（名称特征）
            String name = fti.getName() == null ? "" : fti.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("point") || name.contains("station")) {
                return "point";
            }
            if (name.contains("route") || name.contains("road") || name.contains("stream")
                    || name.contains("line") || name.contains("river")) {
                return "line";
            }
        } catch (Exception ignore) {
            // 忽略，使用默认值
        }
        return "polygon";
    }

    /** 从默认色带为新建样式给出一个初始填充色。 */
    public String suggestedFill(String symbolizerKind) {
        if ("raster".equals(symbolizerKind)) {
            return "#808080";
        }
        return Colors.DEFAULT_FILL;
    }
}
