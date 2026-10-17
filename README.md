# GeoServer 可视化样式编辑面板插件（gs-web-visualstyle）

面向 **GeoServer 3.0.1** 的原生 Web 扩展插件：在 GeoServer 自带管理控制台里嵌入一套  
「可视化样式编辑」面板，用表单 + 实时预览的方式编辑 **SLD 1.1.0 / GeoCSS** 样式。

- 插件类型：GeoServer UI 扩展（`gs-web-*` 模块风格），**单 jar 部署**，放进  
  `geoserver/WEB-INF/lib` 即加载，**不修改 GeoServer 核心源码**。
- 业务模型（SLD 对象模型）**完全由 Java 端 GeoTools 维护，前端只负责颜色选择器、**  
  色带拖拽、预览地图与源码高亮

---

## 0. 与需求基线不一致的三处事实修正（重要）

在实现前，我用运行中的 GeoServer 3.0.1 容器（`docker.osgeo.org/geoserver:3.0.1`）  
核对了 `WEB-INF/lib` 的实际依赖，有三点与需求描述不符，**代码按实测事实编写**：

| 项目     | 需求描述                             | **实测事实（GeoServer 3.0.1 官方镜像）**                                                                                            |
| ------ | -------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| Wicket | Wicket 9                         | **Wicket 10.10.0**（`wicket-core-10.10.0.jar`）；`wicketstuff-select2` 10.9.2                                                |
| Spring | Spring Boot 4                    | **Spring Framework 7.0.8 + Spring Security 7.1.0**（无 Spring Boot 依赖）                                                      |
| 页面路由   | `?wicket:bookmarkablePage=:FQCN` | **Wicket 10 已移除该查询参数映射器**；等价形式是 `/geoserver/web/wicket/bookmarkable/<FQCN>`。实测即使 GeoServer 自己的 `StylePage` 用旧写法也只会被重定向回首页 |
| 其它     | GeoTools 与 3.0.1 对齐              | GeoTools **35.1**、GeoWebCache 2.0.1、Jackson 3.2.0（`tools.jackson.*`，与 Jackson 2 包名不兼容）                                    |

因此本插件：

- 依赖版本**逐项锁定为容器内实测版本**（见根 `pom.xml` 的 `properties`）；
- 页面入口同时提供两种可访问 URL（见第 4 节），并在代码注释中标明原因；
- 不引入 Jackson，改用手写的极简 JSON 序列化（`util/Json.java`）。

---

## 1. 工程结构

```
geoserver-style-edit/
├── pom.xml                         聚合/父 POM（依赖版本统一定义在 dependencyManagement）
├── build.sh / build.bat            一键构建 / 部署脚本
├── tools/
│   ├── gen_local_repo.py           用容器内真实 jar 生成本地 Maven 仓库（离线可编译）
│   ├── gen_properties.py           从 HTML 模板自动生成 Wicket .properties（含 \uXXXX 转义）
│   ├── verify_page.sh              页面可达性 + 关键标记检查
│   ├── verify_ajax.sh              端到端功能验证（表单→SLD→渲染）
│   └── extract_sld.py              从 AJAX 响应中导出并校验生成的 SLD
└── gs-web-visualstyle/             ★ 插件模块（artifactId = gs-web-visualstyle）
    ├── pom.xml
    └── src/main/
        ├── java/org/geoserver/web/style/
        │   ├── VisualStyleEditorPage.java        主页面（继承 GeoServerSecuredPage）
        │   ├── VisualStyleEditorInitializer.java Wicket 应用初始化钩子（挂载短 URL + 能力自检）
        │   ├── EditorContext.java                会话上下文（可序列化状态 + 服务定位）
        │   ├── model/                            ── 可序列化的表单状态与契约对象
        │   │   ├── VisualStyleState.java         前后端唯一契约（含请求参数绑定）
        │   │   ├── SymbolizerKind / ClassificationMethod / RasterMode（枚举）
        │   │   ├── ClassBreak / ColorStop        分级条目、色带控制点
        │   │   └── StyleParseException           带行号的解析异常
        │   ├── service/                          ── 服务层（Spring 单例）
        │   │   ├── StyleCompiler.java            ★ 表单状态 ⇄ GeoTools Style 对象模型 双向编译
        │   │   ├── SldWriter.java                ★ SLD 1.1.0 XML 序列化器（遍历对象模型）
        │   │   ├── StyleSerializationService.java 解析/校验/往返自检（复用 StyleHandler）
        │   │   ├── ClassificationService.java    等间隔 / 分位数 / Jenks 自然断点 / 唯一值
        │   │   ├── LayerMetadataService.java     工作区·图层·字段·栅格直方图（走 Catalog）
        │   │   ├── StylePersistService.java      新建/复制/删除/保存/图层绑定
        │   │   └── StylePermissionService.java   复用原生权限体系
        │   ├── panel/                            ── 左侧导航 / 五个标签页 / 右侧预览
        │   │   ├── NavigationPanel.java          工作区·样式列表·新建复制删除·图层与元数据
        │   │   ├── VectorSymbolizerPanel.java    矢量符号化（单符号 / 唯一值 / 分级)
        │   │   ├── TextSymbolizerPanel.java      标注 TextSymbolizer
        │   │   ├── RasterSymbolizerPanel.java    栅格色带（灰度/RGB/色带/DEM）
        │   │   ├── ScaleControlPanel.java        比例尺控制 + 图例预览
        │   │   ├── SourceCodePanel.java          高级源码（原生 CodeMirrorEditor）
        │   │   └── PreviewPanel.java             右侧 OpenLayers 实时预览
        │   └── util/  Json.java · Colors.java · Ramps.java
        └── resources/
            ├── applicationContext.xml            插件 Spring 配置（jar 根目录，自动加载）
            ├── GeoServerApplication.properties   菜单标题等应用级词条（jar 根目录）
            └── org/geoserver/web/style/
                ├── VisualStyleEditorPage.html / .properties / css/ / js/
                └── panel/*.html / *.properties
```

