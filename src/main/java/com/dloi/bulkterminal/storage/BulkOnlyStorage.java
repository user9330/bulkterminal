package com.dloi.bulkterminal.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.network.chat.Component;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;

import com.dloi.bulkterminal.BulkTerminal;
import com.dloi.bulkterminal.part.BulkTerminalPart;

/**
 * 大宗终端看到的存储视图：把「只读写大宗盘」这条规则集中在这里实现。
 *
 * <ul>
 *   <li>可见性（R2 + R4）：只有被某个大宗盘标记或存有的物品才出现在终端里，
 *       显示数量取网络总量。</li>
 *   <li>存入（R5）：先乐观尝试直接写入，全部失败后才找最高优先级的空盘打标记并重试。</li>
 *   <li>提取（R4）：优先从大宗盘取，不足部分从全网（含普通盘）补足。</li>
 * </ul>
 */
public final class BulkOnlyStorage implements MEStorage {
    private static final Component DESCRIPTION =
            Component.translatable("bulkterminal.storage.description");

    private final BulkTerminalPart part;
    private final BulkCellScanner scanner;
    private final BulkCellMarker marker;

    public BulkOnlyStorage(BulkTerminalPart part) {
        this.part = part;
        this.scanner = new BulkCellScanner(part::getGrid, part::getCurrentTick);
        this.marker = new BulkCellMarker();
    }

    public void invalidate() {
        scanner.invalidate();
    }

    /**
     * 当前网络中所有大宗盘的快照条目（按优先级降序），供管理界面与操作链路使用。
     */
    public List<MountEntry> entries() {
        return scanner.entries();
    }

    public BulkCellMarker marker() {
        return marker;
    }

    @Override
    public Component getDescription() {
        return DESCRIPTION;
    }

    /**
     * 三个公开入口（getAvailableStacks / insert / extract）都包了异常防护：
     * 本模组发生异常时把错误原因（含堆栈）写入日志文件并安全降级，
     * 不让异常沿 AE2 的网络服务链上抛导致崩服。
     */
    @Override
    public void getAvailableStacks(KeyCounter out) {
        try {
            getAvailableStacksInternal(out);
        } catch (Throwable t) {
            BulkTerminal.LOGGER.error("[大宗终端] 枚举可用物品时发生异常", t);
        }
    }

    private void getAvailableStacksInternal(KeyCounter out) {
        var grid = part.getGrid();

        if (grid == null) {
            return;
        }

        var network = grid.getStorageService().getCachedInventory();

        if (network == null || network.isEmpty()) {
            return;
        }

        Set<AEKey> keys = network.keySet();

        for (var key : new ArrayList<>(keys)) {
            long amount = network.get(key);

            if (amount > 0 && isManaged(key)) {
                out.add(key, amount);
            }
        }
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        try {
            return insertInternal(what, amount, mode, source);
        } catch (Throwable t) {
            BulkTerminal.LOGGER.error("[大宗终端] 存入 {} ×{} 时发生异常", what, amount, t);
            return 0;
        }
    }

    private long insertInternal(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || !(what instanceof AEItemKey)) {
            return 0;
        }

        // 阶段一：乐观尝试。已标记 / 已存量的盘会直接命中，常规路径零额外开销
        for (var entry : scanner.entries()) {
            long inserted = entry.cell().insert(what, amount, mode, source);

            if (inserted > 0) {
                return inserted;
            }
        }

        // 模拟态绝不实际打标记，但要向调用方「承诺容量」：
        // AE2 的 poweredInsert 等转移入口是「先 SIMULATE 再 MODULATE」——
        // SIMULATE 返回 0 会让 MODULATE 饿死，自动标记永远没有执行机会。
        // 因此只要存在可标记的空闲盘，这里就报告放得下；真正的标记与写入
        // 只发生在随后的 MODULATE 调用里。
        if (mode == Actionable.SIMULATE) {
            if (part.isAutoMarkEnabled() && hasMarkableTarget()) {
                return amount;
            }

            return 0;
        }

