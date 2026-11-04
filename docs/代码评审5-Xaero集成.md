# 代码评审 5：Xaero's Minimap 集成

评审日期：2026-11-04  
评审对象：本轮新增的 Xaero 后端（客户端）
- `client/src/main/java/com/tianyaworld/pmxxlclaims/client/xaero/XaeroClaimsBridge.java`（核心，301 行）
- 改动点：`PmxxlClaimsClient.java:46-49`、`MinimapRenderer.java:42-49`、`ClaimsOverlayManager.java:54-60`、`ClientConfig.java:19,79-81,93`、`client/build.gradle:28-29`、`client/gradle.properties`、`client/src/main/resources/fabric.mod.json:14-17`

评审方式：**只读**评审，未修改任何源码、未执行构建。  
行号约定：Java 行号相对 `client/src/main/java/com/tianyaworld/pmxxlclaims/client/`；构建/资源/文档写全路径。

证据来源（全部为反编译核对，不是猜）：
- 游戏本体：`~/.gradle/caches/fabric-loom/minecraftMaven/.../minecraft-merged-1.21.11-yarn+build.6.jar`、`intermediary-v2.tiny`、`yarn-1.21.11+build.6/mappings.jar`（用于把 `class_2874.comp_646` 解析成 `DimensionType.coordinateScale`）
- Xaero 本体：`client/libs/xaerominimap-fabric-1.21.11-26.6.2.jar`（26.6.2，id=`xaerominimap`）+ `xaerolib-fabric-1.21.11-1.8.5.jar`
- 关键类 javap：`xaero.common.minimap.render.MinimapRenderer`、`xaero.hud.minimap.element.render.MinimapElementRendererHandler` / `...OverMapRendererHandler` / `...RenderInfo` / `...Reader` / `...Graphics`、`xaero.common.minimap.MinimapProcessor`、`xaero.hud.minimap.module.MinimapSession`、`xaero.hud.minimap.config.util.MinimapConfigClientUtils`、`xaero.lib.client.graphics.util.ImmediateRenderUtil`
- 参照实现：XaeroPlus（MIT，`C:/Users/xie_f/AppData/Local/Temp/xaero_addons/XaeroPlus`）里 `MixinRadarRenderer` 的 `renderElement` 注释与 `@Local(argsOnly=true) LocalFloatRef optionalScaleRef` 印证了签名语义
- 仓库与产物：`pmxxl-claims-map/.gitignore`、`git ls-files`、`client/build/libs/pmxxlclaims-1.0.0.jar`、`pmxxlclaims-mod-1.0.0.jar`

---

## 结论摘要

1. 挂载手法本身是**对的**：`HudMod.INSTANCE → getMinimap() → getOverMapRendererHandler().add(renderer)`、`begin/hasNext/getNext/end`、`renderElement` 都是 26.6.2 里真实存在的公开扩展点，无 mixin、无反射。
2. 但**缩放取错了字段**：`info.backgroundCoordinateScale` 是 `DimensionType.coordinateScale`（主世界 1.0、下界 8.0），**不是 zoom**；下界领地必定被画成 8 倍大，主世界也只在某一个 zoom 值上凑巧对。
3. 另有 3 条"看着不对/干脆不显示"的严重问题：没有视口裁剪（远处领地被钳到小地图边缘照样整块画出来）、地图旋转时矩形不跟转（Xaero 默认就是旋转地图）、以及"装了 Xaero 但小地图没画"时 HUD 与 JourneyMap 同时被关掉导致什么都不显示。未发现崩溃路径，未发现混入 Xaero 代码/ jar。

---

## 一、重点检查逐项结论

