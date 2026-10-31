# 代码评审 4：JourneyMap 集成（客户端 JourneyMap 插件 + PolygonOverlay）

评审日期：2026-10-31
评审对象：本轮新增的 JourneyMap 接入（客户端）
- `client/src/main/java/com/tianyaworld/pmxxlclaims/client/jm/ClaimsJourneyMapPlugin.java`
- `client/src/main/java/com/tianyaworld/pmxxlclaims/client/jm/ClaimsOverlayManager.java`
- 改动点：`PmxxlClaimsClient.java:15,58`、`MinimapRenderer.java:46-48`、`client/build.gradle:15,23`、`client/src/main/resources/fabric.mod.json:12,14-16`

评审方式：**只读**评审，未修改任何源码、未执行构建。
行号约定：Java 行号相对 `client/src/main/java/com/tianyaworld/pmxxlclaims/client/`；构建/资源文件写全路径。
证据来源：
- JourneyMap 本体 `journeymap-1.21.11-6.0.0.jar`（含内嵌 `journeymap-api-fabric-2.0.0-1.21.11.jar`），javap 反编译核对插件发现/初始化/show/渲染/坐标换算；
- API 源码与快照 jar `C:\Users\xie_f\AppData\Local\Temp\jmapi\{apisrc,jmapi.jar,jmapi-sources.jar}`（注意：这份是 **2.1.0-SNAPSHOT** 线，`Context` 已标 `@Deprecated(since="2.1.0")`，与 JM 6.0.0 内嵌的 2.0.0 不是同一版）；
- 参考实现 MapFrontiers 26.3（MIT）`MapFrontiersPlugin.java`、`PolygonOverlaySlot.java`、`OverlaySlot.java`、`OverlayDisplayState.java`；
- 服务端 `ClaimPublisher.java` / `ClaimService.java`（推送节奏与数据半径）；
- 用 JDK 17 做了 3 组最小 JVM 实验，验证"类路径上缺 JourneyMap API 时会不会 NoClassDefFoundError"。

---

## 结论摘要

1. **契约、几何、缩放三项核心都对**：entrypoint 键 `journeymap`、`@JourneyMapPlugin`（common 包）、`getModId` 与 overlay 的 modId 一致、隐式 public 无参构造；`minX..maxX+1` 的四角点与 JM 的方块边界语义吻合，JM 还会自动归一绕向、忽略 Y、自动闭合；`minZoom/maxZoom` 默认就是全量程。**正常装 JM 时领地应当能画出来。**
2. **1 条严重**：`MinimapRenderer` 一见到 journeymap 就无条件 return，于是"JM 小地图被关 / JM 叠加层被关 / 玩家装了 JM 但用别的地图"时，**JM 侧和 HUD 侧都不显示**，且没有任何配置可救；另有 6 条中等（心跳触发的全量重建、256 上限与 ±16 区块数据半径、`catch+break` 吞后续、父子领地绘制顺序、SNAPSHOT 依赖不可复现、displayOrder 压不过别的 mod）。
3. **没有发现会崩溃的问题**：无 JM 时 `ClaimsOverlayManager.tick` 在 `api == null` 处早退；已用等价 JVM 实验复现验证"引用了缺失类型的类仍可加载并走早退分支"，不会 NoClassDefFoundError（但这是易碎写法，见 m1）。

---

## 一、重点检查逐项核对（先给结论，细节见第二、三、四节）

