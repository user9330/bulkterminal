package com.dloi.bulkterminal.debug;

import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.parts.PartHelper;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.StorageCells;
import appeng.api.orientation.IOrientationStrategy;
import appeng.api.util.AEColor;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.core.definitions.AEParts;
import appeng.me.helpers.BaseActionSource;
import appeng.blockentity.networking.CableBusBlockEntity;

import gripe._90.megacells.definition.MEGAItems;
import com.dloi.bulkterminal.BulkTerminal;
import com.dloi.bulkterminal.definition.BTItems;
import com.dloi.bulkterminal.part.BulkTerminalPart;
import com.dloi.bulkterminal.storage.BulkCellState;
import com.dloi.bulkterminal.storage.BulkOnlyStorage;
import com.dloi.bulkterminal.storage.PanelOps;

/**
 * 开发期自动复现脚本（仅当游戏目录下存在 {@code repro.txt} 标记文件时运行）。
 *
 * <p>在主世界出生点旁自动搭建「创意能源 + 线缆 + 大宗终端部件 + 驱动器 + 一块空大宗盘」，
 * 然后程序化执行完整存取场景并输出诊断，最后自动关服。用于稳定复现「已标记盘不直接接收、
 * 重复打标记」的问题。
 */
@EventBusSubscriber(modid = BulkTerminal.MOD_ID)
public final class BulkTerminalRepro {
    private static final String TAG = "[REPRO]";
    private static final int BUILD_TICK = 20;
    private static final int RUN_TICK = 200;
    private static final int HALT_TICK = 1200;

    private static int tick = 0;
    private static int reloadTick = 0;
    private static int reloadPending = -1;
    private static boolean active;
    private static boolean done;
    private static boolean reloadWait;

    private static BlockPos cablePos;
    private static BlockPos cablePos2;
    private static BlockPos drivePos;
    private static MinecraftServer serverRef;
    private static BulkTerminalPart terminalPart;

    private BulkTerminalRepro() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (done) {
            return;
        }

        var server = event.getServer();
        serverRef = server;

        if (!active) {
            if (!Files.exists(markerFile(server))) {
                return;
            }

            active = true;
            BulkTerminal.LOGGER.info("{} 检测到 repro.txt，开始自动复现流程", TAG);
        }

        tick++;

        if (reloadWait) {
            reloadTick++;

            if (reloadTick == 100) {
                afterReload(server);
            } else if (reloadTick == 100 + Math.max(1, reloadPending)) {
                runAfterReloadScenario(server);
                done = true;
                server.halt(false);
            }
            return;
        }

