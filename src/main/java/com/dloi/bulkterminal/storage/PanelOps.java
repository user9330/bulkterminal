package com.dloi.bulkterminal.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.StorageCells;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.me.helpers.BaseActionSource;
import appeng.me.storage.DriveWatcher;
import appeng.parts.AEBasePart;

import com.dloi.bulkterminal.BulkTerminal;
import com.dloi.bulkterminal.net.PanelSnapshotPayload;
import com.dloi.bulkterminal.part.BulkTerminalPart;

import gripe._90.megacells.definition.MEGAComponents;
import gripe._90.megacells.definition.MEGAItems;
import gripe._90.megacells.item.cell.BulkCellInventory;
import gripe._90.megacells.item.cell.BulkCellItem;
import gripe._90.megacells.item.part.CellDockPart;
import gripe._90.megacells.misc.CompressionService;

/**
 * 「大宗盘管理」的服务端操作层：快照、装/取压缩卡、切档位、一键转移。
 *
 * <p>所有状态判定走 {@link BulkCellState}（组件直读）；凡改动了盘的组件
 * （卡、档位、存量）都必须经 {@link BulkCellMarker#refreshAndSave} 强制宿主重建
 * 单元格实例——实例的这些字段都是构造时快照，且旧实例 persist 时会把快照写回组件。
 */
public final class PanelOps {
    private static final Logger LOGGER = LoggerFactory.getLogger(BulkTerminal.MOD_ID + "/PanelOps");

    private PanelOps() {
    }

    /**
     * 盘的稳定身份：宿主节点 + 槽位号。装卡/切档位只改 ItemStack 组件、
     * 不动宿主槽位，所以它在一次界面会话内不变，用于冻结行序（见 {@link #orderedStacks}）。
     */
    private record DiskId(IGridNode node, int slot) {
    }

    /** 枚举时找到的一块大宗盘：ItemStack + 它在宿主里的槽位号。 */
    private record FoundCell(ItemStack stack, int slot) {
    }

    /**
     * 管理界面里的一块大宗盘：稳定身份 + 宿主中的 ItemStack + 它对应的挂载条目（空盘也可能没有挂载）。
     */
    public record PanelStack(DiskId id, ItemStack stack, @Nullable MountEntry entry, int priority,
            String hostDesc) {
    }

    /**
     * 枚举网络中所有大宗盘 ItemStack（每盘一行，跨宿主稳定排序）。
     */
    public static List<PanelStack> enumerate(BulkTerminalPart part) {
        var result = new ArrayList<PanelStack>();
        var grid = part.getGrid();

        if (grid == null) {
            return result;
        }

        var storage = part.getBulkInventory();
        var entries = storage == null ? List.<MountEntry>of() : storage.entries();

        for (var node : grid.getNodes()) {
            var owner = node.getOwner();
            List<FoundCell> found = null;
            String hostDesc = null;

            if (owner instanceof DriveBlockEntity drive) {
                found = new ArrayList<>();
                var inv = drive.getInternalInventory();

                for (int i = 0; i < inv.size(); i++) {
                    var stack = inv.getStackInSlot(i);

                    if (!stack.isEmpty() && stack.getItem() instanceof BulkCellItem) {
                        found.add(new FoundCell(stack, i));
                    }
                }

                hostDesc = Component.translatable("gui.bulkterminal.host_drive",
                        drive.getBlockPos().toShortString()).getString();
            } else if (owner instanceof CellDockPart dock) {
                var stack = dock.getCellInventory().getStackInSlot(0);

                if (!stack.isEmpty() && stack.getItem() instanceof BulkCellItem) {
                    found = List.of(new FoundCell(stack, 0));
                }

                hostDesc = Component.translatable("gui.bulkterminal.host_dock",
                        dock.getHost().getLocation().getPos().toShortString()).getString();
            }

            if (found == null) {
                continue;
            }

            int priority = entries.stream()
                    .filter(e -> e.node() == node)
                    .mapToInt(MountEntry::priority)
                    .max()
                    .orElse(0);
            var entry = entries.stream()
                    .filter(e -> e.node() == node)
                    .findFirst()
                    .orElse(null);

            for (var cell : found) {
                result.add(new PanelStack(new DiskId(node, cell.slot()), cell.stack(), entry, priority,
                        hostDesc));
            }
        }

        // 管理界面行序：先按可用性分档。
        // 档位（0 最前）：
        //   0 有压缩链 + 已装压缩卡 + 已标记   —— 能力最全
        //   1 有压缩链 + 未装压缩卡 + 已标记   —— 装上卡就是能力最全，紧跟其后
        //   2 无压缩链 + 已装压缩卡 + 已标记   —— 装了卡但该物品无压缩链
        //   3 无压缩链 + 未装压缩卡 + 已标记   —— 只能存单一物品
        //   4 未标记（空闲盘）                —— 什么都干不了
        // 档内的字母序由客户端做（照 JEI 的做法按本地化显示名 compareTo），
        // 服务端只有一种本地化结果、拿不到玩家看到的中文名，所以不在这里排名字。
        // 界面打开期间本排序被 orderedStacks 的会话缓存冻结：只有打开/刷新界面时
        // 才按这里的顺序重建行序，避免操作后行跳动误触。
        result.sort(Comparator.comparingInt(PanelOps::sortRank));

        return result;
    }

