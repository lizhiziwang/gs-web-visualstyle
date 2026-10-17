/* (c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0 */
package org.geoserver.web.style.model;

/**
 * SLD / GeoCSS 解析或校验失败异常。
 *
 * <p>承载行号、列号等定位信息，便于「底部源码视图」把错误精确标注到行。
 */
public class StyleParseException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int line;
    private final int column;
    private final String format;

    public StyleParseException(String message) {
        this(message, -1, -1, null, null);
    }

    public StyleParseException(String message, int line, int column, String format, Throwable cause) {
        super(message, cause);
        this.line = line;
        this.column = column;
        this.format = format;
    }

    /** 1 基行号，-1 表示未知。 */
    public int getLine() {
        return line;
    }

    /** 1 基列号，-1 表示未知。 */
    public int getColumn() {
        return column;
    }

    /** 出错时使用的样式格式（sld / css）。 */
    public String getFormat() {
        return format;
    }

    public boolean hasPosition() {
        return line > 0;
    }

    /** 供前端展示的「line N: message」形式。 */
    public String getDisplayMessage() {
        String msg = getMessage() == null ? "未知错误" : getMessage();
        if (line > 0) {
            return "第 " + line + " 行" + (column > 0 ? " 第 " + column + " 列" : "") + ": " + msg;
        }
        return msg;
    }
}