---

## 2. 构建

### 2.1 环境要求

- JDK 17+（GeoServer 3.0.1 运行基线；实测容器为 Temurin 21）
- Maven 3.8+
- 一个可访问的 GeoServer 3.0.1 实例（用于取依赖快照 + 部署验证）

### 2.2 首次准备：生成本地依赖仓库（必做）

**GeoServer / GeoTools 的构件不在 Maven Central**（`repo1.maven.org` 与阿里云公共镜像  
均返回 404），OSGeo 官方仓库 `repo.osgeo.org` 又**没有发布 `gs-web-*` 模块**  
（`gs-web-core`、`gs-web-wms` 的 jar 与 pom 都是 404）。

因此本工程的做法是：**直接用目标 GeoServer 容器里的真实 jar 生成 Maven 本地仓库**。  
这同时带来一个好处 —— 编译期 API 与运行时字节级一致。

```bash
docker cp geoserver:/usr/local/tomcat/webapps/geoserver/WEB-INF/lib/. .gs-jars
python tools/gen_local_repo.py       # 自动探测 Maven 的 localRepository 并写入 66 个构件
```

> `gen_local_repo.py` 会解析 `conf/settings.xml` 中的 `<localRepository>`。  
> 本机 Maven 的本地仓库位于 `D:\zsh\app\maven\apache-maven-3.9.11\repository`  
> 而非默认的 `~/.m2/repository`，脚本已自动处理；同时会补写 Maven Resolver 的  
> `_remote.repositories` 溯源文件（缺失时离线模式会判定构件「不可用」）。

### 2.3 构建 / 部署

```bash
bash build.sh                      # 仅构建
bash build.sh --deploy --restart   # 构建 + 部署到容器 + 重启
```

Windows：`build.bat deploy-restart`

产物：`gs-web-visualstyle/target/gs-web-visualstyle-1.0.0.jar`（约 200 KB）

### 2.4 手动部署

```bash
cp gs-web-visualstyle/target/gs-web-visualstyle-1.0.0.jar \
   <geoserver>/WEB-INF/lib/
# 重启 GeoServer
```

加载确认（日志）：

```
INFO [web.style] - [gs-web-visualstyle] 可视化样式编辑面板已加载；可用样式格式: [sld, zip]
INFO [web.style] - [gs-web-visualstyle] 页面已挂载: /geoserver/web/visualstyle
```

---

## 3. 页面功能

### 左侧导航（`NavigationPanel`）