| # | 检查项 | 结论 | 关键证据 |
|---|---|---|---|
| 1 | 客户端该用哪个 `@JourneyMapPlugin` | **正确**（common 包） | `ClaimsJourneyMapPlugin.java:7` 用 `journeymap.api.v2.common.JourneyMapPlugin`。JM 6.0.0 的 `PluginHelper.preInitPlugins` 同时认 `v2/client/JourneyMapPlugin`（旧）和 `v2/common/JourneyMapPlugin`（新），用旧包会打一条 "deprecated annotation" WARN |
| 2 | `apiVersion` 取值 | **当前无影响** | 6.0.0 的 `PluginHelper.inVersionRange(String)` 反编译出来是 `iconst_1; ireturn`（恒 true）。写 `"2.0.0"` 不报错；MapFrontiers 写的是 `"2.0.0-SNAPSHOT"`，建议对齐以免将来 JM 真做校验 |
| 3 | entrypoint 名字是否 JM 认的 | **正确** | `journeymap/common/helpers/FabricClientHooks.getClientPluginScanResult()` → `FabricLoader.getEntrypoints("journeymap", IJourneyMapPlugin.class)`；`fabric.mod.json:12` 用的正是 `journeymap` |
| 4 | `getModId` 是否必须与 mod id 一致 | **必须与"overlay 用的 modId"一致**；当前一致 | JM 用 `getModId()` 做 `clientPlugins` 的 key（`PluginHelper`），`ClientAPI.show/removeAll` 又用 `Displayable.getModId()` 反查 wrapper（`ClientAPI.getPlugin(String)`）。两边都是 `"pmxxlclaims"`。注意这是**两份字面量**（`ClaimsJourneyMapPlugin.java:18` 与 `ClaimsOverlayManager.java:28`），建议合成一个常量 |
| 5 | 无参构造 | **满足，但别改成包私有** | `PluginHelper` 用 `getDeclaredConstructor().newInstance()`，**没有** `setAccessible(true)` → 类必须 public（当前 `public final`，OK）。Fabric 取 entrypoint 时会先 new 一个、JM 再 new 一个，构造函数会跑两次——所以构造函数里不要放有副作用的初始化 |
| 6 | MapPolygon 点位顺序 / 是否闭合 / 是否 +1 | **顺序不用改；+1 是对的；闭合由 JM 自己做** | `MapPolygon` 文档只说"建议 lower-left 起、逆时针"；实际渲染 `DrawPolygonStep.toScreen` 会按有符号面积自动 `Collections.reverse` 归一绕向、去掉连续重复点、去掉首尾重合点。`MapRenderer.getBlockPixelInGrid(BlockPos)` 反编译显示**只用 x/z**（`method_10263`/`method_10260`），坐标是方块边界；MapFrontiers 单方块四角也是 `pos, pos+(1,0), pos+(1,1), pos+(0,1)`（`FrontierOverlay.java:2052-2058`）→ 闭区间 `minX..maxX` 对应边界 `minX..maxX+1`，**当前写法没有 off-by-one** |
| 7 | BlockPos 的 Y 用玩家当前 Y 是否合理 | **无害，但建议改常量** | Y 在 JM 里被完全忽略（见 6）。MapFrontiers 用常量 `OVERLAY_Y = 70`（`FrontierOverlay.java:82`）。用玩家 Y 会让"Y 有意义"这个错觉固化，将来若 JM 的 underground 地图按 Y 过滤，行为会随玩家高度漂移 |
| 8 | 每次 removeAll + 逐条 show | **不算错，但浪费**；见 M1 | `ClaimsOverlayManager.java:66,81-93`。JM 的 `polygonDrawSteps` 是**无上限**的 `weakKeys` 缓存，而 `DrawPolygonStep.overlay` 是强引用（value→key），所以弱键其实永远回收不掉；**只有 `PluginWrapper.removeAll(DisplayType)` 会调用 `DataCache.invalidatePolygon`**（`api.remove(单条)` 不会）。当前代码每轮 `removeAll` 恰好替 JM 做了清理，所以**不泄漏**；但这也意味着改成逐条 remove 会变成真泄漏（见 M1 修法）。每轮仍要重新三角化 + 重建 draw step；MapFrontiers 的做法是按 key 复用同一个 `PolygonOverlay` 只改属性 |
| 9 | 500ms 节流够不够 | **够** | 服务端 3 秒巡检 + 10 秒心跳（`ClaimPublisher.java:22,51`），500ms 远快于数据变化；真正的问题不是节流而是**签名把心跳也算成变化**（M1） |
| 10 | 256 条上限 | **能打到，且与 HUD 不一致**；见 M2 | `ClaimsOverlayManager.java:30,124-126`；上限是"box 数"不是"领地数"，多区域领地会吃多份 |
| 11 | `api.show` 抛异常会不会导致后面全不画 | **会（但概率低）**；见 M3 | `ClientAPI.show` 内部 `catch (Throwable)` 只 `logError`，所以真正的异常来自 `new ShapeProperties/MapPolygon/PolygonOverlay`；`ClaimsOverlayManager.java:94-97` 用 `break` |
| 12 | 线程 | **安全** | `tick` 挂在 `ClientTickEvents.END_CLIENT_TICK`（客户端主线程）；`initialize` 由 JM 在客户端初始化阶段调用；`requestRebuild()` 只写一个 volatile/普通 boolean。`ClaimCache` 字段全是 volatile |
| 13 | `setLabel/setTitle` 能否显示名字 | **能**（小地图/全屏都画，受 JM 自身裁剪） | `BaseOverlayDrawStep.drawText` 先判 `overlay.getTextProperties().isActiveIn(uiState)`，再用 `labelLines` 在 `labelPosition`（多边形中心）`drawLabels`；`titleLines` 只在非小地图画（`BaseOverlayDrawStep.drawText` 里 `ui == Minimap` 直接跳到结尾）。`TextProperties` 默认非 null、activeUIs/mapTypes 全开、scale=1、白字黑底 0.5、zoom 2..16384。MapFrontiers 的注释也印证："JourneyMap's polygon renderer always dereferences TextProperties" |
| 14 | `setActiveUIs(MapType/UI.values())` | **正确** | `Overlay.setActiveUIs/setActiveMapTypes` 同时把集合写进 `textProperties`（`Overlay.java:261-291`），所以标签也一起生效 |
| 15 | minZoom/maxZoom 默认会不会缩放不显示 | **不会，默认就是全量程** | `Overlay` 字段初值 = `UIState.FULLSCREEN_ZOOM_MIN`(2) / `ZOOM_IN_MAX`(16384)，`isInZoomRange` 判 `minZoom <= zoom <= maxZoom`；JM 的 zoom 越大越放大（`zoom/512` = 每格像素）。MapFrontiers 的 `OverlayDisplayState` 也是 `MINIMUM_ZOOM=2, MAXIMUM_ZOOM=16384` |
| 16 | displayOrder 是否合理 | **偏小**；见 M6 | JM 升序绘制、越大越靠前，默认 1000；我们从 100 起 |
| 17 | 双渲染冲突 | 冲突已避开，但**代价过头**；见 S1 | `MinimapRenderer.java:46-48` |
| 18 | 没装 JM 时 `ClaimsOverlayManager` 是否安全空转 | **安全（已验证）** | `ClaimsOverlayManager.java:44-47` 在 `api == null` 早退。JVM 实验：类路径上没有该类型时，类仍能加载、`invokestatic` 能返回 null、早退分支正常执行 |
| 19 | JM 未安装时 `ClaimsJourneyMapPlugin` 会不会被加载 | **不会被主动加载** | Fabric 的 entrypoint 是按 key 懒取的；只有 JM 调 `FabricLoader.getEntrypoints("journeymap", ...)` 时才会实例化 |
| 20 | 仓库/产物是否混入 JM 代码 | **没有** | `pmxxlclaims-mod-1.0.0.jar` 内只有 `com/tianyaworld/pmxxlclaims/client/jm/*.class`，无 `journeymap/api/**`；`git ls-files` 无 JM jar（`modCompileOnly` 正确，**不要**改成 `modImplementation`/`include`） |
| 21 | `suggests` 而非 `depends` | **合理** | 没装 JM 时 HUD 仍工作；`suggests` 只是提示，不阻断加载。真正的困惑来自 S1（装了 JM 反而没东西看），不是依赖声明 |

