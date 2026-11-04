# 代码评审 6：Xaero 集成「修复复核」

评审日期：2026-11-04  
评审对象：上一轮（代码评审 5）S1–S4 修复后的 Xaero 后端  
- `client/src/main/java/com/tianyaworld/pmxxlclaims/client/xaero/XaeroClaimsBridge.java`（355 行，v2）
- `MinimapRenderer.java`、`jm/ClaimsOverlayManager.java`、`ClientConfig.java`、`client/build.gradle`、`fabric.mod.json`
- 提交：`d583815 fix: xaero overlay scale, clipping, rotation, suppression per review`（工作树干净）

评审方式：**只读**。未修改源码、未运行构建；报告是唯一写入。  
证据：Xaero 26.6.2 本体 `client/libs/xaerominimap-fabric-1.21.11-26.6.2.jar` 的 javap 字节码、xaerolib 1.8.5、已提交源码、已构建产物。行号均指当前提交。

---

## 0. 一句话结论

**公式修对了，机制没修对。** S1 是真修复（与 Xaero 自己喂给 `translatePosition` 的值逐位一致）；S2 **完全没生效**（`if (clipped)` 判的是 Xaero 恒传 `false` 的第一个 boolean）；S3 加了旋转但**角度系统性差 90°**且不判 northLocked；S4 加了新鲜度判断，但时间戳只在"真的画了至少一个色块"时才刷新，于是**没有领地的维度/荒野里自带 HUD 会和 Xaero 小地图同时画**。

---

## 1. 上一轮 4 条严重问题的修复判定

| # | 问题 | 判定 | 一句话理由 |
|---|---|---|---|
| S1 | 缩放取错（用了 `backgroundCoordinateScale`） | **真修复** | `zoom * getMinimapScale() / 2.0` 与 Xaero 内部传给 `prepareRender` 的 zoom 逐项同源，已字节码核实 |
| S2 | 无裁剪 | **未修复（空操作）** | 检查的是第 1 个 boolean，handler 对它恒传 `iconst_0`；真 clipped 是第 2 个参数 `second` |
| S3 | 旋转不跟转 | **部分修复** | 旋转确实生效了，但角度用了 `a`，正确值是 `a - 90°`；且 `getRenderAngle(false)` 写死，northLocked 模式下必错 |
| S4 | Xaero 未真正渲染时仍抑制 HUD/JM | **部分修复** | 1 秒新鲜度+重试结构对了，但时间戳写在 `renderElement` 而不是 `begin`，0 个元素时永远不刷新 → HUD 抢画 |

**仍有严重问题（3 条）**，详见 §2 的 S-1 / S-2 / S-3。S1 可以关闭。

---

### S1【真修复】缩放公式

**现在的代码**（`XaeroClaimsBridge.java:92-111`）：

```java
zoom    = session.getProcessor().getMinimapZoom();
uiScale = HudMod.INSTANCE.getSettings().getMinimapScale();
units   = zoom * uiScale / 2.0 * ClientConfig.xaeroScale();
```

**逐项核对（全部字节码实证）**

1. `param9` 确实是 minimapScale，且与 `getMinimapScale()` 是同一个值：
   - `xaero.hud.minimap.render.MinimapPipRenderer` 调 `MinimapProcessor.onRender(...)` 时第 6 个参数取 `MinimapPipRenderState.getMinimapScale()`；
   - `onRender` 把它原样传到 `renderMinimap(..., float minimapScale, ...)`（`MinimapProcessor.onRender` 偏移 103-127：`fload 7`）；
   - `MinimapPipRenderState` 的 minimapScale 由 `xaero.hud.minimap.module.MinimapRenderer` 偏移 212-217 写入，来源是 `GuiUtils.getMinimapScale(cm)`；
   - 而 `ModSettings.getMinimapScale()` 的方法体**就是** `GuiUtils.getMinimapScale(HudMod.INSTANCE.getHudConfigs().getClientConfigManager())`（`xaero.common.settings.ModSettings` 偏移 0-12）。**两条路同一个值，实现没取错。**
