# 计划：小地图显示附近领地（pmxxl）

## 1. 目标与边界

1. 唯一目标：玩家小地图上渲染附近领地的范围（半透明色块 + 领地名字）。
2. 不做的：传送、领地编辑、点击交互、全服领地图、任何写操作。
3. 分层硬约束：客户端模组只负责渲染与输入；领地数据与判定全部由服务端插件提供；客户端改包不能伪造任何东西。
4. 只显示玩家附近，远处不显示（服务端按半径过滤，客户端只画收到的）。

## 2. 事实基线（已验证）

1. 服务端：Purpur 1.21.11，插件端。领地插件 Residence 5.1.6.2（原版未魔改），依赖 CMILib。
2. Residence 数据模型：ClaimedResidence = 树（parent + Map<String,CuboidArea> areas + Map<String,ClaimedResidence> subzones），几何是轴对齐长方体，CuboidArea 有 getLowVector()/getHighVector()/getWorldName()。
3. 附近查询有现成索引，不需要加载区块：ResidenceManager.getResidences(String worldName, List<ChunkRef>)，ChunkRef 有 public 构造 (int x, int z)。
4. 变更事件齐全：ResidenceCreationEvent / ResidenceDeleteEvent / ResidenceSizeChangeEvent / ResidenceRenameEvent / ResidenceOwnerChangeEvent / ResidenceAreaAddEvent / ResidenceAreaDeleteEvent。
5. 客户端：Fabric 1.21.11（Fabric Loader 0.20.1），现有模组里没有任何小地图，需要自研底图。
6. 模板与代码规范：本地已下载 pmxxl/ItemAction 与 TianYa-DAOQuestPlus 两份源码，Maven + Java 21 + spigot-api provided + shade，包结构 api/command/event/gui/hook/manager/model/util，plugin.yml api-version 1.21 + 中文 prefix。团队要求：尽量不用反射。

## 3. 工程布局（都放工作目录）

1. server/ —— 服务端插件（Maven）
   pom.xml
   src/main/java/com/tianyaworld/pmxxlclaims/PmxxlClaims.java
   .../claim/{ClaimIndex,ClaimSnapshot,ClaimPublisher}.java
   .../net/ClaimCodec.java
   .../listener/{PlayerMoveListener,ResidenceListener}.java
   .../command/ClaimsMapCommand.java
   src/main/resources/{plugin.yml,config.yml}
2. client/ —— 客户端模组（Gradle + Fabric Loom）
   build.gradle / gradle.properties / settings.gradle
   src/main/java/com/tianyaworld/pmxxlclaims/client/{PmxxlClaimsClient,net/ClaimPayload,claim/ClaimCache,render/{MinimapRenderer,ClaimOverlay,ChunkColorCache},config/ClaimsClientConfig}.java
   src/main/resources/{fabric.mod.json,pmxxlclaims.mixins.json}

## 4. 协议（v1，单向 S2C）

1. 频道：pmxxl:claims（两端字符串必须完全一致）。
2. 包头：magic "PMC1"（4 字节）+ flags(u8，bit0=deflate)+ count(varint)。
3. 每条领地：id(varint) + name(utf8) + owner(utf8) + color(i32 ARGB) + flags(u8，bit0=子领地/ bit1=自己的) + areaCount(varint) + 每个区域 (worldIdx u8 + minX/minY/minZ/maxX/maxY/maxZ，X/Z 用 i32，Y 用 i16)。
4. 大于 1024 字节时用 Deflate 压缩再发，flags 标记。
5. 触发：玩家跨 chunk（或水平位移 >= 8 格）重算半径内领地表；内容哈希未变则不发；进服/换世界全量；领地变更事件后标记脏，下一次触发重发。
6. 没有任何 C2S 包。

## 5. 服务端实现要点

1. 半径默认 16 chunk（256 格），config 可调，钳制 4..48。
2. 半径内查询：以玩家所在 chunk 为中心生成 (2R+1)^2 个 ChunkRef，调 getResidences(worldName, refs)。
3. 展开子领地：主领地 + 其 subzones 一并下发，用 parentId 与 flags 区分层级。
4. 颜色：按 owner UUID 哈希生成稳定色，或从 config 里按 owner 覆盖；透明度由客户端配置控制。
5. 节流：PlayerMoveEvent 里只判 chunk 变化，另有 200ms 冷却；所有 Residence 读取与发包都在主线程。
6. 不改 Residence 本体，不加反射，只用公开 API。
7. 调试命令：/claimsmap debug（打印某玩家半径内条目数与耗时）、/claimsmap reload。

## 6. 客户端实现要点

