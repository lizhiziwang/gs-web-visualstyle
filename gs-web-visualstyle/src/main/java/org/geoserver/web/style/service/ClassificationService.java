/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.geoserver.web.style.model.ClassBreak;
import org.geoserver.web.style.model.ColorStop;
import org.geoserver.web.style.util.Ramps;

/**
 * 分级 / 分类算法服务。
 *
 * <p>实现三类数值分级（等间隔、分位数、自然断点 Jenks）与唯一值分类，
 * 并负责按色带为各分级批量配色。
 *
 * <p>Jenks 使用经典的 Fisher-Jenks 动态规划算法（O(n²k)），
 * 对输入的字段值先做去重与上限采样，避免大数据量下耗时过长。
 */
public class ClassificationService implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 参与计算的最大样本数（超出则等间隔抽样），保证交互响应速度。 */
    private static final int MAX_SAMPLES = 20000;

    /** 唯一值分类的最大类别数。 */
    public static final int MAX_UNIQUE_VALUES = 128;

    // ==================================================================
    // 断点计算
    // ==================================================================

    /** 等间隔分级断点（返回 k-1 个内断点）。 */
    public double[] equalIntervalBreaks(double[] values, int k) {
        double[] sorted = sanitize(values);
        if (sorted.length == 0 || k <= 1) {
            return new double[0];
        }
        double min = sorted[0];
        double max = sorted[sorted.length - 1];
        int count = Math.min(k, MAX_UNIQUE_VALUES);
        double[] breaks = new double[count - 1];
        double step = (max - min) / count;
        for (int i = 1; i < count; i++) {
            breaks[i - 1] = min + step * i;
        }
        return breaks;
    }

    /** 分位数分级断点（返回 k-1 个内断点）。 */
    public double[] quantileBreaks(double[] values, int k) {
        double[] sorted = sanitize(values);
        if (sorted.length == 0 || k <= 1) {
            return new double[0];
        }
        int count = Math.min(k, MAX_UNIQUE_VALUES);
        double[] breaks = new double[count - 1];
        for (int i = 1; i < count; i++) {
            double pos = (double) sorted.length * i / count;
            breaks[i - 1] = percentile(sorted, pos);
        }
        return dedupe(breaks);
    }

    /**
     * 自然断点（Jenks）分级断点。
     *
     * <p>Fisher-Jenks：在有序数组上用动态规划最小化「组内离差平方和」。
     * 复杂度 O(n²k)，因此 n 会被限制在 {@link #MAX_SAMPLES} 以内。
     */
    public double[] jenksBreaks(double[] values, int k) {
        double[] sorted = sanitize(values);
        int n = sorted.length;
        int count = Math.max(1, Math.min(k, MAX_UNIQUE_VALUES));
        if (n == 0 || count <= 1) {
            return new double[0];
        }
        if (n <= count) {
            // 样本比分级数还少：每个样本一个分级
            double[] breaks = new double[n - 1];
            for (int i = 0; i < n - 1; i++) {
                breaks[i] = sorted[i];
            }
            return breaks;
        }

        // lowerClassLimits[i][j] = 前 i+1 个元素分成 j+1 级时，第 j 级的起始下标
        int[][] lowerClassLimits = new int[n + 1][count + 1];
        // varianceCombinations[i][j] = 前 i+1 个元素分成 j+1 级的组内离差平方和
        double[][] varianceCombinations = new double[n + 1][count + 1];

        for (int i = 1; i <= count; i++) {
            lowerClassLimits[1][i] = 1;
            varianceCombinations[1][i] = 0d;
            for (int j = 2; j <= n; j++) {
                varianceCombinations[j][i] = Double.MAX_VALUE;
            }
        }

        for (int l = 2; l <= n; l++) {
            double sum = 0d;
            double sumSquares = 0d;
            int w = 0;
            double variance = 0d;

            for (int m = 1; m <= l; m++) {
                int lowerIndex = l - m + 1;
                double val = sorted[lowerIndex - 1];
                w++;
                sum += val;
                sumSquares += val * val;
                double mean = sum / w;
                variance = sumSquares - sum * mean;

                int i4 = lowerIndex - 1;
                if (i4 != 0) {
                    for (int j = 2; j <= count; j++) {
                        double candidate = variance + varianceCombinations[i4][j - 1];
                        if (candidate < varianceCombinations[l][j]
                                || Double.isInfinite(varianceCombinations[l][j])) {
                            lowerClassLimits[l][j] = lowerIndex;
                            varianceCombinations[l][j] = candidate;
                        }
                    }
                }
            }
            lowerClassLimits[l][1] = 1;
            varianceCombinations[l][1] = variance;
        }

        // 回溯断点
        double[] breaks = new double[count - 1];
        int kk = n;
        int remaining = count;
        while (remaining > 1) {
            int idx = lowerClassLimits[kk][remaining] - 2;
            if (idx < 0 || remaining - 2 >= breaks.length) {
                break;
            }
            breaks[remaining - 2] = idx >= 0 && idx < n ? sorted[idx] : sorted[0];
            kk = lowerClassLimits[kk][remaining] - 1;
            remaining--;
        }
        if (kk < 0) {
            kk = 0;
        }
        return dedupe(breaks);
    }

    /**
     * 按方法计算断点。
     *
     * @return k-1 个内断点
     */
    public double[] computeBreaks(double[] values, String methodId, int k) {
        if ("equal".equalsIgnoreCase(methodId)) {
            return equalIntervalBreaks(values, k);
        }
        if ("quantile".equalsIgnoreCase(methodId)) {
            return quantileBreaks(values, k);
        }
        if ("jenks".equalsIgnoreCase(methodId)) {
            return jenksBreaks(values, k);
        }
        return new double[0];
    }

    // ==================================================================
    // 生成分级条目
    // ==================================================================

    /**
     * 依据数值与分级方法生成 {@link ClassBreak} 列表（含配色）。
     *
     * @param values   字段的全部数值
     * @param methodId equal / quantile / jenks / manual
     * @param k        分级数
     * @param ramp     色带控制点
     * @param stroke   描边色
     */
    public List<ClassBreak> classify(double[] values, String methodId, int k,
            List<ColorStop> ramp, String stroke) {
        double[] sorted = sanitize(values);
        List<ClassBreak> out = new ArrayList<>();
        if (sorted.length == 0) {
            return out;
        }
        double min = sorted[0];
        double max = sorted[sorted.length - 1];

        double[] inner = "manual".equalsIgnoreCase(methodId)
                ? new double[0]
                : computeBreaks(sorted, methodId, k);

        List<Double> edges = new ArrayList<>();
        edges.add(Double.valueOf(min));
        for (double b : inner) {
            if (b > min && b < max) {
                edges.add(Double.valueOf(b));
            }
        }
        edges.add(Double.valueOf(max));
        // 保证最后一个区间非零宽
        int n = edges.size() - 1;
        if (n <= 0) {
            edges.clear();
            edges.add(Double.valueOf(min));
            edges.add(Double.valueOf(min == max ? min + 1d : max));
            n = 1;
        }

        List<ColorStop> stops = ramp == null || ramp.isEmpty()
                ? Ramps.parseStops(Ramps.get("viridis").stops)
                : ramp;
        List<String> colors = Ramps.sample(
                stops, n);

        for (int i = 0; i < n; i++) {
            double lo = edges.get(i).doubleValue();
            double hi = edges.get(i + 1).doubleValue();
            ClassBreak brk = new ClassBreak();
            brk.setMin(lo);
            brk.setMax(hi);
            brk.setLabel(formatLabel(lo, hi));
            brk.setFill(colors.get(i));
            brk.setFillOpacity(1d);
            brk.setStroke(stroke);
            brk.setStrokeWidth(1d);
            brk.setSize(8d);
            out.add(brk);
        }
        return out;
    }

    /** 有手动断点时按手动断点生成分级。 */
    public List<ClassBreak> classifyByEdges(List<Double> edges, List<ColorStop> ramp, String stroke) {
        List<ClassBreak> out = new ArrayList<>();
        if (edges == null || edges.size() < 2) {
            return out;
        }
        List<Double> sortedEdges = new ArrayList<>(edges);
        sortedEdges.sort(java.util.Comparator.naturalOrder());
        int n = sortedEdges.size() - 1;
        List<ColorStop> stops = ramp == null || ramp.isEmpty()
                ? Ramps.parseStops(Ramps.get("viridis").stops)
                : ramp;
        List<String> colors = Ramps.sample(
                stops, n);
        for (int i = 0; i < n; i++) {
            double lo = sortedEdges.get(i).doubleValue();
            double hi = sortedEdges.get(i + 1).doubleValue();
            ClassBreak brk = new ClassBreak();
            brk.setMin(lo);
            brk.setMax(hi);
            brk.setLabel(formatLabel(lo, hi));
            brk.setFill(colors.get(i));
            brk.setStroke(stroke);
            out.add(brk);
        }
        return out;
    }

    /**
     * 唯一值分类。
     *
     * @param values 字段的全部字面值
     */
    public List<ClassBreak> uniqueValues(List<String> values, List<ColorStop> ramp, String stroke) {
        List<ClassBreak> out = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            return out;
        }
        Set<String> distinct = new LinkedHashSet<>(values);
        List<String> list = new ArrayList<>(distinct);
        java.util.Collections.sort(list);
        int n = Math.min(list.size(), MAX_UNIQUE_VALUES);

        List<ColorStop> stops = ramp == null || ramp.isEmpty()
                ? Ramps.parseStops(Ramps.get("categorical").stops)
                : ramp;
        boolean categorical = ramp == null || ramp.isEmpty();
        List<String> colors;
        if (categorical) {
            // 定性分类：循环取 12 色色板，避免相邻类别颜色相近
            List<String> palette = Ramps.sample(Ramps.get("categorical"), 12);
            colors = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                colors.add(palette.get(i % palette.size()));
            }
        } else {
            colors = Ramps.sample(stops, n);
        }

        for (int i = 0; i < n; i++) {
            ClassBreak brk = new ClassBreak();
            brk.setValue(list.get(i));
            brk.setLabel(list.get(i));
            brk.setFill(colors.get(i));
            brk.setFillOpacity(1d);
            brk.setStroke(stroke);
            brk.setStrokeWidth(1d);
            brk.setSize(8d);
            out.add(brk);
        }
        return out;
    }

    // ==================================================================
    // 统计工具
    // ==================================================================

    /** 计算字段统计量：[count, min, max, mean, stddev, median]。 */
    public double[] statistics(double[] values) {
        double[] sorted = sanitize(values);
        if (sorted.length == 0) {
            return new double[] {0d, 0d, 0d, 0d, 0d, 0d};
        }
        double sum = 0d;
        for (double v : sorted) {
            sum += v;
        }
        double mean = sum / sorted.length;
        double sq = 0d;
        for (double v : sorted) {
            sq += (v - mean) * (v - mean);
        }
        double stddev = Math.sqrt(sq / sorted.length);
        return new double[] {sorted.length, sorted[0], sorted[sorted.length - 1], mean, stddev,
                percentile(sorted, sorted.length / 2d)};
    }

    /** 生成等宽直方图：[binCount, min, max, counts...] 的 JSON 友好结构。 */
    public int[] histogram(double[] values, double min, double max, int bins) {
        int[] counts = new int[Math.max(1, bins)];
        if (values == null || values.length == 0 || max <= min) {
            return counts;
        }
        double span = max - min;
        for (double v : values) {
            if (Double.isNaN(v)) {
                continue;
            }
            int idx = (int) Math.floor((v - min) / span * counts.length);
            if (idx < 0) {
                idx = 0;
            }
            if (idx >= counts.length) {
                idx = counts.length - 1;
            }
            counts[idx]++;
        }
        return counts;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 过滤 NaN / 无穷，排序，并按需抽样。 */
    private double[] sanitize(double[] values) {
        if (values == null || values.length == 0) {
            return new double[0];
        }
        List<Double> clean = new ArrayList<>(values.length);
        for (double v : values) {
            if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                clean.add(Double.valueOf(v));
            }
        }
        if (clean.isEmpty()) {
            return new double[0];
        }
        double[] arr = new double[clean.size()];
        for (int i = 0; i < clean.size(); i++) {
            arr[i] = clean.get(i).doubleValue();
        }
        Arrays.sort(arr);
        if (arr.length <= MAX_SAMPLES) {
            return arr;
        }
        // 等间隔抽样，保留首尾
        double[] sampled = new double[MAX_SAMPLES];
        double step = (double) (arr.length - 1) / (double) (MAX_SAMPLES - 1);
        for (int i = 0; i < MAX_SAMPLES; i++) {
            sampled[i] = arr[(int) Math.round(i * step)];
        }
        return sampled;
    }

    private double percentile(double[] sorted, double position) {
        if (sorted.length == 0) {
            return 0d;
        }
        if (position <= 0d) {
            return sorted[0];
        }
        if (position >= sorted.length) {
            return sorted[sorted.length - 1];
        }
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[Math.min(lower, sorted.length - 1)];
        }
        double fraction = position - lower;
        double lo = sorted[Math.min(lower, sorted.length - 1)];
        double hi = sorted[Math.min(upper, sorted.length - 1)];
        return lo + (hi - lo) * fraction;
    }

    /** 去掉相邻重复断点，并保持严格递增。 */
    private double[] dedupe(double[] breaks) {
        List<Double> out = new ArrayList<>();
        double prev = Double.NEGATIVE_INFINITY;
        for (double b : breaks) {
            if (Double.isNaN(b) || Double.isInfinite(b)) {
                continue;
            }
            if (b > prev) {
                out.add(Double.valueOf(b));
                prev = b;
            }
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < out.size(); i++) {
            arr[i] = out.get(i).doubleValue();
        }
        return arr;
    }

    private String formatLabel(double lo, double hi) {
        return String.format(java.util.Locale.ROOT, "%.3f ~ %.3f", Double.valueOf(lo), Double.valueOf(hi));
    }

    private String toStopsString(List<ColorStop> stops) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stops.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(stops.get(i).getOffset()).append(':').append(stops.get(i).getNormalizedColor());
        }
        return sb.toString();
    }
}