---

## 二、严重问题

### S1【严重】"装了 JourneyMap" 就等于 "关掉自带 HUD"，JM 侧没开小地图/叠加层时，两端都不显示

**位置**：`MinimapRenderer.java:46-48`（唯一判据）、`PmxxlClaimsClient.java:58`、`ClaimsOverlayManager.java:44-51`、`ClaimsOverlayManager.java:91-92`。

```java
// MinimapRenderer.java:46-48
if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("journeymap")) {
    return;                       // ← 只要 JM 在 classpath 上就永不画 HUD
}
```

**机理**：这两条渲染路径的可见性条件**不是同一个**。

- HUD 侧的条件是"JM 这个 mod 有没有装"（`isModLoaded`）；
- JM 侧的条件是"JM 的 Minimap 这个 UI 是否 active + 玩家是否开着"——`Overlay.isActiveIn(UIState)` 要求 `uiState.active == true && uiState.ui ∈ activeUIs && uiState.dimension == 当前维度`（`Overlay.java:299-304`），而 `activeUIs` 里虽有 Minimap，但 **JM 小地图没在显示时，UIState.active 不为 true（至少它不会被 JM 画出来）**。

于是下面这些组合**两边都不画**：

1. 玩家按 J 把 JM 的小地图收起来了（JM 里这是常用操作）；
2. 玩家在 JM 设置里关掉 Minimap 这个 UI；
3. 玩家装了 JM 但主要用别的地图（Xaero's/原版），JM 小地图没开；
4. JM 侧因为任何原因没有画（玩家关了叠加层、UI 未激活）——我们既没有检测，也没有回退；
5. 全屏地图没开、小地图也没开——只剩一个后台的 JM。

