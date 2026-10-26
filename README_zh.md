# Bulk Terminal（大宗存储终端）

MEGA Cells 的附属模组 ：提供「大宗存储终端」，用于改善大宗磁盘的体验。

- Mod ID：`bulkterminal`
- 许可证：LGPL-3.0-or-later

## 功能特性

**专用的大宗存储视图**

- 只显示MEGA大宗物品存储元件已标记的物品；
- 物品数量显示为总量（MEGA大宗物品存储元件 + 其他磁盘），与实际可取量一致，提取时数字不会跳变。

**自动标记MEGA大宗物品存储元件**

- 首次存入某物品时，若所有大宗盘都不接收，终端自动向未标记的MEGA大宗物品存储元件打上该物品的标记并重新存入；
- 再次存入同一物品时直接命中已标记的盘，无额外开销；
- 打标记对象遵循驱动器 GUI 中设定的优先级（高优先级优先）；
- 自动标记可在终端设置界面关闭，关闭后退回 MEGA 原版行为；该开关持久化保存。

**压缩管理**
- 自动安装网络中的压缩卡
- 便捷修改压缩模式和类型

**一键转移**——压缩管理页面 
- 点击「一键转移」，自动转移已被标记物品至MEGA大宗物品存储元件；
- 按住 Shift 点击「一键转移」：自动把网络中超过 20000 的物品转入MEGA大宗物品存储元件。




## 版本矩阵

| 项           | 版本         |
| ------------ | ------------ |
| Minecraft    | 26.1.2       |
| NeoForge     | 26.1.2.81    |
| AE2          | 26.1.10-beta |
| MEGA Cells   | 4.12.0       |
| ModDevGradle | 2.0.143      |
| Java         | 25           |

> 注：MEGA Cells 4.12.0 的 26.1 移植尚未发布到 Modrinth/CurseForge，需自行从其 GitHub `26.1` 分支构建，并把编译出的 jar 放入本仓库 `libs/`（他人项目产物，不入库；构建命令见 `build.gradle.kts` 依赖块注释）。

## 项目结构

```
bulk-terminal/
├── build.gradle.kts / settings.gradle.kts / gradle wrapper
├── src/main/java/com/dloi/bulkterminal/
│   ├── BulkTerminal.java          # 模组入口与注册
│   ├── part/                      # BulkTerminalPart：终端部件，覆写 getInventory() 接入存储视图
│   ├── storage/                   # 核心：BulkOnlyStorage（可见/存入/提取）、
│   │                              #   BulkCellScanner（PMCS 扫描 + TTL 缓存）、
│   │                              #   BulkCellMarker（定位空盘 → 写标记 → 触发重新挂载）
│   ├── menu/                      # BulkTerminalMenu：菜单类型
│   ├── client/                    # 屏幕绑定、终端界面与设置界面
│   └── definition/                # 物品 / 菜单 / 创造模式物品栏注册
└── src/main/resources/
    ├── META-INF/neoforge.mods.toml
    ├── assets/ae2/screens/...     # 终端样式
    ├── assets/bulkterminal/...    # 语言文件、模型、贴图
    └── data/bulkterminal/recipe/  # 合成配方
```

## AI

本模组的开发过程使用了 AI。

## 许可证

- 代码许可协议采用 LGPLv3 条款；
- 素材许可协议采用 CC BY-NC-SA 3.0 条款。