| # | 检查项 | 结论 | 关键证据 |
|---|---|---|---|
| 1 | `backgroundCoordinateScale` 是什么 | **维度坐标系数，不是 zoom** | 见下方 §1.1 字节码追踪 |
| 2 | 有没有更可靠的缩放来源 | **有**：`processor.getMinimapZoom() * GuiUtils.getMinimapScale(cm) / 2.0` | 见 §1.2 |
| 3 | 矩形中心 `(minX+maxX+1)/2` | **正确** | Xaero 的 `getRenderX/Z` 是方块坐标，`translatePosition` 用 `(x - renderPos.x) * zoom`；`minX..maxX+1` 的几何中心正是 `(minX+maxX+1)/2` |
| 4 | fill 用负坐标 | **合法** | `MinimapElementGraphics.fill` 内部会交换 left/right、top/bottom，再用矩阵画四边形 |
| 5 | 圆形小地图会不会画到圆外 | **会**（至少会画到方框/纹理边上） | `translatePosition` 圆形分支只把**中心**夹到圆周并回传 clipped=true，不裁剪四边形；Xaero 小地图本体里搜不到任何 scissor 调用 |
| 6 | 旋转地图时色块是否跟转 | **不跟转** | `translatePosition` 只调用 `MatrixStack.translate`，从不 `rotate/multiply`；旋转被烘焙进了平移量。Xaero 默认 `NORTH_LOCKED=false`（= 地图随玩家旋转） |
| 7 | 洞穴模式 / 不同 MapType | **能画，但不受控** | `info.cave` 未使用；色块永远画在 OVER_MINIMAP 这一层，与洞穴/地表模式无关（2D 领地本来也无所谓，但"下界 8 倍"会叠加） |
| 8 | `info.renderPos` 是玩家还是地图中心 | **玩家（摄像机实体）位置** | `renderMinimap` 里 `new Vec3d(getEntityX/Y/Z(cameraEntity))`，用到世界地图区块时再除以 `mapScale/entityScale` |
| 9 | 每帧重建 + 排序 512 条 | **可接受但不必要**；真正的开销在 5×N 次立即绘制调用 | `ImmediateRenderUtil.coloredRectangle` 每次都以 `drawImmediateMeshData` 结束 → 每个盒子 5 次 GPU draw |
| 10 | handler 是否每帧调用所有 renderer | **是** | `MinimapElementRendererHandler.render` 每帧遍历 `renderers`，先 `shouldRender`，再 `provider.begin/hasNext/getNext` |
| 11 | 注册时机 / 状态残留 | **基本合理，但失败一次永久废掉** | `XaeroClaimsBridge.java:70-73` `catch (Throwable) { failed = true; }`，之后 `tick` 直接 return，且不留任何重试或世界切换重置 |
| 12 | Xaero 未装时类加载安全 | **安全** | 两处入口都在 `isModLoaded("xaerominimap")` 短路之后（`PmxxlClaimsClient.java:47`、`MinimapRenderer.java:42`）；`XaeroClaimsBridge.isActive()` 本身不触发 Xaero 类型解析 |
| 13 | 后端互斥 | **互斥条件不完整** | "装了 Xaero" ≠ "Xaero 正在画小地图"；见 S4 |
| 14 | 装 Xaero 时 JM 会不会残留已画的 overlay | **不会（同一次启动内）** | `ClaimsOverlayManager.tick` 在 Xaero 存在时直接 return，`rebuild()` 从不执行，也就没有 `show()`；只有运行中热插拔 Xaero 才会残留（现实中做不到） |
| 15 | 装 JM 时 Xaero 分支是否安全 | **安全** | 所有 Xaero 入口都在 `isModLoaded` 之后 |
| 16 | 合规 | **干净** | `pmxxl-claims-map/.gitignore` 有 `client/libs/`；`git ls-files` 无任何 Xaero jar；构建产物内只有 `com/tianyaworld/.../xaero/*.class`（我们自己的包），**没有** `xaero/common/**` 第三方类 |
| 17 | 构建可复现性 | **有硬伤** | `client/build.gradle:28-29` 写死两个文件名；缺 jar 时 Gradle 只报 "package xaero.common does not exist" 之类，不告诉你要放什么文件 |

### 1.1 `backgroundCoordinateScale` 的字节码追踪（结论：DimensionType.coordinateScale）

`xaero.common.minimap.render.MinimapRenderer.renderMinimap(MinimapSession, MinimapProcessor, int,int,int,int, double, float, int, float, XaeroBufferProvider)` 里：

```
351: aload_0 / getfield mc
355: getfield class_310.field_1687      // ClientWorld
358: invokevirtual class_638.method_8597()   // World.getDimension() → DimensionType (class_2874)
361: invokevirtual class_2874.comp_646()     // <<< 就是这里
364: dstore 43
...
386: dload 43 / dstore 48                    // 若走"世界地图区块"分支，先备份
391-403: SupportMods.worldmapSupport.getMapDimensionScale() → dstore 43   // 被替换
420-431: 若为 0 则 MinimapProcessor.getLastMapDimensionScale() → dstore 43
...
2085-2101: minimap.getOverMapRendererHandler()
             .render(vec3d, partialTicks, null /*framebuffer*/, dload 43, mapDimension)
```

而 `MinimapElementRendererHandler.render(Vec3d, float, Framebuffer, double, RegistryKey)` 的第 4 个 double 参数，正是 `new MinimapElementRenderInfo(..., double, RegistryKey)` 的第 8 个参数，构造函数里 `putfield backgroundCoordinateScale`（`MinimapElementRenderInfo` 构造字节码 82-85）。

