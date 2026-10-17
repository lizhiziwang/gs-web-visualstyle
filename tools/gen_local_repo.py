#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
为 gs-web-visualstyle 生成"离线本地依赖仓库"。

背景：GeoServer / GeoTools 的构件不在 Maven Central（也不在阿里云公共镜像）中，
而 OSGeo 官方仓库只发布了部分模块（gs-web-* 缺失）。因此本脚本直接使用
**运行中的 GeoServer 3.0.1 容器内 WEB-INF/lib 的真实 jar** ，
按 Maven 本地仓库目录规范写入 ~/.m2/repository，并生成最小 POM。

这样可保证：编译期 API 与目标运行时字节级一致。

用法:
    python gen_local_repo.py [--jars DIR] [--repo DIR]
"""

import argparse
import os
import re
import shutil
import sys
from pathlib import Path

# (groupId, artifactId, version, jarFile)
ARTIFACTS = [
    # ---- GeoServer 3.0.1 ----
    ("org.geoserver", "gs-main", "3.0.1", "gs-main-3.0.1.jar"),
    ("org.geoserver", "gs-platform", "3.0.1", "gs-platform-3.0.1.jar"),
    ("org.geoserver", "gs-ows", "3.0.1", "gs-ows-3.0.1.jar"),
    ("org.geoserver", "gs-web-core", "3.0.1", "gs-web-core-3.0.1.jar"),
    ("org.geoserver", "gs-web-wms", "3.0.1", "gs-web-wms-3.0.1.jar"),
    ("org.geoserver", "gs-wms-core", "3.0.1", "gs-wms-core-3.0.1.jar"),
    ("org.geoserver", "gs-wms1_1", "3.0.1", "gs-wms1_1-3.0.1.jar"),
    ("org.geoserver", "gs-wms1_3", "3.0.1", "gs-wms1_3-3.0.1.jar"),
    ("org.geoserver", "gs-rest", "3.0.1", "gs-rest-3.0.1.jar"),
    ("org.geoserver", "gs-restconfig", "3.0.1", "gs-restconfig-3.0.1.jar"),
    ("org.geoserver", "gs-wfs-core", "3.0.1", "gs-wfs-core-3.0.1.jar"),
    ("org.geoserver", "gs-wcs", "3.0.1", "gs-wcs-3.0.1.jar"),
    ("org.geoserver", "gs-gwc", "3.0.1", "gs-gwc-3.0.1.jar"),
    ("org.geoserver", "gs-theme", "3.0.1", "gs-theme-3.0.1.jar"),
    ("org.geoserver", "gs-web-sec-core", "3.0.1", "gs-web-sec-core-3.0.1.jar"),
    # ---- GeoTools 35.1 ----
    ("org.geotools", "gt-main", "35.1", "gt-main-35.1.jar"),
    ("org.geotools", "gt-api", "35.1", "gt-api-35.1.jar"),
    ("org.geotools", "gt-render", "35.1", "gt-render-35.1.jar"),
    ("org.geotools", "gt-xml", "35.1", "gt-xml-35.1.jar"),
    ("org.geotools", "gt-xsd-sld", "35.1", "gt-xsd-sld-35.1.jar"),
    ("org.geotools", "gt-coverage", "35.1", "gt-coverage-35.1.jar"),
    ("org.geotools", "gt-referencing", "35.1", "gt-referencing-35.1.jar"),
    ("org.geotools", "gt-metadata", "35.1", "gt-metadata-35.1.jar"),
    ("org.geotools", "gt-cql", "35.1", "gt-cql-35.1.jar"),
    ("org.geotools", "gt-grid", "35.1", "gt-grid-35.1.jar"),
    ("org.geotools", "gt-geotiff", "35.1", "gt-geotiff-35.1.jar"),
    ("org.geotools", "gt-imagemosaic", "35.1", "gt-imagemosaic-35.1.jar"),
    ("org.geotools", "gt-process", "35.1", "gt-process-35.1.jar"),
    ("org.geotools", "gt-process-raster", "35.1", "gt-process-raster-35.1.jar"),
    ("org.geotools", "gt-shapefile", "35.1", "gt-shapefile-35.1.jar"),
    ("org.geotools", "gt-jdbc", "35.1", "gt-jdbc-35.1.jar"),
    ("org.geotools", "gt-http", "35.1", "gt-http-35.1.jar"),
    ("org.geotools", "gt-transform", "35.1", "gt-transform-35.1.jar"),
    ("org.geotools", "gt-brewer", "35.1", "gt-brewer-35.1.jar"),
    ("org.geotools", "gt-epsg-hsql", "35.1", "gt-epsg-hsql-35.1.jar"),
    # ---- Apache Wicket 10.10.0 ----
    ("org.danekja", "jdk-serializable-functional", "1.9.0", "jdk-serializable-functional-1.9.0.jar"),
    ("org.apache.wicket", "wicket-core", "10.10.0", "wicket-core-10.10.0.jar"),
    ("org.apache.wicket", "wicket-extensions", "10.10.0", "wicket-extensions-10.10.0.jar"),
    ("org.apache.wicket", "wicket-request", "10.10.0", "wicket-request-10.10.0.jar"),
    ("org.apache.wicket", "wicket-util", "10.10.0", "wicket-util-10.10.0.jar"),
    # ---- Spring Framework 7.0.8 / Spring Security 7.1.0 ----
    ("org.springframework", "spring-core", "7.0.8", "spring-core-7.0.8.jar"),
    ("org.springframework", "spring-beans", "7.0.8", "spring-beans-7.0.8.jar"),
    ("org.springframework", "spring-context", "7.0.8", "spring-context-7.0.8.jar"),
    ("org.springframework", "spring-context-support", "7.0.8", "spring-context-support-7.0.8.jar"),
    ("org.springframework", "spring-aop", "7.0.8", "spring-aop-7.0.8.jar"),
    ("org.springframework", "spring-expression", "7.0.8", "spring-expression-7.0.8.jar"),
    ("org.springframework", "spring-web", "7.0.8", "spring-web-7.0.8.jar"),
    ("org.springframework", "spring-webmvc", "7.0.8", "spring-webmvc-7.0.8.jar"),
    ("org.springframework", "spring-jdbc", "7.0.8", "spring-jdbc-7.0.8.jar"),
    ("org.springframework", "spring-tx", "7.0.8", "spring-tx-7.0.8.jar"),
    ("org.springframework.security", "spring-security-core", "7.1.0", "spring-security-core-7.1.0.jar"),
    ("org.springframework.security", "spring-security-web", "7.1.0", "spring-security-web-7.1.0.jar"),
    ("org.springframework.security", "spring-security-config", "7.1.0", "spring-security-config-7.1.0.jar"),
    ("org.springframework.security", "spring-security-access", "7.1.0", "spring-security-access-7.1.0.jar"),
    ("org.springframework.security", "spring-security-crypto", "7.1.0", "spring-security-crypto-7.1.0.jar"),
    # ---- 其他编译期依赖 ----
    ("org.locationtech.jts", "jts-core", "1.20.0", "jts-core-1.20.0.jar"),
    ("com.google.guava", "guava", "33.5.0-jre", "guava-33.5.0-jre.jar"),
    ("org.slf4j", "slf4j-api", "2.0.18", "slf4j-api-2.0.18.jar"),
    ("org.apache.commons", "commons-lang3", "3.18.0", "commons-lang3-3.18.0.jar"),
    ("commons-io", "commons-io", "2.21.0", "commons-io-2.21.0.jar"),
    ("commons-codec", "commons-codec", "1.22.1", "commons-codec-1.22.1.jar"),
    ("org.apache.commons", "commons-text", "1.15.0", "commons-text-1.15.0.jar"),
    ("org.freemarker", "freemarker", "2.3.34", "freemarker-2.3.34.jar"),
    ("jakarta.servlet", "jakarta.servlet-api", "6.0.0", "servlet-api.jar"),
    ("org.eclipse.imagen", "imagen-core", "0.9.2", "imagen-core-0.9.2.jar"),
    ("tech.units", "indriya", "2.2", "indriya-2.2.jar"),
    ("javax.measure", "unit-api", "2.2", "unit-api-2.2.jar"),
]

POM_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{group}</groupId>
  <artifactId>{artifact}</artifactId>
  <version>{version}</version>
  <packaging>jar</packaging>
  <description>Offline stub POM generated by gen_local_repo.py (real GeoServer 3.0.1 runtime jar).</description>
</project>
"""