- 工作区下拉（含「全局」）；样式列表（当前工作区 SLD / GeoCSS 样式，点击切换）
- 新建 / 复制 / 删除样式（走 `Catalog`，与原生样式模块同一套 API）
- 绑定预览图层：自动读取要素**字段名 + 字段类型**（数值型标注），栅格图层额外读取  
  **波段数、值域、均值与第 1 波段直方图**

### 中间配置（5 个标签页）

| 标签页               | 能力                                                                                                                                                                                                    |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 矢量符号化             | 点/线/面单选；填充色·透明度·描边色·线宽·虚线·线端/拐角样式·描边偏移；点符号（内置标记/外部 SVG·PNG、大小、旋转、气泡图按字段驱动）；**唯一值分类**（枚举字段唯一值 + 定性色板循环配色）；**分级设色**（等间隔 / 分位数 / **自然断点 Jenks**，可手动微调断点、逐条改色）；颜色渐变选择器（内置 20+ 色带，顺序/发散/感知均匀/DEM/定性分组）+ 反转 |
| 标注 TextSymbolizer | 标注字段或 **CQL 表达式**（含字段插入器）、字体族/字号/粗斜体/颜色/透明度、**Halo 光晕**、偏移量、锚点、**标注避让**（`conflictResolution` + `spaceAround` VendorOption）、分组避让字段、标注独立比例尺开关                                                           |
| 栅格色带              | 灰度单波段 / 多波段 RGB 合成 / 连续色带 Colormap / 离散分级色带 / **DEM 高程色阶**；通道与波段选择；**直方图拉伸**（ContrastEnhancement：normalize / histogram / 对数 / 指数 + Gamma + 归一化策略）；**色带编辑器**（可拖拽控制点、双击加控制点、均匀分布、预设重置）；直方图可视化并自动对齐值域    |
| 比例尺控制             | `MinScaleDenominator` / `MaxScaleDenominator` 数值 + 常用比例尺快捷 chips + 可读提示；**图例实时预览**（单符号/唯一值/分级/栅格四种形态，SVG 绘制，可导出）                                                                                      |
| 高级源码              | 复用 GeoServer 原生 **CodeMirrorEditor**：SLD 模式 `text/sld10`（标签自动补全）、GeoCSS 模式 `text/geocss`；行号、查找替换、跳转行、格式化；**「源码 → 表单」**&#x89E3;析回填、**「表单 → 源码」**&#x91CD;新生成                                            |

### 右侧预览（`PreviewPanel`）

- 内嵌 OpenLayers，复用 GeoServer 自带的 `/geoserver/openlayers3/ol.js` + `ol.css`
- **真实所见即所得**：预览用 `SLD_BODY` 把**尚未保存**的样式交给 WMS 渲染；  
  由于 SLD 可能超出 GET 的 URL 长度限制，通过 OpenLayers 的 `imageLoadFunction`  
  把 GetMap 改写成 **POST**（`application/x-www-form-urlencoded`），彻底规避长度限制
- 缩放/平移、视图复位、底图开关、比例尺读数（并标注当前比例尺是否落在样式可见区间内）
- 底部图例实时渲染

### 底部源码视图

- SLD 1.1 / GeoCSS 切换；行号 + 语法高亮（XML / CSS 词法着色，零额外前端依赖）
- **错误行高亮**：服务端返回的解析错误（含 `SAXParseException` 的行号/列号）直接标在对应行上，  
  鼠标悬停显示消息；顶部显示「✓ 语法校验通过」或「✕ N 个错误」

---

## 4. 页面入口

| 方式               | URL                                                                                                  |
| ---------------- | ---------------------------------------------------------------------------------------------------- |
| 短路径（插件挂载）        | `http://<host>:8000/geoserver/web/visualstyle`                                                       |
| Wicket 原生可书签 URL | `http://<host>:8000/geoserver/web/wicket/bookmarkable/org.geoserver.web.style.VisualStyleEditorPage` |
| 左侧菜单             | 后台导航 **数据 → 可视化样式编辑**（`MenuPageInfo`，图标 `gs-icon-palette`，order 31）                                  |

> 需求中给出的 `?wicket:bookmarkablePage=:org.geoserver.web.style.VisualStyleEditorPage`  
> 形式在 Wicket 10 中已失效（查询参数映射器被移除），GeoServer 3.0.1 自身页面亦然。  
> 插件注册了 `MenuPageInfo`，因此菜单入口、面包屑、`filter=false` 上下文参数都按 GS3 的  
> 新导航体系正常工作。