    /** 排序档位，0 最前。 */
    private static int sortRank(PanelStack ps) {
        var stack = ps.stack();

        if (!isMarked(stack)) {
            return 4;
        }

        boolean hasChain = !BulkCellState.chainOf(stack).isEmpty();
        boolean hasCard = BulkCellState.hasCompressionCard(stack);

        if (hasChain && hasCard) {
            return 0;
        }

        if (hasChain) {
            return 1;
        }

        return hasCard ? 2 : 3;
    }

    /** 是否已标记（有 filter 或有存量）。 */
    private static boolean isMarked(ItemStack stack) {
        return BulkCellState.filter(stack) != null || BulkCellState.stored(stack) != null;
    }

    // ===== 行序冻结（界面会话） =====

    /** 一次界面会话的冻结行序：盘身份顺序 + 每盘的会话内稳定行号。 */
    private record Session(List<DiskId> order, Map<DiskId, Integer> rowIds, int nextId) {
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    /**
     * 丢弃玩家的冻结行序。打开「压缩管理」界面（panelRefresh）与关闭终端菜单时调用，
     * 下次发快照会按当时状态重新排序 —— 排序只在这个时机生效。
     */
    public static void clearSession(UUID playerId) {
        SESSIONS.remove(playerId);
    }

    /** 一次排序结果：冻结顺序的盘列表 + 每盘的会话行号。 */
    private record Ordered(List<PanelStack> stacks, Map<DiskId, Integer> rowIds) {
    }

    /**
     * 取该玩家的管理界面行序。
     *
     * <p>首次调用（会话刚建立）按 {@link #enumerate} 的实时排序记下每块盘的身份，
     * 之后每次调用把最新数据套进冻结顺序：还在的盘保持原位、消失的剔除、
     * 新出现的按档位序追加到末尾。行号（{@code rowIds}）会话内单调递增、
     * 不随位置变化，点击动作按它回传定位，彻底避免实时重排造成的误触。
     */
    private static Ordered orderedStacks(BulkTerminalPart part, @Nullable ServerPlayer player) {
        var fresh = enumerate(part);

        if (player == null) {
            // 无会话场景（自动化测试）：直接用实时排序，行号 = 下标
            var rowIds = new HashMap<DiskId, Integer>();

            for (int i = 0; i < fresh.size(); i++) {
                rowIds.put(fresh.get(i).id(), i);
            }

            return new Ordered(fresh, rowIds);
        }

        var session = SESSIONS.get(player.getUUID());

        if (session == null) {
            var order = new ArrayList<DiskId>(fresh.size());
            var rowIds = new HashMap<DiskId, Integer>();

            for (var ps : fresh) {
                order.add(ps.id());
                rowIds.put(ps.id(), rowIds.size());
            }

            SESSIONS.put(player.getUUID(), new Session(order, rowIds, rowIds.size()));
            return new Ordered(fresh, rowIds);
        }

        var byId = new HashMap<DiskId, PanelStack>();

        for (var ps : fresh) {
            byId.put(ps.id(), ps);
        }

        var stacks = new ArrayList<PanelStack>(fresh.size());
        var order = new ArrayList<DiskId>(fresh.size());
        var rowIds = new HashMap<>(session.rowIds());
        int nextId = session.nextId();

        // 旧顺序里还在的盘保持原位（行数据取最新的）
        for (var id : session.order()) {
            var ps = byId.remove(id);

            if (ps != null) {
                stacks.add(ps);
                order.add(id);
            }
        }

        // 新出现的盘按档位序追加到末尾，分配新行号
        for (var ps : fresh) {
            if (byId.containsKey(ps.id())) {
                stacks.add(ps);
                order.add(ps.id());
                rowIds.put(ps.id(), nextId++);
            }
        }

        SESSIONS.put(player.getUUID(), new Session(order, rowIds, nextId));
        return new Ordered(stacks, rowIds);
    }