再用 tiny 映射查证（`intermediary-v2.tiny` + yarn `mappings.tiny`）：

```
c esh net/minecraft/class_2874        // DimensionType
f D p comp_646 coordinateScale        // 双精度、名为 coordinateScale
m ()D g comp_646 coordinateScale
c dwr net/minecraft/class_4538        // World
m ()Lesh; F_ method_8597              // getDimension()
```

**结论**：`info.backgroundCoordinateScale = 当前维度 DimensionType.coordinateScale`。主世界/末地 = 1.0，**下界 = 8.0**（数据包可改）。它既不是 `zoom`，也不是"方块→像素"。

旁证：`MinimapElementReader.getCoordinateScale()` 默认返回 `info.renderEntityDimensionScale`（同样取自 `mc.world.getDimension().coordinateScale()`），基类处理器用 `readerScale / backgroundCoordinateScale` 求比值再决定要不要缩放元素坐标——它是"维度系数的归一化分母"，一旦当前读法和背景不一致就会被用来换算坐标，语义上完全不可能是像素比例。

### 1.2 真正的"方块→当前矩阵单位"系数是什么

在 `renderMinimap` 里：

```
112-117: this.zoom = processor.getMinimapZoom()          // 配置项 ZOOM 的当前动画值
1994-2006: dstore 70 = this.zoom * (double)fload 9 / 2.0
2057-2082: handler.prepareRender(ps=d24, pc=d26, zoom=d70, specW, specH, halfViewW, halfViewH, circle, optionalScale=f9)
```

`fload 9` 追到 `MinimapPipRenderer.renderToTexture` → `MinimapProcessor.onRender(paddingH,paddingV,w,h, scale, minimapScale, size, boxSize, partial, buffer, sw, sh)` → `renderMinimap(..., double angleOrScale, float minimapScale, int, float partial, buffer)`，即 `MinimapPipRenderState.getMinimapScale()` = `GuiUtils.getMinimapScale(HudMod.INSTANCE.getHudConfigs().getClientConfigManager())`。

`MinimapElementOverMapRendererHandler.prepareRender` 把这个 zoom 存进私有字段，`translatePosition` 里就是 `(ps*dx - pc*dz)*zoom` / `(pc*dx + ps*dz)*zoom` 得到平移像素，然后只 `translate`（不 scale、不 rotate）。

所以 renderElement 内 1 个"矩阵单位"= 1 个**缩放后的 GUI 单位**，一块地 = `zoom * minimapScale / 2` 个这样的单位。实现建议：

```java
// 在 renderElement 里，取代 backgroundCoordinateScale
MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
double unitsPerBlock = 1.0;
if (session != null) {
    unitsPerBlock = session.getProcessor().getMinimapZoom()
            * GuiUtils.getMinimapScale(HudMod.INSTANCE.getHudConfigs().getClientConfigManager())
            / 2.0;
}
int width  = Math.max(1, (int) Math.round((box.maxX - box.minX + 1) * unitsPerBlock * ClientConfig.xaeroScale()));
```

`GuiUtils.getMinimapScale`、`HudMod.getHudConfigs()`、`MinimapProcessor.getMinimapZoom()`、`BuiltInHudModules.MINIMAP.getCurrentSession()` 全部是 public（已 javap 确认）。这个表达式和 Xaero 自己喂给 `translatePosition` 的 zoom 是**同一个值**，不是拟合出来的经验系数。

> 注意：`Minimap.getMatrixStack()` 虽然 public，但它只能拿到"已经平移过"的矩阵，从里面提取不出比例；`MinimapElementRenderInfo` 里也没有 zoom/角度字段。所以只能走上面这条 public API 路线，或者接受用 mixin 读 `MinimapRenderer.zoom`——不建议。

---

## 二、严重问题

### S1【严重·画错】用 `backgroundCoordinateScale` 当像素比例：下界固定 8 倍，主世界一改 zoom 就错

**位置**：`XaeroClaimsBridge.java:228-234`

```java
double coordinateScale = info.backgroundCoordinateScale;
if (!(coordinateScale > 0.01) || coordinateScale > 64.0) coordinateScale = 1.0;
double scale = coordinateScale * ClientConfig.xaeroScale();
int width  = Math.max(1, (int) Math.round((box.maxX - box.minX + 1) * scale));
int height = Math.max(1, (int) Math.round((box.maxZ - box.minZ + 1) * scale));
```