---

## 5. 关键设计说明

### 5.1 状态同步机制（双向绑定）

```
表单字段(name="vs.*")
      │ ① JS 收集整个表单 → 追加到 Wicket AJAX 请求
      ▼
隐藏输入 vsPush 的 change 行为（AjaxFormComponentUpdatingBehavior）
      │ ② VisualStyleState.applyRequestParams(params)
      ▼
StyleCompiler.buildSld(state)  → GeoTools StyledLayerDescriptor（对象模型）
      │ ③ SldWriter.write(...)  → SLD 1.1.0 XML
      │ ④ verifyRoundTrip(...)  → XML 良构性 + GeoTools 解析回读自检
      ▼
AjaxRequestTarget.appendJavaScript("VisualStyle.onState({state, sld, errors})")
      │ ⑤ 前端刷新源码视图 / 图例 / OpenLayers 预览（含错误行标注）
      ▼
底部源码视图 + 右侧预览地图
```

- **表单→源码**：任一字段变化即触发；`sourceDirty=false` 时用表单覆盖源码。
- **源码→表单**：编辑 CodeMirror 后点「源码 → 表单」；`sourceDirty=true` 时服务端保留编辑器内容，  
  只把源码**解析回填**到表单状态。
- 每个面板都位于页面的 `<form wicket:id="visualForm">` 之内，**不嵌套 Form**  
  （HTML 与 Wicket 均不允许嵌套表单），数据类按钮统一用 `AjaxLink` + 动态附加参数回传整个表单。

### 5.2 为什么自己写 SLD 1.1 序列化器（`SldWriter`）

GeoServer 3.0.1 的 SLD 1.1 输出走 EMF 路线：

```java
Encoder e = new Encoder(new SLDConfiguration());
e.encode(sld, SLD.StyledLayerDescriptor, output);
```

`org.geotools.sld.v1_1.SLDConfiguration` 的绑定只认 EMF 生成的 `net.opengis.sld` 对象，  
而 `org.geotools.styling.StyleFactory` 产出的是 `org.geotools.styling.*Impl`。  
把后者交给该 Encoder 时绑定无法匹配，**会退化成写出 `StyledLayerDescriptorImpl@hash` 这样的  
toString 文本**（GeoServer 自身从不走这条路径 —— 它保存的是编辑器里的原始文本，  
所以这个缺陷不会被暴露）。

`SldWriter` 遍历 GeoTools 对象模型直接产出符合 OGC SLD 1.1.0 规范的 `se:` 命名空间 XML：

- 根元素使用**默认命名空间**形式（与 GeoServer 自带 SLD 1.1 示例一致，兼容性最好）；
- 覆盖 Polygon/Line/Point/Text/Raster 五种符号化器、Fill/Stroke/Graphic/Mark/ExternalGraphic、  
  Font/Halo/PointPlacement/LinePlacement、ChannelSelection/ContrastEnhancement/ColorMap、  
  TextSymbolizer 的 VendorOption；
- 过滤器覆盖 `PropertyIsEqualTo/NotEqualTo/LessThan/LessThanOrEqualTo/GreaterThan/
  GreaterThanOrEqualTo/Between/Like/Null` 与 `And/Or/Not`；遇到不支持的构造时**显式抛异常**  
  而不是静默产出错误样式。

### 5.3 往返自检（两道关卡）

`StyleSerializationService.verifyRoundTrip()` 在每次生成 SLD 后执行：

1. **XML 良构性**：JDK `DocumentBuilder`（namespace-aware，禁用 DTD/外部实体防 XXE），  
   错误能精确定位到行/列；
2. **SLD 语义**：把文本交回 GeoServer 的 `StyleHandler.parse`（底层即 GeoTools SLD 解析器，  
   1.1.0 走 XSD/EMF 解析分支）。

任一步失败都会把带行号的错误推送给前端并标红源码行。**这一设计在开发期立刻暴露了  
5.2 中描述的序列化缺陷**，是保证「生成的样式永远能被 GeoServer 读回」的关键。

### 5.4 权限

复用 GeoServer 原生权限体系与 `ComponentAuthorizer`，判定规则与原生样式模块一致：