而且**没有逃生口**：`ClientConfig` 里没有"JM 模式下也画 HUD"的开关（`ClientConfig.java:14-20` 只有 minimapVisible/size/margin/alpha/showCount），M 键改的 `MinimapRenderer.visible` 在 JM 模式下完全看不到效果（`PmxxlClaimsClient.java:50-55` 照样写盘），玩家只会觉得"按 M 没反应"。

**修法（三选一，建议 1+2）**：

1. **按 JM 小地图的真实激活状态决定是否退回 HUD。** 在 `ClaimsJourneyMapPlugin.initialize` 里订阅 `ClientEventRegistry.DISPLAY_UPDATE_EVENT`（`journeymap.api.v2.common.event.ClientEventRegistry`，事件类 `journeymap.api.v2.client.event.DisplayUpdateEvent`，MapFrontiers 的 `MapFrontiersPlugin.java:69-83` 就是这么用的），记录：

   ```java
   ClientEventRegistry.DISPLAY_UPDATE_EVENT.subscribe("pmxxlclaims", e -> {
       if (e.uiState.ui == Context.UI.Minimap) {
           minimapActive = e.uiState.active;      // volatile
       }
   });
   ```

   然后 `MinimapRenderer.render` 的早退条件改成
   `isModLoaded("journeymap") && ClaimsJourneyMapPlugin.minimapActive()`。
   这样 JM 小地图关掉时 HUD 自动接管，全屏地图仍然由 JM 画。

2. **加配置开关**：`ClientConfig` 增加 `hudWithJourneyMap`（默认 false）与 `jmOverlay`（默认 true），`MinimapRenderer` 用 `isModLoaded && !ClientConfig.hudWithJourneyMap()` 早退；M 键在 JM 模式下改成切换"JM 叠加层显示/隐藏"（`api.removeAll(MOD_ID, DisplayType.Polygon)` / 重新 show），否则这个键就是死的。

3. 最低限度：在 `MinimapRenderer` 里至少把"JM 是否真的在画"这件事打一条 `LOGGER.info`（一次性），别再让"装了 JM 就消失"变成无日志的静默行为。

---

## 三、中等问题

### M1【中】心跳包把 `packets` 顶起来 → 每 10 秒全量 `removeAll` + 重建 256 个 overlay

**位置**：`ClaimsOverlayManager.java:55`（签名含 `ClaimCache.packets()`）、`:56-61`（据此判定"变了"）、`:66`（`removeAll`）、`:81-93`（逐条 new + show）。

```java
String signature = worldId + "|" + ClaimCache.packets() + "|" + ClaimStore.version()
        + "|" + ClaimCache.isActive() + "|" + ClientConfig.claimAlpha();
```

**证据链**：
- 服务端 `ClaimPublisher.java:22` `HEARTBEAT_MS = 10000`，`:89` 判断心跳，`:99` "`!heartbeat && previous == signature` 才跳过"，`:136-150` 心跳**无条件发送**；
- 客户端 `ClaimCache.java:38` 每收到一包 `packets++`（即使是内容完全相同的心跳）；
- 结果：稳定在线时 `packets` 每 10 秒必变 → `signature` 必变 → 每 10 秒把本 mod 在所有维度的 polygon 全删（`removeAll` 会遍历 `dimensionOverlays` 所有维度并 `DataCache.invalidatePolygon`，见 `PluginWrapper.removeAll`），再 new 出最多 256 个 `ShapeProperties + MapPolygon + PolygonOverlay + DrawPolygonStep` 并重新三角化。

**后果**：稳态下每 10 秒一次可观测的分配/三角化尖峰（256 个四边形、每块 2 个三角形，量不大但全是垃圾）；`drawStepsUpdateNeeded` 每轮被置 true（`ClientAPI.show`），JM 每 10 秒重建一次 draw step 列表；overlay 的 UUID 每次都换，JM 的 title/rollover 与将来的 popup menu 事件对实例的引用都会丢。玩家在 JM 里对某个 overlay 做的"记住/隐藏"类状态也会随实例消失。