**后果**
- 下界：`coordinateScale = 8.0` → 每格画 8 个单位，一块 16×16 的领地在默认 `xaeroScale=1.0` 下是 128×128 单位，直接糊满整张小地图并把边框甩到地图外。
- 主世界：`1.0` 与 zoom 无关。Xaero 的 `ZOOM` 默认索引是 1 → `getActualZoom()=1.0`，再 `* minimapScale / 2` 后真正的一格 ≈ `0.5` 个单位。也就是说默认设置下（Xaero 的 UI scale 取默认档、minimapScale≈1.0 时）当前代码画的领地**大约大一倍**；玩家把 zoom 改成 2/3/4 或改小地图缩放后比例继续漂移，越放大越离谱（精确倍数见 T2 实机标定）。

**改法**：按 §1.2 用 `getMinimapZoom() * getMinimapScale(cm) / 2.0`，`xaeroScale` 只作为用户微调倍率（0.25–4.0）。不要把 `backgroundCoordinateScale` 用在这里；删掉 `lastLoggedScale` 那行 INFO 或降成 DEBUG（换成 zoom 派生后每次缩放都会刷屏，`XaeroClaimsBridge.java:33,235-238`）。

---

### S2【严重·画错】没有任何视口裁剪：未进画面的领地被"钳"到小地图边缘照样整块画

**位置**：`XaeroClaimsBridge.java:123-125`（`isHidden` 恒 false）、`218-252`（`renderElement` 无条件 fill）、`96-100`（每帧把缓存里**全部**盒子交给 Xaero）

**证据**：`MinimapElementOverMapRendererHandler.translatePosition(MatrixStack, specW, specH, halfViewW, halfViewH, ps, pc, dx, dz, zoom, circle, out)` 的分支逻辑（字节码 43-279）：
- 方形模式：中心超出 `[-halfViewW/2, halfViewW/2]` 时**夹到边界**，等比缩另一个轴，`clipped = true`；
- 圆形模式：中心在圆外时沿半径**投影到圆周**，`clipped = true`；
- 然后把 `clipped` 作为 `renderElement` 的第 2 个 boolean 传进来（本实现直接丢弃），再把矩阵平移过去。

也就是说：**Xaero 从不丢弃越界元素，只把它们的中心挪到边缘**。Xaero 自己的雷达/路径点画的是几个像素的小图标，所以没问题；我们画的是几十上百像素的矩形，于是：
- 屏幕外 200 格远的领地会在小地图边缘出现一个方框；
- 玩家的领地在视野外时，小地图边上一直挂着一个色块；
- 大领地（或按 S1 放大后的任何领地）会覆盖整个小地图。

再叠加：Xaero 小地图本体 26.6.2 的类文件里搜不到任何 `scissor` 调用（`grep -ri scissor` 无结果），`MinimapElementGraphics.fill` 走的是 `ImmediateRenderUtil.coloredRectangle(Matrix4f, ...)` 直接提交网格，不会套 `DrawContext` 的裁剪栈。所以"画到圆外/方框外"是**确定会发生的**，能兜住它的只剩 1.21.9+ 的 PiP 纹理边界（见实测清单 T1）。

**改法**（按性价比排序）：
1. `renderElement` 第一行判 clipped：`if (second) return false;` —— 一行修掉"边缘挂色块"，代价是中心刚出画面的超大领地会整块消失（可接受，下一版再做精确裁剪）。
2. 在 `begin()` 里按视口半径剔除：可见半径（格）≈ `session.getConfiguredWidth() / 2.0 / unitsPerBlock`，保留与"玩家 ±半径"方形相交的盒子，其余直接不进列表。这一步同时解决 M1 的性能问题。
3. 真要精确，只能在 `renderElement` 里自己算屏幕矩形再和地图矩形求交，用多次 `fill` 拼出可见部分——复杂度高，不建议第一版做。

---

### S3【严重·画错】"随玩家旋转"是小地图默认行为，但色块永远轴对齐

**位置**：`XaeroClaimsBridge.java:242-251`（四个 `graphics.fill` 都是屏幕轴对齐矩形）

**证据**：
- `NORTH_LOCKED` 的默认值是 `iconst_0`（false），即**默认地图跟着玩家转**（`MinimapProfiledConfigOptions` 静态初始化，偏移 5206-5243）。
- `translatePosition` 把旋转烘焙进平移量（`(ps*dx - pc*dz)*zoom`），然后只调用 `MatrixStack.translate`；`renderMinimap` 整个方法里搜不到 `RotationAxis`/`multiply(Quaternionf)`/`method_22907`。