2. `÷2.0` 不是拟合：`MinimapRenderer.renderMinimap` 偏移 1994-2006 就是
   `this.zoom * (double)fload9 / 2.0 → dstore 70`，这个值在偏移 2064-2082 作为 `zoom` 传给 `prepareRender`。我们复刻的是同一个表达式。
3. `zoom` 同一个：`this.zoom = processor.getMinimapZoom()`（偏移 108-117，且先 `updateZoom()`）；我们在同一帧稍后调用 `getMinimapZoom()`，读到的是同一字段。
4. 单位也一致：`graphics.pose()` 返回的就是 `Minimap.getMatrixStack()`，`translatePosition` 平移的是同一个栈，所以"1 个矩阵单位"对两边是同一个东西。

**结论**：S1 可以关闭。默认档（zoom≈1、UI scale 默认档）下约 `0.5 单位/格`，16×16 领地 ≈ 8×8 单位——这个量级和 Xaero 底图一致。  
**残留**：`unitsPerBlock()` 每个盒子调一次（每帧最多 512 次进 Xaero 内部取配置），且 `lastLoggedScale` 的 INFO 会随缩放动画每帧刷屏（见 M-3）。

---

### S2【未修复】`if (clipped) return false;` 判在了一个常量上

**位置**：`XaeroClaimsBridge.java:282-287`

```java
public boolean renderElement(ClaimBox box, boolean clipped, boolean second, double x, float y, double z,
                             double depth, ...) {
    if (clipped) { return false; }   // ← clipped 是第 1 个 boolean
```

**实证**：`xaero.hud.minimap.element.render.over.MinimapElementOverMapRendererHandler.transformAndRenderForRenderer`（唯一调用方）偏移 87-121：

```
 87: aload 8            // renderer
 89: aload_1            // E
 90: iconst_0           // ← 第 1 个 boolean：常量 false
 91: iload 21           // ← 第 2 个 boolean：translatePosition 的返回值（clipped）
 93: dload 11           // depth
 95: getfield optionalScale
100/106: partialTranslate[0] / [1]
111: aload 13 (info)  114: guiGraphics  117: aload 14 (buffers)
119: invokevirtual MinimapElementRenderer.renderElement:(...ZZDFDD...)Z
```

`iload 21` 的来源就在同一方法偏移 82：`invokestatic translatePosition(...)Z; istore 21`。

**反向印证**：Xaero 自己的 `PlayerTrackerMinimapElementRenderer.renderElement` 第一行是 `iload_3; ifne`（第 2 个参数=clipped，用于跳过 "wasRenderedOnRadar" 早退），而 `iload_2`（第 1 个参数）被拿去把图标缩到 `0.8f`——两个 boolean 语义完全不同。

**后果**：`clipped` 恒为 `false`，这行是死代码，**越界元素依然被 `translatePosition` 钳到边缘整块画出来**，和修复前一模一样。上一轮 S2 描述的全部现象（屏幕外 200 格的领地在边缘挂方框、色块糊出圆形小地图）**一条都没消失**。

**改法（一行）**

```java
public boolean renderElement(ClaimBox box, boolean highlighted, boolean clipped, double depth,
                             float optionalScale, double subPixelX, double subPixelY, ...) {
    if (clipped) { return false; }
```

同时把形参名改成真实语义（`depth / optionalScale / subPixelX / subPixelY`）——这次修错正是因为旧形参名 `x/y/z/depth` 把顺序带偏了。

**注意这条改法本身的副作用**（上一轮已提示，此处再确认）：`clipped` 的语义是"**元素中心**落在半视口外"，所以一块 200×200、中心在画面外但边缘就在脚下的领地会**整块消失**。想两全就得自己做裁剪（见 §2 S-3 的第二种改法）。

---

### S3【部分修复】旋转：方向对了一半，角度差 90°，且不判 northLocked

**定位**：`XaeroClaimsBridge.java:113-122`（`renderAngleDegrees()` → `getRenderAngle(false)`）、`300-306`（`rotationZ(toRadians(angle))`）。