**修法**：
1. 签名改成**内容签名**（不要用 `packets`）：例如由 `ClaimCache` 在"解码成功且内容真的变了"时自增一个 `contentVersion`（比较 claim 列表的 `name/color/flags/boxes`），签名用 `contentVersion`；或者直接在 `ClaimsOverlayManager` 里对 `collect()` 的结果算 64 位 hash。
2. 更进一步（推荐，照 MapFrontiers）：维护 `Map<String, PolygonOverlay>`，每轮对 key 做 add/update——复用的 overlay 只调 `setShapeProperties/setOuterArea/setDimension/setLabel/...` 再 `show`（`ClientAPI.show` 对同 id 是替换，JM 会沿用同一个 `DrawPolygonStep` 缓存，便宜），**只有新增才 new**（参考 `PolygonOverlaySlot.reconcile` + `OverlaySlot.reconcilePublication`，MIT 可抄）。
   ⚠️ 但**不要用 `api.remove(单条)` 删除**：`PluginWrapper.remove` 只 `setEnabled(false)`，**不会** `DataCache.invalidatePolygon`，而 `polygonDrawSteps` 是无上限的 `weakKeys` 缓存、value 又强引用 key，删掉的 overlay 会永久留在缓存里（真泄漏）。要删除就攒到整批 `removeAll` 里做（例如切维度/离开世界时一次 `removeAll`），或干脆只做 add/update、把删除也交给一次 `removeAll`。
3. 顺手：`removeAll` 会清掉**所有维度**的 polygon。若将来要按维度精确清理，注意 JM 只提供 `api.remove(Displayable)`（没有 modId/维度重载），而且如上所述它不清缓存——所以「按维度精确清理」只在「随后反正还会来一次 `removeAll`」时才有意义。

### M2【中】`MAX_OVERLAYS=256` + 服务端 ±16 区块半径，与 JM 全屏地图的预期严重不符

**位置**：`ClaimsOverlayManager.java:30`（256）、`:119-126`（按"离玩家最近"排序后截断）；服务端 `PmxxlClaims.java:67`（`radius-chunks` 默认 16）、`ClaimService.java:45`（上限 32）。

- 数据只有玩家周围 ±16 区块（±256 格，最大配到 ±512 格）；
- JM 全屏地图常态能看 1000~4000 格，缩到中远景时**领地图层直接消失**，看起来像"模组没画全/坏了"；
- 256 是**box 数**不是领地数：一个多区域领地带 N 个 box 就吃 N 份，大服（`ClaimService` 上限 8192 claim）很容易打满；打满后按"离玩家最近"保留，全屏看到的就是一个以玩家为中心的圆形补丁；
- HUD 路径**没有**这个上限（`MinimapRenderer.java:106-128` 全画），两条渲染路径的覆盖范围不一致。

**修法**：
- 产品层先把 `radius-chunks` 调到 32 并把"只覆盖周边"写进使用说明；或
- 客户端按 JM 的 `UIState.blockBounds`（全屏地图可见范围）做优先级排序，而不是按玩家距离；或
- 真要"全屏可见"，需要客户端主动按视野请求（协议加请求包）——这一轮不改协议的话，至少加一个"数据只覆盖周边 N 格"的提示（JM 的 waypoint/聊天一次性提示都行）。
- 另外把上限改成"领地数"或直接提到 1024 并实测帧率。

### M3【中】`catch + break`：一个 overlay 出错会让本轮后面所有领地都不画，而且下一轮也不会重试

**位置**：`ClaimsOverlayManager.java:94-97`；`:59-60`（`lastSignature` 在 `rebuild` **之前**就写死）。

```java
} catch (Exception exception) {
    LOGGER.debug("failed to show claim overlay " + entry.name() + ": " + exception.getMessage());
    break;                 // ← 后面所有 entry 全部跳过
}
```

**证据**：`journeymap.api.client.impl.ClientAPI.show` 内部是 `try { ... } catch (Throwable) { logError(...) }`，也就是 **`show` 自己不会抛**——所以这里真正能触发 `catch` 的只有 `new ShapeProperties/MapPolygon/PolygonOverlay`（例如将来有人把 `MapPolygon` 改成少于 3 点、dimension 为 null、坐标极端导致数组越界）。一旦触发，`break` 让剩余领地全灭；同时 `lastSignature` 已经写进 `:60`，`dirty` 已置 false，**下一轮 signature 不变 → 不会重试**，直到服务端数据再变（最多 10 秒，但表现就是"地图上少了一大片"）。
另外 `catch (Exception)` 不接 `LinkageError`（API 版本错配时会抛 `NoSuchMethodError`）。

**修法**：
```java
} catch (Exception e) {          // 或 catch (Throwable t)
    LOGGER.warn("claim overlay failed: " + entry.name(), e);   // 用 warn，别用 debug，便于线上定位
    continue;                     // 不影响其它领地
}
```
并把 `lastSignature = signature` 移到 `rebuild` **成功返回之后**（失败时保持 dirty=true），这样下一轮会重试。

### M4【中】父子领地/重叠区域的绘制顺序按"离玩家远近"排，父领地会盖住子领地