**后果**：玩家朝向不是正北时，领地变成"轴对齐正方形"，而底图是转过的。中心位置对，四个角和边界全错，斜着走时能明显看到色块和地形对不上。

**改法**：在 `renderElement` 里给姿态补一次旋转（原点已经在元素中心，正好绕中心转）：

```java
MinimapProcessor proc = session.getProcessor();
boolean northLocked = MinimapConfigClientUtils.getEffectiveNorthLocked(
        session.getConfiguredWidth() / 2, boxScaleOrZero);   // 与 renderMinimap 同一入口
double angleDeg = proc.getRenderer().getRenderAngle(northLocked);   // 单位：度
graphics.pose().multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) -angleDeg));
```
（`getEffectiveNorthLocked` 的实参组合与符号需要在游戏里对一次，见 T4；实在不想碰姿态，就至少把 `xaeroScale`/文档写成"仅北向上可用"，并判断 northLocked 为 false 时改用描点/不画填充。）

---

### S4【严重·不显示】"装了 Xaero"被当成"Xaero 正在画小地图"：Xaero 侧不画时，HUD 和 JourneyMap 都不画

**位置**：
- `MinimapRenderer.java:42-45`：`isModLoaded("xaerominimap") && XaeroClaimsBridge.isActive()` → 直接 return；
- `ClaimsOverlayManager.java:58-60`：`isModLoaded("xaerominimap")` → 直接 return，JM 永远不建 overlay；
- `XaeroClaimsBridge.java:30-31, 70-73`：`failed` 一旦置位就永久不再尝试。

**三种"什么都不显示"的场景**（都是真实可达的）：
1. 玩家在 Xaero 设置里关掉小地图（或服务端 fair-play / 快捷键隐藏）→ `renderMinimap` 不会被调用，我们的 `renderElement` 也就永远不跑，但 `isActive()` 仍是 true → HUD 被抑制，JM 被抑制，全灭。
2. 注册那一刻抛了任何 `Throwable`（Xaero 版本不匹配、`HudMod.INSTANCE` 尚未初始化、别的 mod 抢先改了 handler）→ `failed=true`，本局永久降级；同时 `MinimapRenderer` 里 `isActive()` 为 false，于是继续往下走 → 如果没装 JM，HUD 会显示（还行）；**如果同时装了 JM**，`jmMinimapActive` 默认为 true → HUD 也不画 → 全灭。
3. 用户装了 Xaero 但把它调成了"仅全屏地图"之类（Xaero 的小地图可以整体关闭）→ 同 1。

**改法**（最简单且能覆盖全部三种）：给 bridge 加一个"最近一次真正渲染的时间戳"，由 `renderElement`（或 `begin`）写入；`MinimapRenderer` 改成"仅当 `isActive()` 且 `now - lastRenderAt < 500ms` 时才让位"：

```java
public static volatile long lastRenderedAt;
// renderElement / begin 里：lastRenderedAt = System.currentTimeMillis();
// MinimapRenderer:
boolean xaeroDrawing = XaeroClaimsBridge.isActive()
        && System.currentTimeMillis() - XaeroClaimsBridge.lastRenderedAt < 500L;
if (xaeroDrawing) return;
```
同时把 `ClaimsOverlayManager.tick` 的早退条件从"装了 Xaero"改成"Xaero 真的在画"（同一个判据），这样上面三种场景都会自动回落到 JM/HUD，也不会出现两份叠着画。

---

## 三、中等问题

### M1 每个盒子 5 次立即绘制，且每帧全量重建 + 排序

**位置**：`XaeroClaimsBridge.java:246-251`（1 次底 + 4 次边框）、`96-100`、`276-293`

`MinimapElementGraphics.fill` → `ImmediateRenderUtil.coloredRectangle(Matrix4f,...)` → 末尾 `drawImmediateMeshData(...)`：**每次 fill 都是一次独立的网格提交/绘制**。512 个盒子 = 2560 次 draw/frame。叠加 S2（不剔除），远处领地也照画，实际数字很容易打满。

**改法**：
1. 先做 S2 的视口剔除（收益最大，通常从几百个降到个位数）。
2. 小领地只画边框不画底（或反过来），把 5 次降到 1–4 次。
3. 想彻底解决就自己拿 `graphics.getBufferSource()` + `graphics.pose()` 拼一个批次，最后 `graphics.flush()` 一次提交——但那要自己指定 `RenderPipeline`，工作量和风险都上一个台阶，第一版不建议。
4. `ClaimCacheIndex.collect` 里 `Entry`→`ClaimBox` 拷了两遍（`98-100`），可以直接把 `ClaimCodec.Box` 喂给 provider，省一半分配。
5. 排序本身不是问题（≤512 条、每帧一次，微不足道），但"按盒中心距离"排序会在超大领地时误裁（见 L6）。