        if (tick == BUILD_TICK) {
            build(server);
        } else if (tick == RUN_TICK) {
            runScenario(server);
        } else if (tick >= HALT_TICK) {
            BulkTerminal.LOGGER.info("{} 流程超时，关服", TAG);
            done = true;
            server.halt(false);
        }
    }

    private static Path markerFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("repro.txt");
    }

    private static void build(MinecraftServer server) {
        var level = server.overworld();
        var base = level.getRespawnData().pos();
        var chunk = ChunkPos.containing(base);
        level.setChunkForced(chunk.x(), chunk.z(), true);
        int y = level.getMinY() + 2;

        cablePos = new BlockPos(base.getX() + 2, y, base.getZ() + 2);
        cablePos2 = cablePos.east();
        var energyPos = cablePos2.east();
        drivePos = cablePos.west();

        level.setBlock(cablePos, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(cablePos2, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(energyPos, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(drivePos, Blocks.AIR.defaultBlockState(), 3);

        level.setBlock(energyPos, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState(), 3);
        level.setBlock(drivePos, Blocks.AIR.defaultBlockState(), 3);
        var driveState = AEBlocks.DRIVE.block().defaultBlockState();
        driveState = IOrientationStrategy.get(driveState).setFacing(driveState, Direction.WEST);
        level.setBlock(drivePos, Blocks.AIR.defaultBlockState(), 3);
        BulkTerminal.LOGGER.info("{} 驱动器方块状态 = {}", TAG, driveState);

        var cable1 = PartHelper.setPart(level, cablePos, Direction.UP, null,
                AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT));
        var cable2 = PartHelper.setPart(level, cablePos2, Direction.UP, null,
                AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT));
        BulkTerminal.LOGGER.info("{} 线缆放置：{} / {}", TAG, cable1 != null, cable2 != null);

        terminalPart = PartHelper.setPart(level, cablePos2, Direction.NORTH, null,
                BTItems.BULK_TERMINAL.get());
        BulkTerminal.LOGGER.info("{} 终端部件放置：{}", TAG, terminalPart != null);

        // 驱动器最后放置，让其节点创建时向已有线缆发起连接
        level.setBlock(drivePos, driveState, 3);

        var drive = (DriveBlockEntity) level.getBlockEntity(drivePos);
        var added = drive.getInternalInventory().addItems(MEGAItems.BULK_ITEM_CELL.stack());
        drive.getInternalInventory().addItems(MEGAItems.BULK_ITEM_CELL.stack());
        drive.getInternalInventory().addItems(AEItems.ITEM_CELL_64K.stack());
        drive.saveChanges();
        BulkTerminal.LOGGER.info("{} 驱动器位于 {}，放入 2 块大宗盘 + 1 块 64k 盘：剩余 {}（0 = 全部放入）",
                TAG, drivePos, added.isEmpty() ? 0 : added.getCount());
    }

    private static void dumpDriveAndScan(MinecraftServer server) {
        var level = server.overworld();
        var drive = (DriveBlockEntity) level.getBlockEntity(drivePos);
        BulkTerminal.LOGGER.info("{} 诊断：驱动器节点在线={}", TAG,
                drive.getMainNode().isOnline());

        var energyState = level.getBlockState(drivePos.east());
        BulkTerminal.LOGGER.info("{} 诊断：能源位方块 = {}", TAG, energyState.getBlock());

        var grid = drive.getMainNode().getGrid();
        if (grid != null) {
            var energy = grid.getEnergyService();
            BulkTerminal.LOGGER.info("{} 诊断：网格储能 = {} / {} AE",
                    TAG, (long) energy.getStoredPower(), (long) energy.getMaxStoredPower());
            for (var node : grid.getNodes()) {
                BulkTerminal.LOGGER.info("{} 诊断：节点 {} 在线={}", TAG,
                        node.getOwner() == null ? "null" : node.getOwner().getClass().getSimpleName(),
                        node.isOnline());
            }
        }

        var inv = drive.getInternalInventory();
        for (int i = 0; i < inv.size(); i++) {
            var s = inv.getStackInSlot(i);
            if (!s.isEmpty()) {
                var cell = StorageCells.getCellInventory(s, null);
                BulkTerminal.LOGGER.info("{} 诊断：槽位 {} = {}，StorageCells 识别 = {}",
                        TAG, i, s.getItem(), cell == null ? "null" : cell.getClass().getName());
            }
        }

        if (grid == null) {
            BulkTerminal.LOGGER.info("{} 诊断：驱动器无网格", TAG);
            return;
        }

        for (var node : grid.getNodes()) {
            var provider = node.getService(IStorageProvider.class);
            BulkTerminal.LOGGER.info("{} 诊断：节点 {} owner={} provider={}", TAG,
                    node, node.getOwner() == null ? "null" : node.getOwner().getClass().getSimpleName(),
                    provider == null ? "null" : provider.getClass().getSimpleName());

            if (provider != null) {
                provider.mountInventories((storage, priority) -> BulkTerminal.LOGGER.info(
                        "{} 诊断：挂载到 {}（优先级 {}）", TAG, storage.getClass().getName(), priority));
            }
        }
    }

    private static void runScenario(MinecraftServer server) {
        try {
            doScenario(server);
        } catch (Throwable t) {
            BulkTerminal.LOGGER.error("{} 场景执行异常", TAG, t);
            done = true;
            server.halt(false);
        }
    }

    private static void doScenario(MinecraftServer server) {
        if (terminalPart == null) {
            BulkTerminal.LOGGER.info("{} 终端部件为空，无法继续", TAG);
            return;
        }

        var grid = terminalPart.getGrid();
        BulkTerminal.LOGGER.info("{} 终端节点：{}，网格节点数：{}", TAG,
                terminalPart.getGridNode() != null, grid == null ? -1 : grid.size());

        var storage = (BulkOnlyStorage) terminalPart.getInventory();
        var src = new BaseActionSource();
        var cobble = AEItemKey.of(Items.COBBLESTONE);
        var iron = AEItemKey.of(Items.IRON_INGOT);

        dumpDriveAndScan(server);

        BulkTerminal.LOGGER.info("{} ---- 步骤1：首次存入圆石 x10（MODULATE），期望返回 10 且只标记一块盘", TAG);
        long r1 = storage.insert(cobble, 10, Actionable.MODULATE, src);
        BulkTerminal.LOGGER.info("{} 返回值 = {}", TAG, r1);
        dump(storage);

        BulkTerminal.LOGGER.info("{} ---- 步骤2：再次存入圆石 x10，期望返回 10 且仍只有一块被标记", TAG);
        long r2 = storage.insert(cobble, 10, Actionable.MODULATE, src);
        BulkTerminal.LOGGER.info("{} 返回值 = {}", TAG, r2);
        dump(storage);

        BulkTerminal.LOGGER.info("{} ---- 步骤3：存入铁锭 x3，期望返回 3 且标记第二块盘", TAG);
        long r3 = storage.insert(iron, 3, Actionable.MODULATE, src);
        BulkTerminal.LOGGER.info("{} 返回值 = {}", TAG, r3);
        dump(storage);

        BulkTerminal.LOGGER.info("{} ---- 步骤4：模拟存入圆石 x5（SIMULATE），期望返回 5 且不得新增标记", TAG);
        long s1 = storage.insert(cobble, 5, Actionable.SIMULATE, src);
        BulkTerminal.LOGGER.info("{} 返回值 = {}", TAG, s1);
        dump(storage);

        BulkTerminal.LOGGER.info("{} ---- 步骤5：卸载并重载区块（模拟玩家离开后的区块回收），然后再次存入圆石 x10", TAG);
        var level = server.overworld();
        var chunk = ChunkPos.containing(cablePos);
        level.setChunkForced(chunk.x(), chunk.z(), false);
        BulkTerminal.LOGGER.info("{} 区块已取消强制加载，等待保存与卸载……", TAG);
        reloadWait = true;
    }

    private static void afterReload(MinecraftServer server) {
        var level = server.overworld();
        var chunk = ChunkPos.containing(cablePos);
        level.setChunkForced(chunk.x(), chunk.z(), true);
        BulkTerminal.LOGGER.info("{} 区块已重新加载，等待 BE 重建……", TAG);
        reloadPending = 100;
    }

    private static void runAfterReloadScenario(MinecraftServer server) {
        var level = server.overworld();
        var host = level.getBlockEntity(cablePos2);
        BulkTerminal.LOGGER.info("{} 线缆总线 BE = {}", TAG, host);
        if (host instanceof CableBusBlockEntity bus) {
            terminalPart = bus.getPart(Direction.NORTH) instanceof BulkTerminalPart btp ? btp : null;
        }

        if (terminalPart == null) {
            BulkTerminal.LOGGER.info("{} 重载后终端部件为空，无法继续", TAG);
            return;
        }

        var storage = (BulkOnlyStorage) terminalPart.getInventory();
        var src = new BaseActionSource();
        var cobble = AEItemKey.of(Items.COBBLESTONE);

        BulkTerminal.LOGGER.info("{} ---- 步骤6：重载后存入圆石 x10（MODULATE），期望直接命中已标记盘：返回 10 且不新增标记", TAG);
        long r4 = storage.insert(cobble, 10, Actionable.MODULATE, src);
        BulkTerminal.LOGGER.info("{} 返回值 = {}", TAG, r4);
        dump(storage);

        // 压缩链去重（isAlreadyMarkedOrStoring 的定向验证）：
        // 铁锭 ↔ 铁块是原版 9→1 配方，MEGA 一定聚合成同一条链。
        // 给铁盘装压缩卡前，铁块不被任何盘接管（期望 false）；
        // 装卡后，铁盘按压缩链接收铁块（期望 true）——这正是去重判定的依据。
        BulkTerminal.LOGGER.info("{} ---- 步骤7：压缩链去重验证（铁块 = 铁锭的同链变体）", TAG);
        var ironBlock = AEItemKey.of(Items.IRON_BLOCK);
        BulkTerminal.LOGGER.info("{} 装卡前 isAlreadyMarkedOrStoring(iron_block) = {}（期望 false）", TAG,
                storage.marker().isAlreadyMarkedOrStoring(storage.entries(), ironBlock));

        if (serverRef != null && drivePos != null
                && serverRef.overworld().getBlockEntity(drivePos) instanceof DriveBlockEntity drive) {
            var inv = drive.getInternalInventory();
            for (int i = 0; i < inv.size(); i++) {
                var s = inv.getStackInSlot(i);
                if (s.getItem() instanceof gripe._90.megacells.item.cell.BulkCellItem cellItem
                        && AEItemKey.of(Items.IRON_INGOT).equals(cellItem.getConfigInventory(s).getKey(0))) {
                    var left = cellItem.getUpgrades(s).addItems(MEGAItems.COMPRESSION_CARD.stack());
                    BulkTerminal.LOGGER.info("{} 已给槽位 {} 安装压缩卡（余料 {}）", TAG, i, left.getCount());
                }
            }

            BulkTerminal.LOGGER.info("{} 装卡后 isAlreadyMarkedOrStoring(iron_block) = {}（期望 true）", TAG,
                    storage.marker().isAlreadyMarkedOrStoring(storage.entries(), ironBlock));
        } else {
            BulkTerminal.LOGGER.info("{} 未找到驱动器，步骤7 跳过", TAG);
        }

        // 管理操作（PanelOps）的服务端直测：装卡 / 切档 / 一键转移。
        // 不走 UI 与网络包，直接调用操作层，验证状态机与组件写入。
        BulkTerminal.LOGGER.info("{} ---- 步骤8：管理操作直测（装卡 / 切档 / 一键转移）", TAG);

        if (serverRef != null && drivePos != null && terminalPart != null
                && serverRef.overworld().getBlockEntity(drivePos) instanceof DriveBlockEntity drive
                && terminalPart.getGrid() != null) {
            var grid = terminalPart.getGrid();
            var network = grid.getStorageService().getInventory();
            var src2 = new BaseActionSource();

            // 8a：网络里放一张压缩卡，然后通过管理操作给圆石盘装卡
            network.insert(AEItemKey.of(MEGAItems.COMPRESSION_CARD), 1, Actionable.MODULATE, src2);
            var stacks = PanelOps.enumerate(terminalPart);
            int cobbleIdx = -1;
            int ironIdx = -1;

            for (int i = 0; i < stacks.size(); i++) {
                var filter = BulkCellState.filter(stacks.get(i).stack());

                if (AEItemKey.of(Items.COBBLESTONE).equals(filter)) {
                    cobbleIdx = i;
                } else if (AEItemKey.of(Items.IRON_INGOT).equals(filter)) {
                    ironIdx = i;
                }
            }

            BulkTerminal.LOGGER.info("{} 8a：枚举到 {} 块大宗盘（圆石={} 铁锭={}）", TAG,
                    stacks.size(), cobbleIdx, ironIdx);
            PanelOps.installCard(terminalPart, null, cobbleIdx);
            BulkTerminal.LOGGER.info("{} 8a：装卡后圆石盘 hasCompressionCard = {}（期望 true）", TAG,
                    stacks.get(cobbleIdx) != null
                            && BulkCellState.hasCompressionCard(stacks.get(cobbleIdx).stack()));

            // 8b：给铁锭盘切档。铁链 = [铁粒, 铁锭, 铁块]（基本单位是铁粒），
            // 默认档位 = 链长-1（满链发布）。-1 降一档（期望 1），+1 升回（期望 2）
            if (ironIdx >= 0) {
                var ironStack = stacks.get(ironIdx).stack();
                BulkTerminal.LOGGER.info("{} 8b：铁链大小 = {}（期望 3），初始档位 = {}（期望 2）", TAG,
                        BulkCellState.chainOf(ironStack).size(), BulkCellState.cutoff(ironStack));
                PanelOps.cycleCutoff(terminalPart, null, ironIdx, -1);
                BulkTerminal.LOGGER.info("{} 8b：降一档后 cutoff = {}（期望 1）", TAG,
                        BulkCellState.cutoff(ironStack));
                PanelOps.cycleCutoff(terminalPart, null, ironIdx, 1);
                BulkTerminal.LOGGER.info("{} 8b：升一档后 cutoff = {}（期望 2）", TAG,
                        BulkCellState.cutoff(ironStack));
            }

            // 8c：一键转移。直接往 64k 普通盘里塞 100 个铁锭（绕开网络路由），
            // 阈值 50 转移后铁盘单位数应为 3+50=53，再全量转移后应为 103
            var regInv = drive.getInternalInventory();
            for (int i = 0; i < regInv.size(); i++) {
                var s = regInv.getStackInSlot(i);
                var cellInv = StorageCells.getCellInventory(s, null);

                if (cellInv == null
                        || cellInv instanceof gripe._90.megacells.item.cell.BulkCellInventory) {
                    continue;
                }

                long inserted = cellInv.insert(AEItemKey.of(Items.IRON_INGOT), 100,
                        Actionable.MODULATE, src2);
                BulkTerminal.LOGGER.info("{} 8c：普通盘槽位 {} 存入铁锭 {}（期望 100）", TAG, i, inserted);
            }

            if (ironIdx >= 0) {
                var ironStack = stacks.get(ironIdx).stack();
                PanelOps.transferAll(terminalPart, null, 50);
                // 单位是铁粒：3 铁锭 = 27 单位；+50 铁锭 = +450 → 477
                BulkTerminal.LOGGER.info("{} 8c：阈值 50 转移后铁盘单位数 = {}（期望 477）", TAG,
                        BulkCellState.unitCount(ironStack));
                PanelOps.transferAll(terminalPart, null, 0);
                BulkTerminal.LOGGER.info("{} 8c：全量转移后铁盘单位数 = {}（期望 927）", TAG,
                        BulkCellState.unitCount(ironStack));
            }
        } else {
            BulkTerminal.LOGGER.info("{} 未找到驱动器/终端，步骤8 跳过", TAG);
        }
    }

    private static void dump(BulkOnlyStorage storage) {
        var entries = storage.entries();
        BulkTerminal.LOGGER.info("{} 快照：共 {} 块大宗盘", TAG, entries.size());

        for (var e : entries) {
            BulkTerminal.LOGGER.info("{}   盘：优先级={} canFitInsideCell={} 有存量={}",
                    TAG, e.priority(), e.cell().canFitInsideCell(),
                    storage.marker().hasStoredContent(e));
        }

        // 逐槽位读取驱动器内每块盘的 filter / 存量组件
        if (serverRef != null && drivePos != null
                && serverRef.overworld().getBlockEntity(drivePos) instanceof DriveBlockEntity drive) {
            var inv = drive.getInternalInventory();
            for (int i = 0; i < inv.size(); i++) {
                var s = inv.getStackInSlot(i);
                if (!s.isEmpty() && s.getItem() instanceof gripe._90.megacells.item.cell.BulkCellItem cellItem) {
                    var config = cellItem.getConfigInventory(s).getStack(0);
                    var stored = s.get(gripe._90.megacells.definition.MEGAComponents.BULK_CELL_ITEM);
                    BulkTerminal.LOGGER.info("{}   槽位{}：filter={} stored={}",
                            TAG, i, config == null ? "无" : config.what(), stored);
                }
            }
        }
    }
}