    // ===== 快照 =====

    public static void sendSnapshot(BulkTerminalPart part, @Nullable ServerPlayer player) {
        if (player == null) {
            return;
        }

        var grid = part.getGrid();

        if (grid == null) {
            com.dloi.bulkterminal.net.PanelClientState.sendTo(player,
                    new PanelSnapshotPayload(0, part.isAutoMarkEnabled(), part.getTransferThreshold(),
                            List.of()));
            return;
        }

        long cards = countNetworkCards(grid);
        var ordered = orderedStacks(part, player);
        var rows = new ArrayList<PanelSnapshotPayload.Row>();

        for (var ps : ordered.stacks()) {
            rows.add(buildRow(ps, ordered.rowIds().get(ps.id())));
        }

        com.dloi.bulkterminal.net.PanelClientState.sendTo(player,
                new PanelSnapshotPayload(cards, part.isAutoMarkEnabled(), part.getTransferThreshold(), rows));
    }

    /**
     * 操作反馈：玩家在线时发聊天框（成功绿 / 提醒黄），随后统一下发新快照。
     * 无玩家场景（自动化测试）只发快照，文案由调用方自行记日志。
     */
    public static void feedback(BulkTerminalPart part, @Nullable ServerPlayer player,
            List<String> info, List<String> warn) {
        if (player != null) {
            for (var line : info) {
                player.displayClientMessage(Component.literal(line).withStyle(ChatFormatting.GREEN), false);
            }

            for (var line : warn) {
                player.displayClientMessage(Component.literal(line).withStyle(ChatFormatting.YELLOW), false);
            }
        }

        sendSnapshot(part, player);
    }

    private static long countNetworkCards(IGrid grid) {
        var network = grid.getStorageService().getInventory();
        var counter = new KeyCounter();
        network.getAvailableStacks(counter);
        return counter.get(AEItemKey.of(MEGAItems.COMPRESSION_CARD));
    }

    private static PanelSnapshotPayload.Row buildRow(PanelStack ps, int rowId) {
        var stack = ps.stack();
        var filter = BulkCellState.filter(stack);
        var stored = BulkCellState.stored(stack);
        var chain = BulkCellState.chainOf(stack);

        var chainIds = new ArrayList<String>();
        for (int i = 0; i < chain.size(); i++) {
            chainIds.add(AEItemKey.of(chain.getItem(i)).getId().toString());
        }

        var display = new ArrayList<String>();
        var cell = StorageCells.getCellInventory(stack, null);

        if (cell instanceof BulkCellInventory bulk) {
            var counter = new KeyCounter();
            bulk.getAvailableStacks(counter);

            for (var entry : counter) {
                if (entry.getKey() instanceof AEItemKey key && entry.getLongValue() > 0) {
                    display.add(key.getId().toString() + "|" + entry.getLongValue());
                }
            }
        }

        return new PanelSnapshotPayload.Row(
                ps.priority(),
                ps.hostDesc(),
                filter == null ? "" : filter.getId().toString(),
                stored == null ? "" : stored.getId().toString(),
                BulkCellState.hasCompressionCard(stack),
                BulkCellState.isMismatched(stack),
                BulkCellState.canFitInsideCell(stack),
                BulkCellState.cutoff(stack),
                chainIds,
                display,
                rowId);
    }