### M2 注册失败一次 = 本局永久放弃，且日志没有堆栈

**位置**：`XaeroClaimsBridge.java:70-73`

`catch (Throwable throwable) { failed = true; LOGGER.warn("... " + throwable); }` —— 只拼了 `toString()`，没有堆栈；之后 `tick` 里 `if (registered || failed) return;` 直接退出。

**改法**：`LOGGER.warn("failed to register xaero claims overlay", throwable)`；把 `failed` 换成"失败次数上限（比如 5 次）+ 世界切换时清零"；`ClientPlayConnectionEvents.JOIN` 的 `resetSession()` 里顺手 `XaeroClaimsBridge.reset()`（若注册已成功则保持幂等）。

### M3 M 键（`minimapVisible`）对 Xaero 后端完全无效

**位置**：`MinimapRenderer.java:27-33,50`（M 键只切 `visible`，Xaero 分支在它之前 return）、`XaeroClaimsBridge.java` 全文无 `minimapVisible` 引用

玩家按 M 关掉小地图，自带 HUD 会消失，但 Xaero 上的领地层照画。行为不一致。

**改法**：`begin()` 或 `renderElement` 里判 `if (!MinimapRenderer.isVisible()) return false;`；并在文档里写清 M 键对三个后端都生效。

### M4 `xaeroScale` 的语义、默认值、范围都没有依据，而且没有文档

**位置**：`ClientConfig.java:19,79-81,93`

默认 1.0、允许 0.05–16.0。在 S1 的修法落地前，这个值只是"把错误的比例再乘一个数"；落地后它应该是"用户微调倍率"，范围 0.25–4.0 就够了。另外 `README.md` 与 `使用说明-领地小地图模组.md` 里**一次都没提** `xaeroScale`（文档只写了 `minimapSize/minimapMargin/claimAlpha`，见 `使用说明` 第 21-26 行）。

**改法**：改成 `xaeroScaleMultiplier`（默认 1.0，0.25–4.0），文档补一行"Xaero 后端专用，按需微调"。

### M5 构建对 `client/libs/` 的依赖没有友好报错

**位置**：`client/build.gradle:28-29`

```groovy
modCompileOnly files("libs/xaerominimap-fabric-1.21.11-26.6.2.jar")
modCompileOnly files("libs/xaerolib-fabric-1.21.11-1.8.5.jar")
```
文件名写死版本号。别人 clone 后没有这两个 jar，构建会以 "package xaero.common does not exist" 这类**误导性**错误失败（看起来像源码问题）。README 第 85 行只说"把 Xaero 的 jar 放进 `client/libs/`"，没给文件名/版本/来源。

**改法**（二选一）：
```groovy
def xaeroJars = fileTree("libs") { include "xaerominimap-fabric-*.jar", "xaerolib-fabric-*.jar" }
if (xaeroJars.files.size() < 2) {
    throw new GradleException("缺少 Xaero 编译依赖：请把 xaerominimap-fabric-1.21.11-26.6.2.jar 与 xaerolib-fabric-1.21.11-1.8.5.jar 放进 client/libs/（见 README）")
}
dependencies { modCompileOnly xaeroJars }
```
并在 README 写清两个 jar 的**精确文件名、版本、获取方式**和"该目录不入库"的原因（ARR 不允许分发）。

### M6 已构建产物与源码不同步（交付的 jar 里缺 `suggests.xaerominimap`）

**位置**：`client/src/main/resources/fabric.mod.json:14-17` vs `client/build/libs/pmxxlclaims-1.0.0.jar` / `pmxxlclaims-mod-1.0.0.jar`

实测：源码里 `suggests` 有 `journeymap` + `xaerominimap` 两条；两个已构建 jar 里的 `fabric.mod.json` **只有 journeymap**（jar 时间 18:19:52，资源文件 18:20:13，晚 21 秒）。另外 `client/build/libs/pmxxlclaims-1.0.0-sources.jar` 是 22 字节的空包。

**改法**：出包前重新 `gradlew build`，并核对 `unzip -p build/libs/*.jar fabric.mod.json`；顺手查一下 `sourcesJar` 为什么是空的（`withSourcesJar()` 与自定义 `jar{}` 块的交互）。

### M7 文档与实现不一致（用户视角）