| 场景           | 要求                                          |
| ------------ | ------------------------------------------- |
| 页面访问         | `ComponentAuthorizer.WORKSPACE_ADMIN`       |
| 全局样式（无工作区）读写 | `ROLE_ADMINISTRATOR`，其它角色只读并显示提示条           |
| 工作区样式读写      | 该用户是工作区管理员，**或**持有可选细粒度角色 `ROLE_STYLE_EDIT` |

细粒度角色通过 `StylePermissionService.granularRoleEnabled` 开关（默认开启，  
只有用户确实配置了该角色时才生效）。无权限时保存/删除按钮直接隐藏（CSS `.vs-readonly`），  
服务端各动作也再次校验。

### 5.5 与 GeoServer 的集成点

| 集成点                                  | 说明                                                                 |
| ------------------------------------ | ------------------------------------------------------------------ |
| `classpath*:/applicationContext.xml` | GeoServer 3.0.1 `web.xml` 的 `contextConfigLocation`，jar 根目录的配置自动加载 |


| `MenuPageInfo` | 注册后台菜单项（沿用 gs-web-core 的 `dataCategory` / `workspaceAdminAuthorizer` bean） |
| `ModuleStatusImpl` | 出现在「关于/状态」页，便于确认插件已加载 |
| `GeoServerApplicationInitializer` | Wicket 应用初始化钩子：挂载短 URL、启动时自检可用样式格式 |
| `StyleHandler` / `Styles.handler()` | 样式格式与 GeoServer 原生样式模块完全一致 |
| `Catalog` / `ResourcePool` | 工作区、图层、样式、字段、栅格统计、样式读写全部走 Catalog，**不自己连数据库** |
| `GeoServerSecuredPage` / `CodeMirrorEditor` / `AjaxTabbedPanel` 等 | 原生 Wicket 组件复用 |

---

## 6. 已知限制

1. **GeoCSS 需要安装扩展**：GeoServer 3.0.1 官方镜像未内置 CSS 扩展
   （容器内实测可用格式仅 `[sld, zip]`）。插件已做优雅降级 —— 底部源码视图切到
   GeoCSS 时提示，启动日志也会说明；安装 `gs-css-plugin` 后重启即可自动支持，
   `StyleSerializationService` 会动态识别。
2. **SLD 版本**：插件生成/保存默认 SLD **1.1.0**；读取时按源码实际版本自动识别（1.0.0 / 1.1.0 均可）。
3. **大数据量字段统计**：唯一值枚举与数值分级最多扫描 3 万条要素；直方图统计用 256×256
   降采样读取，避免拖垮后台 UI。
4. **标注避让**由 GeoServer 渲染引擎在地图渲染阶段计算，SLD 中表现为 VendorOption，
   表单改动后需依赖预览刷新观察效果。

---

## 7. 验证

### 7.1 页面可达性

```bash
printf 'machine localhost login admin password geoserver\n' > ~/.gs-test.netrc
chmod 600 ~/.gs-test.netrc
bash tools/verify_page.sh
```

### 7.2 核心数据流端到端

```bash
bash tools/verify_ajax.sh topp:states STATE_ABBR
```

实测结果（GeoServer 3.0.1 官方镜像）：

```
== A) 登录并打开页面 ==            页面 200
== B) 单一符号：表单 → SLD 1.1.0 ==
  OK StyledLayerDescriptor / 1.1.0 / se 命名空间 / se:PolygonSymbolizer
  OK ff5500 / fill-opacity / 003366 / stroke-dasharray
  OK se:TextSymbolizer / conflictResolution(VendorOption)
== C) 唯一值分类：服务端枚举 + 规则生成 ==
  OK uniqueValues / PropertyIsEqualTo / ogc:Filter / se:Rule   （按 STATE_ABBR 生成 49 条分类规则）
== D) 用生成的 SLD 渲染 WMS 地图 ==
  WMS 200 image/png      ← 插件生成的 SLD 被 GeoServer 渲染器接受并出图
```

> 注：GeoServer 3 启用了 Fetch Metadata 跨站隔离，非浏览器客户端需补齐
> `Sec-Fetch-*` / `Origin` / `Referer` 请求头，否则会被判定为跨站请求并重定向到登录页。
> 两个验证脚本已内置这些请求头。

---

## 8. 许可

插件代码遵循 GPL 2.0（与 GeoServer 一致）：
`(c) 2026 GeoServer Visual Style Editor Plugin - GPL 2.0`