1. 收包：自定义 CustomPayload（record + Codec），ClientPlayNetworking.registerGlobalReceiver 收，解压后写入 ClaimCache。
2. 缓存：按世界 + chunk 分桶，进包替换该批数据，离开半径的自动过期（带 TTL 或整表替换）。
3. 渲染阶段 A（先跑通链路）：在世界里画领地包围盒底面线框，验证坐标与颜色正确。
4. 渲染阶段 B（目标）：自研极简小地图
   ChunkColorCache：每个 chunk 取 4x4 采样点，取最高非空方块的颜色求平均，缓存成 16x16 像素块；
   MinimapRenderer：右上角 128x128 窗口，每像素 1 格，支持 1/2/4 倍缩放，北向上，玩家箭头居中；
   ClaimOverlay：把每个 CuboidArea 的 XZ 范围投影成地图矩形，填充半透明色 + 描边 + 居中名字（超出窗口裁剪）；
   开关：默认 M 键，配置项含位置、大小、缩放、透明度、是否显示所有权名字。
5. 与现有 xiuxiankeypress 完全独立，单独一个模组，避免耦合。

## 7. 构建与部署

1. 服务端：JAVA_HOME 指向本机 JDK22（D:\MCLDownload\ext\jre-v64-220420\jdk22），用 IDEA 自带 Maven（C:\Program Files\JetBrains\IntelliJ IDEA 2024.2.4\plugins\maven\lib\maven3\bin\mvn.cmd）执行 clean package。
2. Residence jar 用 mvn install:install-file 装进本地仓库（scope=provided），不提交进工程。
3. 产物 server/target/pmxxlclaims-1.0.0.jar 丢服务器 plugins/。
4. 客户端：gradle wrapper（首次联网下载 Gradle 与 Loom 依赖），JDK21（本机 ~/.gradle/jdks 已有 adoptium 21），产物 build/libs/pmxxlclaims-1.0.0.jar 丢客户端 mods/。

## 8. 验收清单

1. 服务端启动日志出现 PmxxlClaims enabled + Residence hook ok。
2. /claimsmap debug 能打印半径内领地表（服务端侧数据正确）。
3. 客户端日志/F3 显示 recv 包数与 claim 条目数非 0。
4. 站在领地边界，地图矩形与 F3 chunk 边界对齐，误差 <= 1 格。
5. 走出 16 chunk 后，远处领地从小地图消失。
6. 新建/删除/改名领地，5 秒内地图更新。
7. 抓包确认只有 S2C 数据，没有 C2S 请求。
8. 客户端移除模组后，服务端无任何报错。

## 9. 需要拍板的 4 项

1. 半径默认值是否用 16 chunk（256 格）。
2. 是否显示别人领地的名字，以及名字格式（建议：领地名字 + 第二行归属者，可配置关闭）。
3. 底图路线：先自研极简（推荐，不依赖第三方 APIs），JourneyMap API 作为后续可选。
4. 命名确认：插件 PmxxlClaims / 模组 pmxxlclaims / 频道 pmxxl:claims。

## 10. 风险

1. 1.21.11 的 Fabric Loom 首次构建要联网拉依赖，可能耗时较长。
2. Residence 与 Purpur 1.21.11 的兼容性未知（它 folia-supported，理论上无碍），必要时先用 /claimsmap debug 验证查询。
3. 小地图渲染是本项目最大工作量，估占总工时 60%。
4. 频道名或编解码不一致的表现是「收不到包」，需要两端日志同时看。

## 11. 排期（今晚可跑的量）

1. 阶段 1（30 分钟）：两个工程骨架 + 协议文档 + 编译通过。
2. 阶段 2（60 分钟）：服务端查询/节流/下发 + 调试命令 + 服务端自测。
3. 阶段 3（60 分钟）：客户端收包 + 3D 线框验证链路。
4. 阶段 4（120 分钟以上）：小地图 + 领地叠加 + 配置项。
5. 阶段 5（30 分钟）：打包、部署说明、验收清单执行记录。

## 12. 决策记录（2026-10-29 用户拍板）

1. 半径：默认 16 chunk，按计划执行。
2. 名字：只显示领地名（不发拥有者名字，send-owner 默认 false，配置可开）。
3. 底图：自研；允许借鉴开源实现，但必须先核许可证。
4. 命名：插件 PmxxlClaims，模组 pmxxlclaims，频道 pmxxl:claims。
5. 交付物形态：客户端玩家侧只装一个 Fabric 模组 jar；服务端只放一个 Bukkit 插件 jar。两端运行时不同，无法合并成单一文件，交付为「一个插件 + 一个模组 + 部署说明」。

## 13. 开源借鉴的许可证核查结果（2026-10-29）