先说结论：`pose.push()/multiplyPositionMatrix/pose.pop()` 这套**用法是对的**，旋转中心也正好是元素中心，不会污染后续元素：

- `MinimapElementGraphics.fill` 每次都现取矩阵：偏移 32-36 `pose() → peek() → getPositionMatrix()`，再交给 `ImmediateRenderUtil.coloredRectangle`。所以 push 后的旋转**生效**。
- `transformAndRenderForRenderer` 自己也有 push/pop（偏移 35 / 124）包住我们，我们的 push/pop 嵌套在里面，平衡。
- `multiplyPositionMatrix` = `peek().getPositionMatrix().mul(m)`，即 M = T·R，点先转后平移——正是"绕元素中心旋转"。`new Matrix4f().rotationZ(θ)` 是 `[[cos,-sin],[sin,cos]]`。

**但角度算错了 90°。**

推导（全部来自字节码）：

1. `renderMinimap` 偏移 176-207：
   ```
   a   = Math.toRadians(getRenderAngle(northLocked));
   ps  = Math.sin(PI - a);      // = sin a
   pc  = Math.cos(PI - a);      // = -cos a
   prepareRender(ps, pc, zoom, ...)   // 偏移 2064-2082
   ```
2. `translatePosition`（`MinimapElementOverMapRendererHandler` 偏移 3-33、313-321）：
   ```
   screenX = ps*dx - pc*dz
   screenY = pc*dx + ps*dz
   translate(round(screenX), round(screenY), 0)
   ```
   即世界偏移 `(u=dx, v=dz)` 到屏幕的线性映射是 `R = [[ps, -pc], [pc, ps]]`。
3. 我们的 fill 以 `u` 为横、`v` 为纵，所以姿态要补的旋转 θ 必须满足
   `cosθ = ps = sin a`、`sinθ = pc = -cos a` → **θ = a − 90°**。

代码用的是 `θ = a`，差一个恒定的 90°。

**两个可验证的推论**
- northLocked（`getRenderAngle(true) = 90.0`）时正确 θ = 0（地图本来就不转），代码却转了 `a = -90° - yaw`——**北向上模式下 100% 画歪**。
- 正方形领地差 90° 看不出来，所以"看起来好像修好了"；矩形领地（子领地常见）会宽高互换，一眼可见。

**改法**

```java
// 1) 用和 Xaero 同一入口算 northLocked
ClientConfigManager cm = HudMod.INSTANCE.getHudConfigs().getClientConfigManager();
int shape = cm.getEffective(MinimapProfiledConfigOptions.SHAPE);
boolean northLocked = MinimapConfigClientUtils.getEffectiveNorthLocked(
        session.getProcessor().getMinimapSize() / 2, shape);
// 2) 角度再减 90°
double deg = session.getProcessor().getRenderer().getRenderAngle(northLocked) - 90.0;
if (deg != 0.0) {
    pose.multiplyPositionMatrix(new Matrix4f().rotationZ((float) Math.toRadians(deg)));
}
```

（`getEffectiveNorthLocked(int,int)`、`getMinimapSize()`、`getRenderAngle(boolean)` 均 public，已 javap 确认；`SHAPE` 的取值方式与 `renderMinimap` 偏移 149-163 完全一致。）

顺带：现在 `if (angle != 0.0)` 这个门几乎永远为真（`-90-yaw` 极少正好 0），改成上面的写法后它在北向上模式下会正确变成 0，省一次矩阵乘法。

---

### S4【部分修复】`isRendering()` 的时间戳写错了位置 → 没有领地时 HUD 抢画

**位置**：`XaeroClaimsBridge.java:48-50`（判定）、`316`（时间戳）、`MinimapRenderer.java:42-45`、`ClaimsOverlayManager.java:59-72`

现在的判定是 `registered && !failed && now - lastRenderedAt < 1000`，方向是对的（比 `isActive()` 强）。**但 `lastRenderedAt` 只在 `renderElement` 里、且只在真的画了盒子时更新**：