**位置**：`使用说明-领地小地图模组.md:12,17-18`、`README.md:85-88`

使用说明只写"装了 JourneyMap 用 JM，否则用自带 HUD"，完全没有 Xaero；而实际优先级是 Xaero > JM > HUD。玩家装了 Xaero 会发现"小地图上确实有领地，但 M 键关不掉、样式和自带 HUD 不一样、调 zoom 大小会变"，没有文档可查。README 只有三段话，也没写"Xaero 小地图被关时怎么办"。

**改法**：使用说明加一节"三个后端"：优先级、各自前提（Xaero 小地图必须处于显示状态）、`xaeroScale`、已知限制（旋转/裁剪/大领地）。

### M8 渲染顺序：与内置元素并列，且加在最后 → 半透明色块盖在雷达/路径点图标上

**位置**：`XaeroClaimsBridge.java:218-222`（未覆写 `getOrder()`）

`MinimapElementRenderer.getOrder()` 默认 0；`handler.add()` 用 `Collections.sort`（稳定排序），我们的 renderer 最后加入 → 同序时排在最后 → 后绘制。fill 的 alpha 默认 `0x66`，会把玩家图标/路径点染一层色。

**改法**：`@Override public int getOrder() { return -100; }` 让它先画（垫在图标下面）。需要先确认 over-map handler 里确实有别的元素（雷达/路径点是否走 OVER_MINIMAP 取决于 Xaero 设置），T6 一并验证。

### M9 `suggests` 的版本下限比编译版本低

**位置**：`fabric.mod.json:16`

写的是 `>=1.21.11-26.6.0`，但代码是对着 `26.6.2` 编译的。若 26.6.0 里 `MinimapElementRenderInfo`/`MinimapElementGraphics` 有差异，用户那边会在注册时抛 `NoSuchMethodError/NoClassDefFoundError`，然后被 M2 的永久 `failed` 吞掉。

**改法**：改成 `>=1.21.11-26.6.2`；或者确认 26.6.0 也有这些方法后再放宽。

---

## 四、轻微问题

- **L1** `XaeroClaimsBridge.java:225-227`：`renderElement` 的形参名与实际语义不符。真实签名是 `(E, boolean first, boolean second=clipped, double depth, float optionalScale, double subPixelX, double subPixelY, info, graphics, buffers)`（`OverMapRendererHandler.transformAndRenderForRenderer` 字节码 87-119 + XaeroPlus `@Local(argsOnly=true) LocalFloatRef optionalScaleRef` 可证）。当前写成 `x/y/z/depth` 且全都未使用，类型对所以能编译，但会误导后续维护者。
- **L2** `XaeroClaimsBridge.java:33,235-238`：`lastLoggedScale` 用了 `volatile`（只在一个线程读写，不需要），且"比例变化就打 INFO"在换成 zoom 派生后会随每次缩放刷屏。改 DEBUG 或只在首次打印。
- **L3** `XaeroClaimsBridge.java:273-293`：`ClaimCacheIndex.Entry` 与 `ClaimCodec.Box` 字段完全重复；`begin()` 里又把 `Entry` 拷成 `ClaimBox`，每帧多一轮对象。
- **L4** `XaeroClaimsBridge.java:133-135`：`getRenderY` 固定返回 64.0。OVER_MINIMAP 的 `translatePosition` 只吃 X/Z，Y 不参与，属于无害噪声；建议注释写明。
- **L5** `XaeroClaimsBridge.java:143-180`：`getInteractionBox*/getRenderBox*` 全返 0。因为 `isInteractable()` 返回 false，目前不会触发 0 尺寸的命中框；将来若想开点击/悬浮，这里必须补真实尺寸。
- **L6** `XaeroClaimsBridge.java:296-299`：`dist2` 用"盒子中心"算距离。一个 500×500 的领地如果中心很远但边缘就在脚下，会被排到后面甚至被 512 上限裁掉。改成"到矩形的最近距离"更合理（`max(0, minX-px, px-maxX)` 这类）。
- **L7** `MinimapRenderer.java:111`：`claim.color() == 0` 的调色板兜底在 Xaero 侧没有对应实现。当前服务端 `ClaimService.colorOf()` 永远给非 0 颜色，所以两边都不会触发；但这是一处"两边行为不一致"的隐患，建议把取色逻辑抽成一个公共方法，两个后端共用。
- **L8** `ClaimsOverlayManager.java:34`：`jmMinimapActive` 初值 true，JM 插件 `initialize` 之前 HUD 就被抑制了（启动瞬间的闪烁）。影响很小，可改为 false 直到 JM 首次上报。