    // ===== 装卡 / 取卡 =====

    public static void installCard(BulkTerminalPart part, @Nullable ServerPlayer player, int rowId) {
        var ps = stackByRowId(part, player, rowId);

        if (ps == null) {
            return;
        }

        var stack = ps.stack();

        if (!(stack.getItem() instanceof BulkCellItem cellItem)) {
            return;
        }

        if (BulkCellState.isMismatched(stack)) {
            feedback(part, player, List.of(), List.of(
                    Component.translatable("gui.bulkterminal.err_mismatch").getString()));
            return;
        }

        if (BulkCellState.hasCompressionCard(stack)) {
            feedback(part, player, List.of(), List.of(
                    Component.translatable("gui.bulkterminal.err_card_present").getString()));
            return;
        }

        var grid = part.getGrid();

        if (grid == null) {
            return;
        }

        var source = actionSource(part);
        var network = grid.getStorageService().getInventory();
        var cardKey = AEItemKey.of(MEGAItems.COMPRESSION_CARD);

        if (network.extract(cardKey, 1, Actionable.SIMULATE, source) < 1) {
            feedback(part, player, List.of(), List.of(
                    Component.translatable("gui.bulkterminal.err_no_card").getString()));
            return;
        }

        network.extract(cardKey, 1, Actionable.MODULATE, source);
        var leftover = cellItem.getUpgrades(stack).addItems(cardKey.toStack());

        if (!leftover.isEmpty()) {
            // 上限校验理论上已在 SIMULATE 前置检查里拦住；万一没装进去，把卡退回网络
            network.insert(AEItemKey.of(leftover), leftover.getCount(), Actionable.MODULATE, source);
            LOGGER.warn("装卡未成功（盘已满/不允许），卡已退回网络：{}", ps.hostDesc());
        } else if (ps.entry() != null) {
            marker(part).refreshAndSave(ps.entry());
        }

        invalidate(part);
        feedback(part, player, List.of(
                Component.translatable("gui.bulkterminal.info_card_installed", ps.hostDesc()).getString()),
                List.of());
    }

    public static void takeCard(BulkTerminalPart part, @Nullable ServerPlayer player, int rowId) {
        var ps = stackByRowId(part, player, rowId);

        if (ps == null || !(ps.stack().getItem() instanceof BulkCellItem cellItem)) {
            return;
        }

        var stack = ps.stack();

        if (!BulkCellState.hasCompressionCard(stack)) {
            feedback(part, player, List.of(), List.of(
                    Component.translatable("gui.bulkterminal.err_card_absent").getString()));
            return;
        }

        var grid = part.getGrid();

        if (grid == null) {
            return;
        }

        var source = actionSource(part);
        var network = grid.getStorageService().getInventory();
        var upgrades = cellItem.getUpgrades(stack);

        for (int slot = 0; slot < upgrades.size(); slot++) {
            var card = upgrades.getStackInSlot(slot);

            if (card.isEmpty() || !card.is(MEGAItems.COMPRESSION_CARD.asItem())) {
                continue;
            }

            var removed = upgrades.extractItem(slot, 1, false);

            if (removed.isEmpty()) {
                continue;
            }

            var inserted = network.insert(AEItemKey.of(removed), removed.getCount(),
                    Actionable.MODULATE, source);

            if (inserted < removed.getCount()) {
                // 网络塞不下（理论不可能），塞回卡槽
                upgrades.addItems(removed.copyWithCount((int) (removed.getCount() - inserted)));
            }

            if (ps.entry() != null) {
                marker(part).refreshAndSave(ps.entry());
            }

            invalidate(part);
            feedback(part, player, List.of(
                    Component.translatable("gui.bulkterminal.info_card_removed", ps.hostDesc()).getString()),
                    List.of());
            return;
        }

        feedback(part, player, List.of(), List.of(
                Component.translatable("gui.bulkterminal.err_card_absent").getString()));
    }

    // ===== 档位切换 =====