```java
// 316 行，在 renderElement 末尾
lastRenderedAt = System.currentTimeMillis();
```

而 `renderElement` 只有在 provider 至少吐出一个元素时才会被调用：`MinimapElementRendererHandler.renderForRenderer` 偏移 55-73 是 `begin() → while(hasNext()) { ... }`，**0 个元素时 `begin` 照调，`renderElement` 一次都不调**。

**后果（新引入的严重问题）**：只要当前范围内没有领地（荒野、下界/末地、新服、`ClaimCache.isActive()` 掉线），`lastRenderedAt` 永远是旧值 → `isRendering()` 恒 false → `MinimapRenderer` 走完整流程，在屏幕上画出一整块 128×128 的自带小地图（含区块色块和边框），和 Xaero 的小地图**并排双显**。而"附近没有领地"恰恰是最常见的状态。

同一原因还会导致：进游戏第一秒、Xaero 那边一帧没画（切维度/重载）、所有盒子都被（未来修好的）裁剪掉时，HUD 都会插进来闪一下。

**改法（一行位置调整）**

```java
// ClaimProvider.begin() 第一行就写——begin 每帧必被调用，哪怕 0 个元素
@Override
public void begin(MinimapElementRenderLocation location, Object context) {
    lastRenderedAt = System.currentTimeMillis();
    ...
}
```

`renderElement` 里那行可以删（保留也无害）。这样"Xaero 这一帧真的走了我们的 provider"才等于"Xaero 在画"，语义才和 `isRendering()` 对得上。

**另外两处仍然没修（上一轮 M2/M3）**
- `attempts` 40 次 ×3 s = 120 s 后永久 `failed`，且 `resetSession()`（`PmxxlClaimsClient.java:55-58`）没有重置它。上一轮建议"失败上限+世界切换清零"，只做了一半（堆栈日志补上了，见 86-89）。
- M 键对 Xaero 完全无效：`MinimapRenderer.render` 在 `visible` 检查（50 行）之前就 `return` 了，bridge 也从不看 `MinimapRenderer.isVisible()`。

---

## 2. 本轮新发现的问题

### 严重

#### S-1【严重·新】无领地时自带 HUD 与 Xaero 小地图同时显示

即 §1 S4 的直接后果，`XaeroClaimsBridge.java:316` + `MinimapRenderer.java:42-45`。  
**症状**：站在没有领地的野外，右上角凭空多出一块 128×128 的深色小地图 + 区块色块 + `claims 0` 文字，和 Xaero 的小地图重叠/并排。走回领地时又消失——变成"来回闪"。  
**改法**：见 S4（时间戳挪到 `begin`）。改完后建议再补一条：`isRendering()` 成立时 HUD 连"面板底"都不画（现在是整段 return，已满足）。

#### S-2【严重·新】旋转角度差 90°，北向上模式必错

即 §1 S3。`XaeroClaimsBridge.java:303-306`。  
**症状**：地图随玩家旋转时矩形领地的宽高相对底图互换；用户在 Xaero 里开"北向上"后所有领地固定歪 90°（正方形领地看不出来，矩形子领地很明显）。  
**改法**：`getRenderAngle(northLocked) - 90.0`，见 S3。

#### S-3【严重·未修】裁剪仍是空操作，越界色块照旧挂在边缘

即 §1 S2。`XaeroClaimsBridge.java:285-287`。  
**改法 A（一行，先止血）**：`if (second) return false;`（连同改名）。代价：中心出画面但边缘可见的大领地整块消失。  
**改法 B（推荐，一次做对）**：把视口反变换回世界坐标做矩形求交——每帧算一次：
```
sx,sy 取 ±halfView   →  dx = ps*sx + pc*sy ; dz = -pc*sx + ps*sy    // R 是正交阵，逆=转置
对 4 个角求 dx/dz 的 min/max，得到世界系里的视口 AABB
用它与 claim box 求交，只画交集中的矩形（含描边；被切掉的那条边不画边框）
```
这样"部分可见"就是正确裁剪，而且比 `if (second)` 更省 draw call（每帧 4 角计算，不是每盒）。圆形小地图可先用内接正方形（半径/√2）做保守裁剪，四角宁可少画也不外溢。Xaero 本体里 `grep -ril scissor` 为 0，指望它兜底是不现实的。