def jar_fallback(jar_dir: Path, jar_name: str):
    """servlet-api.jar 在 tomcat/lib 下，文件名无版本号；做一次模糊匹配。"""
    p = jar_dir / jar_name
    if p.exists():
        return p
    prefix = jar_name.rsplit("-", 1)[0]
    for cand in jar_dir.glob(prefix + "*.jar"):
        return cand
    return None


def detect_local_repo():
    """
    解析 Maven 实际使用的本地仓库目录。

    很多环境在 conf/settings.xml 里把 <localRepository> 指向非默认位置
    （本机即为 D:\\zsh\\app\\maven\\apache-maven-3.9.11\\repository），
    直接写 ~/.m2/repository 会导致 Maven 完全看不到这些构件。
    """
    candidates = []
    mvn_home = os.environ.get("MAVEN_HOME") or os.environ.get("M2_HOME")
    if mvn_home:
        candidates.append(Path(mvn_home) / "conf" / "settings.xml")
    which = shutil.which("mvn")
    if which:
        # <maven>/bin/mvn -> <maven>/conf/settings.xml
        candidates.append(Path(which).resolve().parent.parent / "conf" / "settings.xml")
    candidates.append(Path.home() / ".m2" / "settings.xml")

    for settings in candidates:
        if not settings.is_file():
            continue
        try:
            text = settings.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        # 去掉 XML 注释，避免匹配到被注释掉的示例配置
        text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
        m = re.search(r"<localRepository>\s*([^<\s]+)\s*</localRepository>", text)
        if m:
            return Path(m.group(1).replace("\\", "/"))
    return Path.home() / ".m2" / "repository"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jars", default=None, help="存放 GS 容器 jar 的目录")
    ap.add_argument("--repo", default=None, help="目标本地仓库目录，默认自动探测")
    args = ap.parse_args()

    here = Path(__file__).resolve().parent.parent
    jar_dir = Path(args.jars) if args.jars else here / ".gs-jars"
    repo = Path(args.repo) if args.repo else detect_local_repo()

    if not jar_dir.is_dir():
        print("[ERR] jar 目录不存在: %s" % jar_dir, file=sys.stderr)
        return 2

    print("jar  目录 : %s" % jar_dir)
    print("本地仓库 : %s" % repo)
    print("-" * 78)

    ok, missing = 0, []
    for group, artifact, version, jar_name in ARTIFACTS:
        src = jar_fallback(jar_dir, jar_name)
        if src is None:
            missing.append(jar_name)
            continue
        dest_dir = repo.joinpath(*group.split("."), artifact, version)
        dest_dir.mkdir(parents=True, exist_ok=True)

        jar_name = "%s-%s.jar" % (artifact, version)
        pom_name = "%s-%s.pom" % (artifact, version)

        shutil.copy2(src, dest_dir / jar_name)
        (dest_dir / pom_name).write_text(
            POM_TEMPLATE.format(group=group, artifact=artifact, version=version),
            encoding="utf-8",
        )

        # 清理历史失败标记，否则 Maven 会认为该构件解析失败而不重试
        for stale in dest_dir.glob("*.lastUpdated"):
            stale.unlink()

        # Maven Resolver 的溯源文件。缺失时，EnhancedLocalRepositoryManager 会判定
        # 构件"不可用"（即使文件存在），导致离线构建报 Cannot access ... in offline mode。
        # 条目 "文件名>=" 中空仓库 id 表示"本地安装"，正是 install:install-file 的写法。
        (dest_dir / "_remote.repositories").write_text(
            "#NOTE: This is a Maven Resolver internal implementation file, "
            "its format can be changed without prior notice.\n"
            "%s>=\n%s>=\n" % (jar_name, pom_name),
            encoding="utf-8",
        )
        ok += 1

    print("已安装 %d 个构件。" % ok)
    if missing:
        print("[WARN] 下列 jar 未找到（可能不影响编译，但请检查）:")
        for m in missing:
            print("       - %s" % m)
    return 0


if __name__ == "__main__":
    sys.exit(main())