    public static void cycleCutoff(BulkTerminalPart part, @Nullable ServerPlayer player, int rowId, int delta) {
        var ps = stackByRowId(part, player, rowId);

        if (ps == null) {
            return;
        }

        var stack = ps.stack();

        if (!BulkCellState.canCycleCutoff(stack)) {
            feedback(part, player, List.of(), List.of(
                    Component.translatable("gui.bulkterminal.err_no_chain").getString()));
            return;
        }

        int max = BulkCellState.maxCutoff(stack);
        int next = Math.clamp(BulkCellState.cutoff(stack) + delta, 0, max);

        stack.set(MEGAComponents.BULK_CELL_COMPRESSION_CUTOFF, next);

        if (ps.entry() != null) {
            marker(part).refreshAndSave(ps.entry());
        }

        invalidate(part);

        var chain = BulkCellState.chainOf(stack);
        var itemName = chain.isEmpty() ? "" : AEItemKey.of(chain.getItem(next)).getDisplayName().getString();
        feedback(part, player, List.of(
                Component.translatable("gui.bulkterminal.info_cutoff", ps.hostDesc(), itemName).getString()),
                List.of());
    }

    // ===== 一键转移 =====

    /** 一个网络挂载：存储视图 + 是否大宗盘（大宗盘持有量不计入普通存储统计）。 */
    private record Mount(appeng.api.storage.MEStorage storage, boolean bulk) {
    }

    /** 收集全部挂载（大宗盘 + 普通存储）。 */
    private static List<Mount> collectMounts(IGrid grid) {
        var mounts = new ArrayList<Mount>();

        for (var node : grid.getNodes()) {
            var provider = node.getService(IStorageProvider.class);

            if (provider == null) {
                continue;
            }

            provider.mountInventories((inv, priority) -> {
                var cell = unwrap(inv);
                mounts.add(new Mount(inv, cell instanceof BulkCellInventory));
            });
        }

        return mounts;
    }

    /**
     * 普通存储里各物品的存量（大宗盘持有量不算）。
     *
     * @param only 只统计这些物品；null = 全部物品
     */
    private static Map<AEItemKey, Long> regularHeld(List<Mount> mounts, @Nullable Set<AEItemKey> only) {
        var held = new LinkedHashMap<AEItemKey, Long>();

        for (var mount : mounts) {
            if (mount.bulk()) {
                continue;
            }

            var counter = new KeyCounter();
            mount.storage().getAvailableStacks(counter);

            for (var entry : counter) {
                if (entry.getKey() instanceof AEItemKey key && entry.getLongValue() > 0
                        && (only == null || only.contains(key))) {
                    held.merge(key, entry.getLongValue(), Long::sum);
                }
            }
        }

        return held;
    }

    /**
     * 已接管物品 → 目标盘映射（精确匹配优先，其次装卡盘的链变体）。
     */
    private static Map<AEItemKey, PanelStack> claimMap(List<PanelStack> stacks) {
        var claim = new HashMap<AEItemKey, PanelStack>();

        for (var ps : stacks) {
            var stack = ps.stack();

            if (BulkCellState.isMismatched(stack)) {
                continue;
            }

            var filter = BulkCellState.filter(stack);

            if (filter == null) {
                continue;
            }

            var stored = BulkCellState.stored(stack);
            claim.putIfAbsent(filter, ps);

            if (stored != null) {
                claim.putIfAbsent(stored, ps);
            }

            if (BulkCellState.hasCompressionCard(stack)) {
                var chain = BulkCellState.chainOf(stack);

                for (int i = 0; i < chain.size(); i++) {
                    claim.putIfAbsent(AEItemKey.of(chain.getItem(i)), ps);
                }
            }
        }

        return claim;
    }