### 中等

#### M-1 `getOrder() = 800` 方向反了：领地色块盖在路径点/玩家图标上面

**位置**：`XaeroClaimsBridge.java:276-279`  
**实证**：`MinimapElementRenderer.getOrder()` 默认 `iconst_0`，`compareTo` = `Integer.compare`，`handler.add()` 用 `Collections.sort` 升序，`render` 里按列表 0→n 顺序绘制（后画=盖在上面）。Xaero 内置的 over-map 渲染器 `WaypointMapRenderer.getOrder() = 100`（`PlayerTrackerMinimapElementRenderer` 也是 100）。  
**后果**：800 > 100 → 我们在路径点之后绘制，半透明填充（alpha 0x66）和白色描边会压住路径点图标、玩家朝向箭头。  
**注意**：上一轮 M8 建议 `-100` 是基于"同序时最后加入=最后绘制"的误判；其实**默认的 0 就已经在 100 之前、已经垫在图标下面了**，这次改动是从正确改成了错误。  
**改法**：`return 0;`（或 `-100`，如果还想垫在别的 order<0 的东西下面）。改完顺手确认 T-6。

#### M-2 `xaeroCleared` 只清一次，第二次让位时 JM 会和 Xaero 叠画

**位置**：`ClaimsOverlayManager.java:34,61-70`  
`xaeroCleared` 置 true 后**永不复位**。序列：无领地（`isRendering()` false）→ JM 重建并 `show()` → 回到有领地 → Xaero 接管 → 因为 `xaeroCleared` 仍是 true，**不再 `removeAll`** → JM 地图/小地图上留着旧 overlay，和 Xaero 同时画。现在的 S-1 让这个序列每走几步就会发生一次。  
**改法**：`else { xaeroCleared = false; }`，或者干脆去掉这个 flag（每次进入 Xaero 分支都调一次 `removeAll`，500 ms 节流已有）。

#### M-3 `units-per-block` 的 INFO 会随缩放动画每帧刷屏

**位置**：`XaeroClaimsBridge.java:289-292`  
`getMinimapZoom()` 是动画插值（`updateZoom()`），玩家滚轮缩放时 `units` 每帧都在变 → 每帧一条 INFO。上一轮 L2 就建议降 DEBUG，这次因为改了公式反而更明显。  
**改法**：`LOGGER.debug(...)`，或只在首次打印。

#### M-4 M 键对 Xaero 后端无效（上一轮 M3 未处理）

**位置**：`MinimapRenderer.java:42-45`（在 `visible` 判断之前 return）、bridge 全文无 `minimapVisible` 引用  
**后果**：按 M 关掉自带小地图后，Xaero 上的领地层照画；文档却写"M 键开关小地图"（`使用说明-领地小地图模组.md:17`）。  
**改法**：`renderElement`/provider 里 `if (!MinimapRenderer.isVisible()) return false;`（注意要放在 `begin` 里把列表清空，否则返回 false 不会减少绘制次数）。

#### M-5 注册失败 120 秒后永久放弃，且不随世界/重连重置

**位置**：`XaeroClaimsBridge.java:65-69,86-89`；`PmxxlClaimsClient.java:55-58`  
`attempts > 40` 直接 `failed = true`，没有 reset 入口；`resetSession()` 只清缓存。上一轮 M2 只补了堆栈日志。  
**改法**：加 `static void reset()`（`registered` 保留幂等），在 `JOIN` 里调；或把"给上限"改成"指数退避 + 世界切换清零"。

#### M-6 构建对 `client/libs/` 的依赖仍然没有友好报错（上一轮 M5 未处理）