**位置**：`ClaimsOverlayManager.java:119-123`（按玩家距离排序）、`:90`（`setDisplayOrder(order++)`）、`:80`（子领地 `strokeWidth=1.0f`，父领地 2.0f）。

**机理**：JM 按 displayOrder **升序**绘制，值越大越靠前（`Overlay.setDisplayOrder` 文档 + `ClientAPI.getDrawSteps` 用 `Comparator.comparingInt(getDisplayOrder)` 排序后顺序绘制）。当前排序是"离玩家近的在前"，而 `order` 从 100 递增——于是**离玩家最近的 order 最小 = 最先画 = 压在最底层**。玩家站在子领地旁边时：

- 子领地（小、中心通常更近）→ order 小 → 先画；
- 父领地（大、中心更远）→ order 大 → 后画，0.4 透明填充 + 2px 白边直接盖在子领地那 1px 细边上。

**修法**：把"截断用的距离排序"和"绘制顺序"分开：
```java
entries.sort(Comparator.comparingInt((Entry e) -> -( (e.maxX()-e.minX()) * (e.maxZ()-e.minZ()) ))   // 面积大的先画
             .thenComparing(e -> (e.subzone() ? 1 : 0)));                                            // 子领地最后
```
即 displayOrder 按"面积降序 + 子领地靠后"分配；距离只用来决定 256 截断。

### M5【中】`journeymap-api-fabric:2.0.0-1.21.11-SNAPSHOT` 不可复现；编译期用的是 2.1.0-SNAPSHOT 线，运行期是 2.0.0

**位置**：`client/build.gradle:15`（仓库）、`:23`（依赖）。

**证据**：
- `jm.gserv.me` 的 `maven-metadata.xml` 里 `journeymap-api-fabric` **只发布 `*-SNAPSHOT`**（`2.0.0-1.21.11-SNAPSHOT` 的 snap 时间戳是 `20260529.024458-20`）；SNAPSHOT 会被后续构建覆盖，Gradle 默认 24h 后重新解析 ⇒ 同一份源码隔天可能编到不同 API；离线（CI/新机器）没有缓存直接失败。
- 我们读的 API 源码/jar（`apisrc`、`jmapi.jar`）是 **2.1.0-SNAPSHOT** 线（`Context` 有 `@Deprecated(since="2.1.0")`，注释说 26.2 会搬到 `journeymap.api.v2.common`），而 **JM 6.0.0 内嵌的运行时 API 是 `2.0.0-1.21.11`**（`journeymap-api-fabric-2.0.0-1.21.11.jar`，`IClientAPI.API_VERSION = "2.0.0-SNAPSHOT"`）。当前代码只用了稳定 API 所以没问题，但"编译用的 snapshot 比运行时新"是个定时炸弹（将来顺手用了 snapshot 新增的方法 → 线上 `NoSuchMethodError`）。

**修法**：
1. 依赖 pin 到带时间戳的具体版本（可复现、可离线缓存）：
   ```groovy
   modCompileOnly "info.journeymap:journeymap-api-fabric:2.0.0-1.21.11-20260529.024458-20"
   ```
   并在 `resolutionStrategies` 里对 `info.journeymap` 关掉 changing 缓存（如果必须用 SNAPSHOT）。
2. 给仓库加 content 过滤，别让 Gradle 拿它去查 Minecraft/Fabric 依赖（现在每次解析都会多问一个外网仓库，既是速度也是失败点）：
   ```groovy
   maven {
       name = 'JourneyMap'
       url = 'https://jm.gserv.me/repository/maven-public/'
       content { includeGroup 'info.journeymap' }
   }
   ```
3. 想跑 dev 客户端看效果，再加 `modLocalRuntime`（可选，非缺陷）。
4. **不要把 API jar 打进产物**（现在是 `modCompileOnly`，正确；JM 许可证禁止分发 `journeymap.*` 的源码或 class）。

### M6【中】`displayOrder` 从 100 起，和其他 mod（默认 1000）共存时会被压在下层

**位置**：`ClaimsOverlayManager.java:72,90`。

JM 的 `Overlay.displayOrder` 默认 1000（越大越靠前）。我们 100 起步，同装 MapFrontiers / 其他画多边形或标记的 mod 时，本模组的领地图层会画在它们**下面**（被半透明填充盖住、标记被压在领地之上/下取决于对方值）。目前单独运行时无感，但这是个"和别人一起装就出问题"的隐患。

**修法**：改成明显更大的基准，例如 `int order = 1000;`（或 2000），并让父子顺序按 M4 的规则在基准上递增。

---

## 四、轻微问题

| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| m1 | 无 JM 时依赖"JVM 懒解析"才能不崩：`tick` 里 `ClaimsJourneyMapPlugin.api()` 的方法描述符就带 `IClientAPI`，目前靠"返回 null 立刻早退"活下来（我用等价类做了 3 组实验确认可行，包括 StackMapTable 里带缺失类型的局部变量）。一旦有人把早退改成 `api.xxx()`、或在字段初始化里碰 JM 类型，就会在**每个 tick** 抛 `NoClassDefFoundError`（不是启动时，而是进游戏后每 tick 一次） | `ClaimsOverlayManager.java:43-47`、`ClaimsJourneyMapPlugin.java:10-14` | 在 `tick`/HUD 入口显式门控：`private static final boolean JM = FabricLoader.getInstance().isModLoaded("journeymap");`，`if (!JM) return;`，再进行任何 JM 调用。这比"靠 null 早退"稳得多 |
| m2 | `isModLoaded` 每帧查一次 | `MinimapRenderer.java:46` | 提到 `static final boolean JOURNEYMAP_LOADED` |
| m3 | M 键在 JM 模式下是死键，却仍然写配置 | `PmxxlClaimsClient.java:50-55` | JM 模式改成切换 JM 叠加层（见 S1 修法 2），或直接提示"由 JourneyMap 接管" |
| m4 | 兜底数据源（没装服务端插件）在 JM 里所有领地同一个浅蓝 `0x4FC3F7`，HUD 里是按名字哈希的调色板；且 JM 里没有 `claims N` 计数 | `ClaimsOverlayManager.java:111,113` vs `MinimapRenderer.java:113,126,89-92` | 抽一个 `colorFor(name)` 给两条渲染路径共用；计数若要保留，画在 JM 的 waypoint 或改用 `title` 附在领地中心 |
| m5 | 每个 box 都设同一个 label，多区域领地会在每个子区域各画一遍名字 | `ClaimsOverlayManager.java:88-89,105-107` | 只在"该 claim 面积最大的那个 box"上设 label，其余 `setLabel(null)`；或者按 box 序号加后缀（如 `name + " #2"`） |
| m6 | `setOverlayGroupName` 未设置 | `ClaimsOverlayManager.java:87-92` | 加 `overlay.setOverlayGroupName("claims")`，便于 JM 侧归类/将来做开关 |
| m7 | `Context` 在编译用的 API 里已 `@Deprecated(since="2.1.0")`，26.2 会搬到 `journeymap.api.v2.common` | `ClaimsOverlayManager.java:8,91-92` | 把 `Context.UI/MapType` 的引用收敛到一个私有方法里，将来只改一处；编译告警可接受但别扩散 |
| m8 | `apiVersion = "2.0.0"` 与参考实现的 `"2.0.0-SNAPSHOT"` 不一致 | `ClaimsJourneyMapPlugin.java:7` | 对齐成 `"2.0.0-SNAPSHOT"`（6.0.0 不校验，但将来会） |
| m9 | `lastSignature`/`dirty` 是静态状态，`resetSession` 不清 | `ClaimsOverlayManager.java:32-34`、`PmxxlClaimsClient.java:64-70` | 在 `resetSession` 里加 `ClaimsOverlayManager.reset()`（置 dirty=true、清 signature），避免"重连后恰好签名相同"的极端情况下不重画 |
| m10 | 工作副本里的 `jm` 包没进 git 仓库副本（`git ls-files` 里没有 `client/.../jm/`，`pmxxl-claims-map` 的 HEAD 只改了 README）；同时工作目录根下躺着 `journeymap-1.21.11-6.0.0.jar` | `pmxxl-claims-map` / 工作目录 | 提交 jm 包时**别**把 JM 本体 jar 一起加进去（许可证禁止分发；目前 `git ls-files` 没有 jar，保持即可），并把 `*.jar` 明确写进 `.gitignore` |

---

## 五、需要进游戏实测才能确认（离线无法判定）