    public static void transferAll(BulkTerminalPart part, @Nullable ServerPlayer player, long threshold) {
        var grid = part.getGrid();

        if (grid == null) {
            return;
        }

        var source = actionSource(part);
        var claim = claimMap(enumerate(part));

        if (claim.isEmpty()) {
            feedback(part, player, List.of(), List.of(
                    Component.translatable("gui.bulkterminal.info_nothing_claimed").getString()));
            return;
        }

        var mounts = collectMounts(grid);

        // 普通存储里各已接管物品的存量（大宗盘持有量不算）
        var regularHeld = regularHeld(mounts, claim.keySet());

        // 逐个普通挂载抽取（绕开网络路由，避免误从大宗盘抽回）
        Map<AEItemKey, Long> extracted = new LinkedHashMap<>();
        Map<AEItemKey, Long> allowance = new HashMap<>();

        for (var mount : mounts) {
            if (mount.bulk()) {
                continue;
            }

            var counter = new KeyCounter();
            mount.storage().getAvailableStacks(counter);

            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey key) || !claim.containsKey(key)) {
                    continue;
                }

                long movable = allowance.computeIfAbsent(key,
                        k -> Math.max(0, regularHeld.getOrDefault(k, 0L) - threshold));

                if (movable <= 0) {
                    continue;
                }

                long take = Math.min(entry.getLongValue(), movable);
                long got = mount.storage().extract(key, take, Actionable.MODULATE, source);