**位置**：`client/build.gradle:28-29`  
仍是硬编码文件名，没有 `GradleException`。更麻烦的是：**仓库里根本没有 `client/libs/`**（`pmxxl-claims-map/client/` 只有 `build.gradle / gradle.properties / settings.gradle / src`；jar 实际放在工作区另一个 `client/libs`）。别人 clone 后直接 `gradle build` 会以 `package xaero.common does not exist` 失败，看不出缺文件。  
**改法**（这条不会误伤：`XaeroClaimsBridge` 无条件 import `xaero.*`，两个 jar 是**硬编译依赖**，没有"不用 Xaero 也能编"的场景）：
```groovy
def xaeroJars = fileTree("libs") { include "xaerominimap-fabric-*.jar", "xaerolib-fabric-*.jar" }
if (xaeroJars.files.size() < 2) {
    throw new GradleException("缺少 Xaero 编译依赖：请把 xaerominimap-fabric-1.21.11-26.6.2.jar 与 xaerolib-fabric-1.21.11-1.8.5.jar 放进 client/libs/（见 README §小地图后端）")
}
dependencies { modCompileOnly xaeroJars }
```
README:85 也建议写清两个 jar 的**精确文件名/版本/来源**。

#### M-7 性能：5 次即时绘制 × 512 个盒子 = 每帧最多 2560 次 GPU 提交，而且没有任何剔除

**位置**：`XaeroClaimsBridge.java:293-312`、`349-351`  
`MinimapElementGraphics.fill` → `ImmediateRenderUtil.coloredRectangle` → 末尾 `drawImmediateMeshData`（已由 xaerolib 1.8.5 字节码确认），每次 fill 是一次独立网格提交。  
叠加 S2 没生效（没有视口剔除），远处盒子照画。另外：
- `unitsPerBlock()` 每盒调用一次（内部要进 `BuiltInHudModules`/`HudMod` 取配置）；
- `new Matrix4f()` 每盒一次（512 次/帧的垃圾）。  
**改法**：① 按 S-3 改法 B 先做世界系裁剪（通常从几百降到个位数）；② `units`/角度/矩阵每帧算一次（放在 `begin` 或 `preRender` 缓到字段）；③ 512 上限降到 128–256（服务端半径 16 区块时 512 基本用不到）。

#### M-8 文档与实现不一致

- `使用说明-领地小地图模组.md:17`：说 M 键开关小地图——对 Xaero 后端无效（M-4）。
- `使用说明...:18`：说"Xaero 小地图没开/没画出来时，自带 HUD 会在 1 秒内接管"——实际"没有领地"时也会接管（S-1），玩家会以为是自己没进领地。
- `fabric.mod.json:16` 与 jar 内一致，但仍是 `>=1.21.11-26.6.0`（上一轮 M9 建议 `>=26.6.2`，代码是对 26.6.2 编的）。

### 轻微

- **L-1** `XaeroClaimsBridge.java:282-284`：`renderElement` 的形参名全是错的——真身是 `(E, boolean highlighted, boolean clipped, double depth, float optionalScale, double subPixelX, double subPixelY, info, graphics, buffers)`。这次 S2 修错就是被旧名字带偏的直接后果，优先改名。
- **L-2** `XaeroClaimsBridge.java:293-294`：宽度/高度用 `Math.round`，但没叠加 Xaero 给的 `subPixelX/subPixelY`（`partialTranslate`），快速移动时相对底图有 <1 px 的抖动。
- **L-3** `XaeroClaimsBridge.java:349-351`：512 上限按报文顺序截断，不按距离——JM 那边（`ClaimsOverlayManager.collect` 144-151）是按距离排序的，两边行为不一致。建议同样按"到矩形的最近距离"排序后再截断。
- **L-4** `XaeroClaimsBridge.java:134-154`：`begin` 在 `world == null` 或 `location != OVER_MINIMAP` 时把 `boxes` 清空，但**不重置 `cachedVersion/cachedWorld`**；若真触发，后续同版本同世界会一直空列表。目前该 handler 的 location 固定 `OVER_MINIMAP`（构造时 `getstatic OVER_MINIMAP`），实际走不到，属于埋雷。
- **L-5** `XaeroClaimsBridge.java:335-353`：`ClaimCacheIndex.Entry` 与 `ClaimCodec.Box` 字段完全重复，`collect` 里多拷一遍（上一轮 L3 未处理）。
- **L-6** `XaeroClaimsBridge.java:185-187`：`getRenderY` 恒 64.0，OVER_MINIMAP 只吃 X/Z，无害；加一行注释说明即可。
- **L-7** `ClientConfig.java:19,79-81`：`xaeroScale` 仍是 0.05–16.0（上一轮 M4 建议 0.25–4.0）；`save()` 会把它写进配置，但文档只给一句话，没说"这是微调系数、不是格数"。
- **L-8** `XaeroClaimsBridge.java:43,107-109`：`lastLoggedScale` 的 `volatile` 无必要（单渲染线程）；`MAX_SCALE = 64` 对 `zoom*scale/2` 永远够不着，属于无效保护。
- **L-9** `client/build/libs/pmxxlclaims-1.0.0-sources.jar` 仍是 22 字节空包（上一轮 M6 的尾巴），`withSourcesJar()` 与自定义 `jar{}` 的交互要看一眼。