1. **S1 复现**：JM 6.0.0 + 本模组进服 → 按 J 收起 JM 小地图 → 确认是否"两边都不显示"；再在 JM 设置里关掉 Minimap UI 复验一次。
2. **边界对齐**：站在领地角上，把 JM 全屏地图放到最大，看 polygon 是否**压住** `minX..maxX` 这一整块方块（+1 的视觉验证；理论值正确，但要看 JM 的 `roundToScreenPixel` 在极端缩放下有没有半像素偏移）。
3. **缩放全区间**：全屏地图从最远缩到最近，确认领地不会在某一级缩放消失（默认 minZoom=2/maxZoom=16384 理论上全覆盖，但要实测 JM 全屏 zoom 是否真的不低于 2）。
4. **地图类型**：Day / Night / Underground / Topo / Biome 各切一次。Underground 是唯一可能和 Y 有关的类型（虽然 `getBlockPixelInGrid` 不用 Y），我们传的是玩家当前 Y。
5. **M1 抖动**：站在领地不动 60 秒，观察帧时间/内存（JM 的 F3 或 spark）是否每 10 秒出现一次尖峰；同时在日志里数 `claim overlays` 重建次数（建议临时加一条 debug 计数）。
6. **M2 覆盖**：在领地密集的大服，把 JM 全屏缩到能看 2000 格，确认超出 ±16 区块后领地消失是否符合产品预期；如有 >256 个 box，确认被截断的那部分是否"恰好是远处"。
7. **M4 父子**：在一个父领地里放一个子领地，分别站在子领地内/外，看父领地的 0.4 填充和 2px 白边是否糊掉子领地的 1px 细边。
8. **M6 共存**：同时装 MapFrontiers（或任何用 overlay 的 mod），确认我们的领地图层没被压在下面。
9. **JM 内开关**：JM 的"Overlay 管理/显示叠加层"总开关关掉后再验证一次行为（此时 `UIState.active` 的表现）。
10. **切维度/重连**：下界→主世界→重连，确认领地都能在 10 秒内重新出现（对应 m9）。
11. **无 JM 基线**：删掉 JM，确认 HUD 正常、且日志里没有 `NoClassDefFoundError`（我做了 JVM 等价实验，但真机加载顺序不同，值得跑一次）。
12. **标签**：JM 小地图上领地名字是否可读（`TextProperties.scale` 默认 1，小地图上可能太小）；全屏地图上标签是否被 JM 的 `isTextInMinimap` 裁剪逻辑裁掉。

---

## 附录 A：证据与复现方式（只读，不涉及构建）

```bash
# 1) JM 本体里内嵌的 API 版本
unzip -p journeymap-1.21.11-6.0.0.jar fabric.mod.json | grep -A3 '"jars"'
unzip -l journeymap-1.21.11-6.0.0.jar | grep journeymap-api

# 2) 插件发现：entrypoint 键 + 注解 + 构造 + modId 校验
unzip -o journeymap-1.21.11-6.0.0.jar 'journeymap/*' -d jm
javap -p -c -classpath jm journeymap.common.helpers.FabricClientHooks   # getEntrypoints("journeymap", IJourneyMapPlugin.class)
javap -p -c -classpath jm journeymap.api.common.PluginHelper           # preInitPlugins / inVersionRange / initPlugins

# 3) show 的异常语义、removeAll 的范围、坐标换算
javap -p -c -classpath jm journeymap.api.client.impl.ClientAPI         # show: catch Throwable -> logError
javap -p -c -classpath jm journeymap.api.common.PluginWrapper          # removeAll 遍历所有维度 + invalidatePolygon
javap -p -c -classpath jm journeymap.client.render.map.MapRenderer     # getBlockPixelInGrid(BlockPos) 只用 x/z

# 4) 缩放常量
javap -p -constants -classpath <jmapi.jar> journeymap.api.v2.client.util.UIState  # ZOOM_IN_MAX=16384, FULLSCREEN_ZOOM_MIN=2
```

**"无 JM 不崩"的 JVM 实验**（等价结构：静态 volatile 缺类型字段 + 方法返回缺类型 + 调用处 `if (x == null) return;` + 带缺类型参数的私有方法）：类路径上删掉依赖类后，类仍能加载、`tick` 连续执行 3 次正常早退，仅当真正执行到"使用该类型"的指令时才抛 `NoClassDefFoundError`。结论：当前 `api == null` 早退写法可工作，但属易碎写法（见 m1）。

---

## 附录 B：建议的修复顺序（按性价比）

1. **S1**（可见性兜底）+ **m1**（显式 `isModLoaded` 门控）——一次改完，收益最大。
2. **M1**（内容签名；再考虑复现 overlay 身份）——去掉稳态 10 秒一次的无效重建。
3. **M3 + M4**（`continue` + 面积/子领地排序）——小改动，避免"少画一片"和"父盖子"。
4. **M6 + m6 + m5**（displayOrder、groupName、label 去重）——纯视觉层次。
5. **M5**（pin 版本 + content 过滤）——构建可复现，CI 不再看外网仓库脸色。
6. **M2**（半径/上限/提示）——需要产品拍板，代码侧只需改排序与提示。