---

## 五、需要进游戏实测才能确认的清单

> 下面这些**现有证据只能推到"极可能"，要下最终结论必须实机**。建议按顺序做，每条都记下 GUI scale / zoom / 小地图尺寸 / 形状（圆/方）/ 是否旋转。

- **T1 越界到底画到哪**：把一块领地放到视野外 100+ 格，观察小地图边缘。要确认的是：Xaero 的 OVER_MINIMAP 层在 1.21.9+ 的 PiP 纹理里是否被`ScissorStack`限死（如果限，最坏只是方形四角；如果不限，会糊到整个屏幕）。**这条直接决定 S2 的严重度上限。**
- **T2 比例标定**：站在一块已知尺寸的领地中心（比如 32×32），分别在 zoom 索引 1/2/3、小地图缩放 0.5/1.0/1.5 下截图，用区块网格量"一格 = 多少像素"，验证 §1.2 的公式；同时用下界同一块地验证 8 倍问题（S1）。
- **T3 GUI scale 影响**：GUI scale 1/2/3、小地图 size 小/中/大、放大版小地图（enlarged）各看一次，确认姿态里没有额外的整体缩放。
- **T4 旋转地图**：`NORTH_LOCKED=false`（默认）朝东/朝南走，确认色块是否始终轴对齐（S3）；若按 §S3 改了姿态，确认旋转方向与角度符号正确（大概率先试负角度）。
- **T5 洞穴模式/下界**：`isCaveModeDisplayed` 为 true 时（下界/洞穴模式手动切换）色块是否还压在正确位置；下界的 8 倍问题是否如预期。
- **T6 与内置元素的前后关系**：开雷达（玩家图标）+ 放一个路径点，确认半透明色块是盖住它们还是被它们盖住（M8），以及 `getOrder()` 改动后的效果。
- **T7 空场景**：Xaero 小地图在设置里关掉 / 按快捷键隐藏 / F1 隐藏 GUI / 打开任意容器界面，逐一确认此时"有没有领地可看"（S4）。同时验证"Xaero+JM 同时装"时是否真的出现了全灭。
- **T8 注册失败路径**：临时把 `libs` 换成旧版 Xaero（或人为让 `handler.add` 抛异常），确认日志是否有用、HUD 是否会接管（M2 + S4）。**不要在正式服上做这条。**
- **T9 性能**：找一块领地密集的区域（尽量逼近 512 盒），看 FPS 和帧时间；再对比加上 S2 剔除后、以及只画边框后的差异（M1）。
- **T10 交互**：鼠标悬停到色块上、在小地图上滚动缩放、拖拽小地图，确认没有因为 `getInteractionBox*=0` / `isInteractable=false` 产生异常或奇怪的命中框（L5）。

---

## 六、合规与构建核对（补充说明）

1. **没有混入第三方代码/jar**：
   - `git ls-files | grep -i -E "libs/|xaero"` 只返回 `client/src/main/java/.../xaero/XaeroClaimsBridge.java`（我们自己的源码）；
   - `pmxxl-claims-map/.gitignore` 里有 `client/libs/`；
   - `client/build/libs/pmxxlclaims-1.0.0.jar` 与根目录 `pmxxlclaims-mod-1.0.0.jar` 内**只有** `com/tianyaworld/pmxxlclaims/client/xaero/*.class`，没有 `xaero/common/**`、`xaero/hud/**`、`xaero/lib/**`。`modCompileOnly` 的用法正确（**不要**改成 `implementation`/`include`——Xaero 是 All Rights Reserved）。
   - 包名 `com.tianyaworld.pmxxlclaims.client.xaero` 与 Xaero 的 `xaero.*` 不冲突，安全。
2. **构建可复现性**见 M5；**产物与源码一致性**见 M6。
3. **运行期类加载**：`XaeroClaimsBridge` 的所有静态入口（`tick`/`isActive`）要么在 `isModLoaded` 之后，要么本身不解析 Xaero 类型，未安装 Xaero 时不会 `NoClassDefFoundError`。真正脆弱的是"装了但版本不对"——见 M2/M9，建议把 `catch` 的日志升级为带堆栈并加失败上限。

---

## 七、一句话总结

挂载点选得对、合规干净、生命周期基本正确；**但"比例"和"裁剪"这两件决定观感的事没有做对**——`backgroundCoordinateScale` 是维度系数不是 zoom，越界元素被 Xaero 钳到边缘而不是丢弃，地图默认又是旋转的。把 §S1–S4 四条改掉、按 §S2 加视口剔除，这个后端才能算可用。