---

## 3. 仍需进游戏实测的清单

> 下面每条都是"静态证据只能推到极可能"。建议每条都记录 GUI scale / Xaero zoom / 小地图尺寸 / 形状（圆/方）/ 是否北向上。

- **T-1（最高优先，验 S-1）**：走到完全没有领地的野外 / 进下界 / 进末地，看右上角是否冒出第二块自带小地图。改完 `begin` 时间戳后重测：应始终只有 Xaero 一块。
- **T-2（验 S-2 角度）**：造一块**长方形**领地（如 32×16），先在 Xaero 里关掉"北向上"、朝东/南/西走，看色块长边是否始终贴合底图；再打开"北向上"，看是否还歪 90°。修正后应两者都对。
- **T-3（验 S-3 裁剪）**：把领地放到视野外 100+ 格，看小地图边缘。要确认 1.21.9+ 的 PiP 纹理是否顺手限死了绘制范围（Xaero 里没有任何 scissor，所以大概率会溢出到方形/圆形边界外，甚至整个 GUI 层）。
- **T-4（验 S-3 改法 B）**：站在 200×200 大领地的角落/边缘，确认裁剪后只剩画面内那一块，且被切掉的边不画描边。
- **T-5（验 M-1 层级）**：放一个路径点 + 开雷达玩家图标，确认领地色块在图标**下面**；改 `getOrder()` 前后各看一次。
- **T-6（验 S1 标定）**：站在已知尺寸领地中心，Xaero zoom 索引 1/2/3 × UI scale 0.5/1.0/1.5 各截一张，用区块网格量"一格 = 多少像素"，与 `zoom*scale/2` 对账（顺带覆盖 GUI scale 1/2/3、圆/方两种形状、放大版小地图）。
- **T-7（验 M-4）**：按 M，确认 Xaero 上的领地层是否跟着消失；再按一次恢复。
- **T-8（验 M-2）**：Xaero + JourneyMap 同时装，在有领地/无领地之间来回走 4–5 次，看 JM 地图上是否残留旧 overlay、是否两份同时出现。
- **T-9（验 M-5）**：临时换旧版 Xaero（或人为让 `handler.add` 抛异常），确认日志有堆栈、HUD 能接管、且 2 分钟后换世界能恢复。**不要在正式服做。**
- **T-10（验 M-7）**：找领地密集区域（逼近 512 盒）量帧时间；再对比裁剪后、以及只画边框的差异。
- **T-11（验 L-2）**：高速飞行时盯着领地边缘，看是否有 1 px 抖动/嵌顿。
- **T-12（顺带）**：`ClaimCache.isActive()` 是"60 秒没有新包就认为失效"，服务端 10 秒心跳应该能兜住；但如果服务端心跳被关掉，60 秒后领地会整片消失并同时触发 S-1（HUD 抢画）。实测时留意一下。

---

## 4. 仓库 / 产物 / 合规核对