        // 自动标记可由玩家在「大宗盘管理」界面关闭；关闭后退回 MEGA 原版行为（未标记拒收）
        if (!part.isAutoMarkEnabled()) {
            BulkTerminal.LOGGER.info("[自动标记] 开关处于关闭状态，存入 {} 失败", what);
            return 0;
        }

        // 强制重扫一次，排除缓存过期导致的误判
        scanner.invalidate();

        // 重复标记检测：该物品已被某块大宗盘接管（filter、存量，或装压缩卡时的同压缩链）
        // 但写入失败，说明盘处于异常状态（如 filter 与存量不一致），不能再给别的空盘打同样的标记
        if (marker.isAlreadyMarkedOrStoring(scanner.entries(), (AEItemKey) what)) {
            BulkTerminal.LOGGER.info(
                    "[自动标记] {} 已被某块大宗盘接管但写入失败（盘状态异常），跳过重复标记", what);
            return 0;
        }

        // 阶段二：没有可承接的盘，取优先级最高的空闲盘打标记。
        // 空闲判定走组件直读而非挂载实例：实例的 filter 是构造时快照，
        // 组件才是唯一真相（locateBulkStack 与 mark 用同一谓词，选盘和写标必然一致）
        var target = scanner.entries().stream()
                .filter(marker::canMark)
                .findFirst()
                .orElse(null);

        if (target == null) {
            BulkTerminal.LOGGER.info(
                    "[自动标记] 网络中没有 canFitInsideCell 的空闲大宗盘（共 {} 块大宗盘），存入 {} 失败",
                    scanner.entries().size(), what);
            return 0;
        }

        BulkTerminal.LOGGER.info("[自动标记] 选中空闲盘（优先级 {}）写入标记：{}",
                target.priority(), what);

        if (!marker.mark(target, what)) {
            BulkTerminal.LOGGER.info("[自动标记] 标记写入失败（宿主 ItemStack 定位失败，宿主类型：{}）",
                    target.node().getOwner() == null ? "null" : target.node().getOwner().getClass().getName());
            return 0;
        }

        BulkTerminal.LOGGER.info("[自动标记] 标记已写入并触发重新挂载");

        scanner.invalidate();

        // 阶段三：重试。此时宿主已经重新挂载，交给新实例写入
        for (var entry : scanner.entries()) {
            long inserted = entry.cell().insert(what, amount, mode, source);

            if (inserted > 0) {
                return inserted;
            }
        }

        BulkTerminal.LOGGER.info("[自动标记] 标记后重试存入仍失败（重新挂载未生效？）：{}", what);
        return 0;
    }

    /**
     * 是否存在「空闲、可打标记」的大宗盘（只读判断，供模拟阶段使用；组件直读）。
     */
    private boolean hasMarkableTarget() {
        for (var entry : scanner.entries()) {
            if (marker.canMark(entry)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        try {
            return extractInternal(what, amount, mode, source);
        } catch (Throwable t) {
            BulkTerminal.LOGGER.error("[大宗终端] 提取 {} ×{} 时发生异常", what, amount, t);
            return 0;
        }
    }

    private long extractInternal(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || !isManaged(what)) {
            return 0;
        }

        long remaining = amount;

        // 阶段一：优先从大宗盘取
        for (var entry : scanner.entries()) {
            remaining -= entry.cell().extract(what, remaining, mode, source);

            if (remaining <= 0) {
                return amount;
            }
        }

        // 阶段二：大宗盘不足，从全网（含普通盘）补足，这正是 R4 允许的语义
        var network = networkStorage();

        if (network != null) {
            remaining -= network.extract(what, remaining, mode, source);
        }

        return amount - remaining;
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        return isManaged(what);
    }

    /**
     * 该物品是否被大宗体系接管：存在某个大宗盘标记或存有它。
     */
    private boolean isManaged(AEKey what) {
        if (!(what instanceof AEItemKey)) {
            return false;
        }

        for (var entry : scanner.entries()) {
            if (entry.cell().isPreferredStorageFor(what, null)) {
                return true;
            }
        }

        return false;
    }

    private MEStorage networkStorage() {
        IGrid grid = part.getGrid();
        return grid == null ? null : grid.getStorageService().getInventory();
    }
}