1. 1.21.11 上没有可直接抄的宽松许可独立小地图：Xaero's Minimap、Xaero's World Map、JourneyMap、VoxelMap-Updated 全是 All-Rights-Reserved；Open Parties and Claims 是 LGPL-3.0（抄进闭源模组会被传染）。
2. 可安全借鉴（MIT）的两份：BlueMap（服务端网页地图，区块取色与光照/高度着色思路）、MapFrontiers（JourneyMap 附加，区域多边形判定与绘制思路）。二者都只借鉴算法思路，不整体抄代码；若抄具体代码段，需在文件头保留其 MIT 版权声明。
3. 因此底图仍按自研实现（区块采样平均色 + 领地矩形叠加），不引入任何 GPL/LGPL 依赖。
## 14. 进度（2026-10-29 22:45）

1. 阶段 1 完成：两个工程骨架已建，编译均通过。
2. 阶段 2 完成：服务端查询、半径过滤、节流（每 2 秒巡检 + 跨区块立即触发 + 事件触发）、增量哈希去重、调测命令 /claimsmap 都已实现并编译通过。
3. 阶段 3 与阶段 4 的收包部分完成：客户端 payload 注册、解码、缓存、HUD 元素挂载（HudElementRegistry，1.21.11 已移除 HudRenderCallback）全部编译通过；小地图绘制（区块平均色底图 + 领地填充描边名字 + 玩家箭头 + M 开关）已实现。
4. 协议往返测试通过（服务端 encode → 客户端 decode，中文/负坐标/远坐标/子领地 flag 全对）。
5. 待办：运行时联调（需要真实 Purpur 服务端 + 客户端进服）、底图精细化、脚本化部署包。
6. 工具链记录：Loom 1.19.3 需要 JVM 25，已下载 Temurin JDK 25 到临时目录；Gradle 9.8.2 同理；yarn 1.21.11+build.6、loader 0.20.1、fabric-api 0.141.6+1.21.11。
## 15. 架构调整（2026-10-30）：改为纯客户端模组

1. 用户新要求：最终只要一个客户端模组，不要搭配服务端插件。
2. 硬约束：领地数据只在服务端 Residence 里，客户端协议不携带，所以纯客户端只能从「服务端已暴露给客户端的信息」取数。
3. 采用方案：解析 /res current 与 /res show 的聊天回显（其中含 X/Y/Z 两组角坐标），本地记录 + 小地图渲染；K 键主动扫描，被动解析任何 /res show 输出；数据存 config/pmxxlclaims-claims.json。
4. 已移除网络 payload 相关的 ClaimPayload/ClaimCodec/ClaimCache，模组不再注册任何自定义频道，也不再需要服务端配合。
5. 服务端插件 PmxxlClaims 源码与 jar 保留在 server/ 下，仅作为将来「全量下发」的可选方案，当前交付不需要它。
6. 待用户确认：是否要加自动扫描开关；服务器是否装了 Pl3xMap/dynmap（可做第三个数据源，一次拉全）。
## 16. 最终架构（2026-10-30 用户确认启用服务端插件）

1. 采用网易那套：服务端插件是数据源，用自定义频道 S2C 推流；客户端模组只接收与渲染。
2. 服务端：PmxxlClaims-plugin-1.0.0.jar，只读 Residence，按半径 16 区块取领地，跨区块/2 秒巡检/领地变更事件触发，哈希去重，超过 1KB Deflate，世界标识用 World#getKey() 与客户端维度对齐。
3. 客户端：pmxxlclaims-mod-1.0.0.jar，优先用推流数据（30 秒内有包即视为推流生效），同时保留聊天解析兜底；两者在地图上按名字去重合并。
4. 兜底在推流生效时自动停用，不会额外发命令。
5. 文档：使用说明-领地小地图模组.md（客户端）、服务端部署-领地推流.md（服务端）。
## 17. 全量修复轮（2026-10-30 晚）

1. 协议升级 PMC2：单世界字符串 + status 字节；删除 id/Y/worldIndex 三个无用字段。
2. 服务端：查询结果 2 秒快照缓存 + ChunkRef 列表复用；编码/压缩移入异步线程（主线程只取快照与发包，按 revision 作废旧结果）；10 秒心跳；1 MiB 上限折半裁剪；只给注册了频道（或显式配置 assume-client-unregistered）的玩家发；半径钳到 32；命令输出中文化；pom 用 release 21、关闭 resources filtering。
3. 客户端：v2 解码含全部上限校验与 inflate 4 MiB 上限；ClaimCache 改为粘性活跃 + 单世界；ClaimStore 2000 条上限/按时间淘汰/异步写盘/版本号；ChatParser 一行多坐标、严格名字判定、数字解析防护；AutoScanner 世界+区块键、10 分钟 TTL、先进先出淘汰、换服重置；渲染缓存 + try/finally scissor + 配置化尺寸/边距/透明度 + 子领地 1px 描边；JOIN/DISCONNECT 全量清理；fabric.mod.json 收紧依赖。
4. 验证：两端 BUILD SUCCESS；PMC2 往返 PROTO v2 OK（113 字节）；畸形包 5/5 拒绝。
5. 已派出第二个干净上下文评审员复审，报告落 代码评审2-领地小地图.md。