| 项 | 结论 | 证据 |
|---|---|---|
| 工作树 | **干净** | `git status --porcelain` 空；HEAD = `d583815` |
| 源码与仓库一致 | **一致** | 工作区 `client/src` 与 `pmxxl-claims-map/client/src` `diff -r` 无差异 |
| 产物与源码一致 | **一致** | 根 `pmxxlclaims-mod-1.0.0.jar` 与 `client/build/libs/pmxxlclaims-1.0.0.jar` `cmp` 完全相同；jar 内 `XaeroClaimsBridge$ClaimElementRenderer` 已含 `sipush 800` / `rotationZ` / `lastRenderedAt`，是 v2 |
| `fabric.mod.json` | **已同步**（上一轮 M6 关闭） | 源码与 jar 内都有 `"xaerominimap": ">=1.21.11-26.6.0"`；版本下限仍偏低（M-8） |
| 合规 | **干净** | `git ls-files` 只命中我们自己的 `client/src/.../xaero/XaeroClaimsBridge.java`；`.gitignore` 有 `client/libs/`；jar 里 `xaero/common|hud|lib` 计数 = 0，只有 `com/tianyaworld/.../xaero/*.class` |
| 构建可复现 | **有硬伤** | 见 M-6：硬编码文件名 + 仓库内无 `client/libs` + 无友好报错 |

---

## 5. 修复优先级建议

1. **S2**：`if (second) return false;` + 形参改名（一行，先让"边缘挂色块"消失）。
2. **S4 / S-1**：`lastRenderedAt` 挪进 `ClaimProvider.begin()`（一行，去掉双小地图）。
3. **S3**：`getRenderAngle(northLocked) - 90.0` + 用 `getEffectiveNorthLocked`（北向上模式现在是必错的）。
4. **M-1**：`getOrder()` 改回 0。
5. **M-6**：`GradleException` + README 写清文件名（否则别人根本编不过）。
6. 其余按 M-2 → M-3 → M-4 → M-5 → M-7 顺序。
7. 最后再做 S-3 改法 B（世界系 AABB 裁剪），把"部分可见"做对，顺带把 draw call 降一个数量级。

---

## 6. 附：本次复核用到的关键字节码位置

| 主题 | 类.方法 | 偏移 |
|---|---|---|
| renderElement 真签名与第 1/第 2 boolean 来源 | `over.MinimapElementOverMapRendererHandler.transformAndRenderForRenderer` | 87-121（`iconst_0` / `iload 21`） |
| clipped = translatePosition 返回值 | 同上 | 82（`invokestatic translatePosition … istore 21`） |
| clipped 语义（中心出视口） | `MinimapElementOverMapRendererHandler.translatePosition` | 43-279 |
| Xaero 自己怎么用这两个 boolean | `player.tracker.PlayerTrackerMinimapElementRenderer.renderElement` | 0（`iload_3`）、261-273（`iload_2`→0.8f） |
| 角度 → ps/pc | `common.minimap.render.MinimapRenderer.renderMinimap` | 176-207 |
| zoom×minimapScale/2 | 同上 | 1994-2006，传给 prepareRender 在 2064-2082 |
| getRenderAngle | `common.minimap.render.MinimapRenderer.getRenderAngle` | 0-12 |
| minimapScale 来源 | `hud.minimap.render.MinimapPipRenderer` + `hud.minimap.module.MinimapRenderer` + `common.settings.ModSettings.getMinimapScale` | 47-51 / 212-217 / 0-12 |
| fill 每次现取矩阵 | `element.render.MinimapElementGraphics.fill(RenderPipeline,…)` | 32-55 |
| 每 fill 一次 draw | xaerolib `ImmediateRenderUtil.coloredRectangle` | 171-187 |
| 每帧必调 begin（0 元素也调） | `element.render.MinimapElementRendererHandler.renderForRenderer` | 55-73 |
| order 默认与内置值 | `MinimapElementRenderer.getOrder` / `WaypointMapRenderer.getOrder` | `iconst_0` / `bipush 100` |