                if (got > 0) {
                    extracted.merge(key, got, Long::sum);
                    allowance.put(key, movable - got);
                }
            }
        }

        var moved = new ArrayList<String>();
        var warn = new ArrayList<String>();

        // 换算并装入目标大宗盘（cell.insert 会按面额换算成基本单位）
        for (var entry : extracted.entrySet()) {
            var key = entry.getKey();
            var target = claim.get(key);
            long amount = entry.getValue();

            var cellInv = StorageCells.getCellInventory(target.stack(), null);

            if (!(cellInv instanceof BulkCellInventory bulk)) {
                warn.add(Component.translatable("gui.bulkterminal.err_target_unmounted",
                        key.getDisplayName().getString()).getString());
                continue;
            }

            // 用目标盘的新鲜实例插入（组件即真相；随后 refreshAndSave 让宿主重建挂载实例）。
            // 不能用 entry.cell()：同一宿主的所有挂载共享一个节点，条目映射无法对应到具体盘。
            long accepted = bulk.insert(key, amount, Actionable.MODULATE, source);

            if (accepted > 0) {
                moved.add(Component.translatable("gui.bulkterminal.status_moved_item",
                        key.getDisplayName().getString(), accepted, target.hostDesc()).getString());
            }

            if (accepted < amount) {
                var network = grid.getStorageService().getInventory();
                long rest = amount - accepted;
                long putBack = network.insert(key, rest, Actionable.MODULATE, source);

                if (putBack < rest) {
                    LOGGER.error("一键转移回存失败：{} 个 {} 无法放回网络", rest - putBack, key);
                }

                warn.add(Component.translatable("gui.bulkterminal.status_insert_rejected",
                        key.getDisplayName().getString(), rest - putBack).getString());
            }
        }

        // 低于阈值的说明
        for (var entry : regularHeld.entrySet()) {
            long amount = entry.getValue();

            if (amount > 0 && amount - threshold <= 0
                    && !extracted.containsKey(entry.getKey())) {
                warn.add(Component.translatable("gui.bulkterminal.status_below_threshold",
                        entry.getKey().getDisplayName().getString(), amount, threshold).getString());
            }
        }

        // 未被接管的种类数（汇总一句）
        long unclaimedKinds = 0;

        for (var mount : mounts) {
            if (mount.bulk()) {
                continue;
            }

            var counter = new KeyCounter();
            mount.storage().getAvailableStacks(counter);

            for (var entry : counter) {
                if (entry.getKey() instanceof AEItemKey key && !claim.containsKey(key)
                        && entry.getLongValue() > 0) {
                    unclaimedKinds++;
                }
            }
        }

        if (unclaimedKinds > 0) {
            warn.add(Component.translatable("gui.bulkterminal.status_unclaimed", unclaimedKinds).getString());
        }

        invalidate(part);

        if (player == null) {
            // 无玩家场景（自动化测试）：结果走日志
            moved.forEach(line -> LOGGER.info("[转移] {}", line));
            warn.forEach(line -> LOGGER.info("[转移提示] {}", line));
            sendSnapshot(part, player);
        } else {
            feedback(part, player, moved, warn);
        }
    }

    // ===== 一键转移（Shift 自动版） =====

    /**
     * Shift+点击「一键转移」的固定保留阈值：普通存储里每种物品保留 20000，超出部分自动转移。
     */
    public static final long AUTO_TRANSFER_KEEP = 20000;

    /**
     * Shift+点击「一键转移」的单种物品转移量上限：达到 2.1G（约 int 上限 2,147,483,647）
     * 时不再转移该物品，并在聊天框告知玩家。
     */
    public static final long AUTO_TRANSFER_LIMIT = 2_100_000_000L;

    /**
     * Shift+点击「一键转移」：自动转移普通存储中超过 {@link #AUTO_TRANSFER_KEEP} 的物品到大宗盘。
     *
     * <p>转移逻辑镜像存入链路（{@link BulkOnlyStorage#insert}）：已被某块盘接管的物品直接转移；
     * 未被接管的先给空闲盘打标记（选盘顺序与存入一致：优先级最高的空闲盘），标记完成后再转移。
     *
     * <p>单种物品的转移量达到 {@link #AUTO_TRANSFER_LIMIT}（2.1G）时跳过该物品并在聊天框告知。
     */
    public static void transferAuto(BulkTerminalPart part, @Nullable ServerPlayer player) {
        var grid = part.getGrid();

        if (grid == null) {
            return;
        }

        var source = actionSource(part);
        var stacks = enumerate(part);
        var claim = claimMap(stacks);
        var mounts = collectMounts(grid);

        // 普通存储里全部物品的存量（大宗盘持有量不算）
        var regularHeld = regularHeld(mounts, null);

        var moved = new ArrayList<String>();
        var warn = new ArrayList<String>();

        // 空闲盘（按优先级降序，镜像存入链路 scanner.entries() 的优先级语义）
        var freeCells = new ArrayList<PanelStack>();

        for (var ps : stacks) {
            if (ps.entry() != null && BulkCellState.canFitInsideCell(ps.stack())) {
                freeCells.add(ps);
            }
        }

        freeCells.sort(Comparator.comparingInt(PanelStack::priority).reversed());

        // 第一步：为每种超额物品定目标——已接管的直接进计划；未接管的先占用一块空闲盘打标记。
        // 转移量达到 2.1G 的直接跳过并提示玩家。
        var plan = new LinkedHashMap<AEItemKey, Long>();
        int freeIdx = 0;
        int marked = 0;

        for (var e : regularHeld.entrySet()) {
            var key = e.getKey();
            long movable = e.getValue() - AUTO_TRANSFER_KEEP;

            if (movable <= 0) {
                continue; // 未超过保留阈值，保留不动
            }

            if (movable >= AUTO_TRANSFER_LIMIT) {
                warn.add(Component.translatable("gui.bulkterminal.status_too_large",
                        key.getDisplayName().getString(), e.getValue()).getString());
                continue;
            }

            if (claim.containsKey(key)) {
                plan.put(key, movable);
                continue;
            }

            if (freeIdx >= freeCells.size()) {
                warn.add(Component.translatable("gui.bulkterminal.warn_no_free_cell",
                        key.getDisplayName().getString()).getString());
                continue;
            }

            var free = freeCells.get(freeIdx++);

            if (!(free.stack().getItem() instanceof BulkCellItem cellItem)) {
                continue;
            }

            // 与存入链路同源（BulkCellMarker.mark）：写 ConfigInventory 第 0 格
            // （changeListener 自动回写组件），随后强制宿主重建单元格实例，让新标记立刻生效
            cellItem.getConfigInventory(free.stack()).setStack(0, new GenericStack(key, 1));
            marker(part).refreshAndSave(free.entry());
            claim.put(key, free);
            plan.put(key, movable);
            marked++;
        }

        if (marked > 0) {
            moved.add(Component.translatable("gui.bulkterminal.info_auto_marked", marked).getString());
        }

        if (plan.isEmpty()) {
            if (warn.isEmpty()) {
                moved.add(Component.translatable("gui.bulkterminal.info_nothing_over_threshold",
                        AUTO_TRANSFER_KEEP).getString());
            }

            invalidate(part);
            feedback(part, player, moved, warn);
            return;
        }

        // 第二步：逐个普通挂载抽取（绕开网络路由，避免误从大宗盘抽回）
        var extracted = new LinkedHashMap<AEItemKey, Long>();
        var allowance = new HashMap<>(plan);

        for (var mount : mounts) {
            if (mount.bulk()) {
                continue;
            }

            var counter = new KeyCounter();
            mount.storage().getAvailableStacks(counter);

            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey key) || !claim.containsKey(key)) {
                    continue;
                }

                long movable = allowance.getOrDefault(key, 0L);

                if (movable <= 0) {
                    continue;
                }

                long take = Math.min(entry.getLongValue(), movable);
                long got = mount.storage().extract(key, take, Actionable.MODULATE, source);

                if (got > 0) {
                    extracted.merge(key, got, Long::sum);
                    allowance.put(key, movable - got);
                }
            }
        }

        // 第三步：换算并装入目标大宗盘（cell.insert 会按面额换算成基本单位）
        for (var entry : extracted.entrySet()) {
            var key = entry.getKey();
            var target = claim.get(key);
            long amount = entry.getValue();

            var cellInv = StorageCells.getCellInventory(target.stack(), null);

            if (!(cellInv instanceof BulkCellInventory bulk)) {
                warn.add(Component.translatable("gui.bulkterminal.err_target_unmounted",
                        key.getDisplayName().getString()).getString());
                continue;
            }

            long accepted = bulk.insert(key, amount, Actionable.MODULATE, source);

            if (accepted > 0) {
                moved.add(Component.translatable("gui.bulkterminal.status_moved_item",
                        key.getDisplayName().getString(), accepted, target.hostDesc()).getString());
            }

            if (accepted < amount) {
                var network = grid.getStorageService().getInventory();
                long rest = amount - accepted;
                long putBack = network.insert(key, rest, Actionable.MODULATE, source);

                if (putBack < rest) {
                    LOGGER.error("一键转移回存失败：{} 个 {} 无法放回网络", rest - putBack, key);
                }

                warn.add(Component.translatable("gui.bulkterminal.status_insert_rejected",
                        key.getDisplayName().getString(), rest - putBack).getString());
            }
        }

        invalidate(part);

        if (player == null) {
            // 无玩家场景（自动化测试）：结果走日志
            moved.forEach(line -> LOGGER.info("[自动转移] {}", line));
            warn.forEach(line -> LOGGER.info("[自动转移提示] {}", line));
            sendSnapshot(part, player);
        } else {
            feedback(part, player, moved, warn);
        }
    }

    // ===== 杂项 =====

    /**
     * 按会话行号定位盘。行号由快照下发、客户端点击时原样回传；
     * 界面会话内行号与盘的对应关系不变（见 {@link #orderedStacks}），
     * 与行在列表里的位置解耦，档位变化不会让它指向另一块盘。
     */
    @Nullable
    private static PanelStack stackByRowId(BulkTerminalPart part, @Nullable ServerPlayer player, int rowId) {
        var ordered = orderedStacks(part, player);

        for (var ps : ordered.stacks()) {
            if (ordered.rowIds().getOrDefault(ps.id(), -1) == rowId) {
                return ps;
            }
        }

        return null;
    }

    private static BulkCellMarker marker(BulkTerminalPart part) {
        var storage = part.getBulkInventory();

        if (storage == null) {
            throw new IllegalStateException("终端存储视图未初始化");
        }

        return storage.marker();
    }

    private static void invalidate(BulkTerminalPart part) {
        var storage = part.getBulkInventory();

        if (storage != null) {
            storage.invalidate();
        }
    }

    private static IActionSource actionSource(BulkTerminalPart part) {
        var host = part.getHost();
        return host != null ? IActionSource.ofMachine(part) : new BaseActionSource();
    }

    @Nullable
    private static appeng.api.storage.cells.StorageCell unwrap(appeng.api.storage.MEStorage inv) {
        if (inv instanceof DriveWatcher watcher && watcher.getCell() != null) {
            return watcher.getCell();
        }

        if (inv instanceof appeng.api.storage.cells.StorageCell cell) {
            return cell;
        }

        return null;
    }
}
