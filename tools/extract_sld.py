#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 Wicket AJAX 响应中提取插件推回的 SLD 源码并落盘。

插件通过 AjaxRequestTarget.appendJavaScript 推送如下调用：
    window.VisualStyle && window.VisualStyle.onState({state:{...},sld:"<SLD>",errors:[...],...});

本脚本把 sld 字段还原成文本文件，并做一次 XML 良构性检查。

用法：
    python tools/extract_sld.py <响应文件> <输出 sld 路径>
退出码：0 成功；1 未找到或 XML 不良构。
"""

import io
import re
import sys
import xml.dom.minidom


def unescape_json_string(raw: str) -> str:
    out = []
    i = 0
    n = len(raw)
    while i < n:
        c = raw[i]
        if c == "\\" and i + 1 < n:
            nxt = raw[i + 1]
            mapping = {'"': '"', "\\": "\\", "/": "/", "n": "\n",
                       "r": "\r", "t": "\t", "b": "\b", "f": "\f"}
            if nxt in mapping:
                out.append(mapping[nxt])
                i += 2
                continue
            if nxt == "u" and i + 5 < n:
                try:
                    out.append(chr(int(raw[i + 2:i + 6], 16)))
                    i += 6
                    continue
                except ValueError:
                    pass
        out.append(c)
        i += 1
    return "".join(out)


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    resp, dest = sys.argv[1], sys.argv[2]

    s = io.open(resp, encoding="utf-8", errors="ignore").read()
    i = s.find('sld:"')
    if i < 0:
        print("未找到 sld 字段（插件可能未推送成功）")
        return 1
    # ⚠ 不能靠「后面的固定字段名」来定位结尾：push 载荷的字段顺序会变
    # （例如插件后来加了 previewSld），按 JSON 字符串规则扫到第一个未转义的引号才可靠。
    start = i + 5
    j = start
    n = len(s)
    while j < n:
        c = s[j]
        if c == "\\":
            j += 2
            continue
        if c == '"':
            break
        j += 1
    if j >= n:
        print("响应结构异常：sld 字符串未闭合")
        return 1

    sld = unescape_json_string(s[start:j])
    io.open(dest, "w", encoding="utf-8", newline="\n").write(sld)

    try:
        xml.dom.minidom.parseString(sld.encode("utf-8"))
        print("XML 良构性 OK，共 %d 字符 -> %s" % (len(sld), dest))
        return 0
    except Exception as e:
        print("XML 良构性失败: %s" % e)
        return 1


if __name__ == "__main__":
    sys.exit(main())
