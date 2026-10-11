package com.dloi.bulkterminal.storage;

import java.math.BigInteger;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEItemKey;

import gripe._90.megacells.definition.MEGAComponents;
import gripe._90.megacells.definition.MEGAItems;
import gripe._90.megacells.item.cell.BulkCellItem;
import gripe._90.megacells.misc.CompressionChain;
import gripe._90.megacells.misc.CompressionService;

/**
 * 大宗盘状态的组件直读工具。
 *
 * <p>所有判定都直接读 ItemStack 组件，与 {@code BulkCellInventory} 构造语义逐项对应，
 * 不构建也不信任任何单元格实例——组件是唯一真相（见 {@link BulkCellMarker} 的说明）。
 */
public final class BulkCellState {
    private BulkCellState() {
    }

    /** 配置槽 0 的标记物品（未标记返回 null）。 */
    @Nullable
    public static AEItemKey filter(ItemStack stack) {
        if (!(stack.getItem() instanceof BulkCellItem cellItem)) {
            return null;
        }

        var key = cellItem.getConfigInventory(stack).getKey(0);
        return key instanceof AEItemKey item ? item : null;
    }

    /** 存量物品组件（无存量返回 null）。 */
    @Nullable
    public static AEItemKey stored(ItemStack stack) {
        var key = stack.get(MEGAComponents.BULK_CELL_ITEM);
        return key instanceof AEItemKey item ? item : null;
    }

    /** 决定压缩链的物品：存量优先，其次 filter（与 MEGA 的 determiningItem 一致）。 */
    @Nullable
    public static AEItemKey determining(ItemStack stack) {
        var stored = stored(stack);
        return stored != null ? stored : filter(stack);
    }

    public static BigInteger unitCount(ItemStack stack) {
        return stack.getOrDefault(MEGAComponents.BULK_CELL_UNIT_COUNT, BigInteger.ZERO);
    }

    /** 空闲可标记：无 filter、无存量、余量 &lt;= 0（镜像 MEGA canFitInsideCell）。 */
    public static boolean canFitInsideCell(ItemStack stack) {
        return filter(stack) == null && stored(stack) == null && unitCount(stack).signum() < 1;
    }

    /** 是否装着存量（镜像 MEGA getStatus 的非空判定）。 */
    public static boolean hasStoredStacks(ItemStack stack) {
        var stored = stored(stack);
        return stored != null && unitCount(stack).signum() > 0;
    }

    /** 已标记但无存量（可清除标记的状态）。 */
    public static boolean isMarkedAndEmpty(ItemStack stack) {
        if (canFitInsideCell(stack) || hasStoredStacks(stack)) {
            return false;
        }

        return filter(stack) != null;
    }

    /** 是否装有压缩卡。 */
    public static boolean hasCompressionCard(ItemStack stack) {
        return stack.getItem() instanceof BulkCellItem cellItem
                && cellItem.getUpgrades(stack).isInstalled(MEGAItems.COMPRESSION_CARD);
    }

    /** 是否可再装压缩卡（每盘上限 1 张；异常盘先修复再装）。 */
    public static boolean canInstallCard(ItemStack stack) {
        return stack.getItem() instanceof BulkCellItem
                && !hasCompressionCard(stack)
                && !isMismatched(stack);
    }

    /** 该盘的压缩链（由 determining 物品决定；无链为空链）。 */
    public static CompressionChain chainOf(ItemStack stack) {
        return CompressionService.getChain(determining(stack));
    }

    /** 档位上限 = 链长 - 1（无链时 0）。 */
    public static int maxCutoff(ItemStack stack) {
        return Math.max(0, chainOf(stack).size() - 1);
    }

    /** 当前档位（组件值钳制到合法范围）。 */
    public static int cutoff(ItemStack stack) {
        int max = maxCutoff(stack);
        int recorded = stack.getOrDefault(MEGAComponents.BULK_CELL_COMPRESSION_CUTOFF, max);
        return recorded < 0 ? max : Math.min(recorded, max);
    }

    /** 档位是否有切换的意义（链长 &gt; 1 且盘状态正常）。 */
    public static boolean canCycleCutoff(ItemStack stack) {
        return !isMismatched(stack) && chainOf(stack).size() > 1;
    }

    /**
     * filter 与存量不一致（镜像 MEGA isFilterMismatched 的只读部分）：
     * 有存量但 filter 缺失，或 filter 不在存量的压缩链内。
     */
    public static boolean isMismatched(ItemStack stack) {
        var stored = stored(stack);

        if (stored == null) {
            return false;
        }

        var filter = filter(stack);

        if (stored.equals(filter)) {
            return false;
        }

        if (filter == null) {
            return true;
        }

        return !CompressionService.getChain(stored).containsVariant(filter);
    }

    /**
     * 该盘是否会接收 {@code what}（镜像 MEGA insert 的接受条件）：
     * filter / 存量精确匹配，或装有压缩卡且属于同一条压缩链。
     */
    public static boolean claims(ItemStack stack, AEItemKey what) {
        var filter = filter(stack);

        if (what.equals(filter)) {
            return true;
        }

        var stored = stored(stack);

        if (what.equals(stored)) {
            return true;
        }

        if (!hasCompressionCard(stack)) {
            return false;
        }

        var determining = stored != null ? stored : filter;

        if (determining == null) {
            return false;
        }

        return CompressionService.getChain(determining).containsVariant(what);
    }

    /** 链内物品对基本单位的倍率（不在链内为 1）。 */
    public static BigInteger chainFactor(ItemStack stack, AEItemKey variant) {
        return chainOf(stack).unitFactor(variant);
    }
}
