# 领地小地图（pmxxl-claims-map）

Minecraft 私服「小地图显示附近领地」的完整实现：一个 Purpur/Paper 服务端插件 + 一个 Fabric 客户端模组。

- 服务端插件 `PmxxlClaims`：只读 Residence 5.1.6.2 的领地数据，按玩家半径（默认 16 区块）打包，通过自定义频道 `pmxxl:claims` 下发；不做判定、不存数据、不改 Residence。
- 客户端模组 `pmxxlclaims`：接收并缓存报文，在小地图 HUD 上绘制半透明色块、描边与领地名；服务端未安装插件时自动降级为聊天解析（`/res show` 回显）兜底。

## 目录结构

```
server/   服务端插件（Maven，Java 21，paper-api provided，Residence provided）
client/   客户端模组（Fabric Loom，MC 1.21.11）
docs/     设计、部署、评审与验证文档
```

## 环境要求

1. 服务端：Purpur / Paper / Spigot 1.21.11，Java 21，Residence 5.1.6.2（含 CMILib）。
2. 客户端：Fabric Loader 0.20.1，Minecraft 1.21.11，Fabric API >= 0.141.6。

## 构建

### 服务端插件

需要 JDK 21+ 与 Maven：

```
mvn -f server/pom.xml clean package
```

产物：`server/target/PmxxlClaims-1.0.0.jar`。

### 客户端模组

本工程使用 Fabric Loom 1.19.3，Loom 1.19.3 要求运行 Gradle 的 JVM 为 Java 25，编译目标仍为 Java 21。

```
cd client
gradle build
```

产物：`client/build/libs/pmxxlclaims-1.0.0.jar`。

## 部署

1. 服务端：把插件 jar 放入 `plugins/`，重启服务器。日志出现 `PmxxlClaims enabled, Residence hook ok, radius=16` 即成功。
2. 客户端：把模组 jar 放入 `.minecraft/mods/`。
3. 服务端配置 `plugins/PmxxlClaims/config.yml`：

```yaml
radius-chunks: 16
send-owner: false
assume-client-unregistered: false
```

4. 命令：`/claimsmap reload`、`/claimsmap debug [玩家]`，权限 `pmxxlclaims.admin`。
5. 客户端按键：`M` 开关小地图；配置文件 `config/pmxxlclaims.json`。

## 通信协议 PMC3

1. 帧：magic `PMC3` + flags（bit0 = Deflate）+ body。
2. body：世界字符串（≤512 字节）+ 领地数（varint）+ 每条领地（名字、owner、color、flags、区域数、每区域 minX/minZ/maxX/maxZ）。
3. 触发：跨区块立即、每 3 秒巡检、领地变更合并刷新；内容不变时仅 10 秒心跳发送。
4. 体积：空包 26 字节，500 条约 8 KB，超过 1 MiB 自动裁剪。

## 验证状态

1. 两端编译通过；PMC3 编解码往返与畸形包拒绝测试通过。
2. 已在真实 Purpur 1.21.11 + Residence 5.1.6.2 + CMILib 环境验证：插件加载、命令执行、真实查询、客户端频道注册后收到可解码的 PMC3 帧、心跳重复。
3. 尚需游戏内实测：真实领地数据的渲染对齐、Fabric 客户端加载表现。

## 已知限制

1. 代理多后端（同域名多子服）时客户端无法区分后端，聊天兜底记录可能串数据。
2. 推送半径上限 32 区块。
3. 客户端底图是区块平均色，无地形细节与缩放/旋转。
4. 聊天兜底依赖 `/res show` 文本格式。

## License

All Rights Reserved。

## JourneyMap 集成（推荐）

1. 玩家安装 JourneyMap 1.21.11-6.0.0+（Fabric）后，本模组不再自己画小地图，而是作为 JourneyMap 插件，把领地范围画在 JourneyMap 的小地图与全屏地图上（API v2 的 PolygonOverlay）。
2. 未安装 JourneyMap 时自动回落到模组自带的简易小地图。
3. 构建依赖：`info.journeymap:journeymap-api-fabric:2.0.0-1.21.11-SNAPSHOT`，仓库 `https://jm.gserv.me/repository/maven-public/`；仅 compileOnly，不随本模组分发任何 JourneyMap 代码或 jar（JourneyMap 为 All Rights Reserved，只通过其公开 API 使用）。
