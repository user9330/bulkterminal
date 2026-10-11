package com.dloi.bulkterminal.storage;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.item.ItemStack;

import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.parts.AEBasePart;

import com.dloi.bulkterminal.BulkTerminal;

import gripe._90.megacells.definition.MEGAComponents;
import gripe._90.megacells.definition.MEGAItems;
import gripe._90.megacells.item.cell.BulkCellItem;
import gripe._90.megacells.item.part.CellDockPart;
import gripe._90.megacells.misc.CompressionService;

/**
 * 负责「给未标记的大宗盘打 / 清标记」这一步，以及读取盘位的公开快照信息。
 *
 * <p>链路：定位 ItemStack → 写入 / 清除 ConfigInventory（自动回写组件）→ 让宿主重新挂载 → 触发宿主存档。
 *
 * <p>注意：只用公开 API。MEGA 的 {@code getFilterItem()} / {@code getStoredItem()} 是包内可见，
 * 因此盘的 filter / 存量 / 余量一律直接读 ItemStack 组件（配置槽 0、{@code BULK_CELL_ITEM}、
 * {@code BULK_CELL_UNIT_COUNT}），与 {@code BulkCellInventory} 构造时读取的内容逐项对应。
 * 判定不经过任何单元格实例——驱动器会缓存实例（AE2 19.2.18+ 的 isCached），实例的
 * filter 是构造时快照，组件才是唯一真相；这也对 MEGA 未来给 Handler 加缓存免疫。
 */
public final class BulkCellMarker {
    private static final Logger LOGGER = LoggerFactory.getLogger(BulkTerminal.MOD_ID + "/Marker");

    /**
     * 判断网络中是否已有大宗盘接管了 {@code what}，即「重复标记检测」。
     *
     * <p>判定镜像 MEGA {@code BulkCellInventory.insert} 的接受条件：
     * filter 或存量精确等于该物品；若盘装有压缩卡，再按压缩链匹配（同链变体也会被该盘接收）。
     * 未装压缩卡的盘只认精确物品——此时给另一块空盘打同链其他变体的标记是合法的。
     *
     * <p>用于存入链路：若某块盘已经接管了该物品但写入失败（例如 filter 与存量不一致的
     * 异常状态），此时再给另一块空盘打同样的标记，只会让同一物品散落到多块盘上，
     * 因此直接放弃并交给上层提示。
     */
    public boolean isAlreadyMarkedOrStoring(List<MountEntry> entries, AEItemKey what) {
        var visitedNodes = new HashSet<IGridNode>();

        for (var entry : entries) {
            if (!visitedNodes.add(entry.node())) {
                continue;
            }

            for (var stack : locateAllBulkStacks(entry)) {
                if (claimsItem(stack, what)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 该盘是否会接收 {@code what} 的存入（按组件直读判定，语义对齐 MEGA insert）。
     */
    private static boolean claimsItem(ItemStack stack, AEItemKey what) {
        return BulkCellState.claims(stack, what);
    }

    /**
     * 列出宿主容器中的所有大宗盘 ItemStack（不限定状态）。包内可见，供管理操作复用。
     */
    List<ItemStack> locateAllBulkStacks(MountEntry entry) {
        var result = new ArrayList<ItemStack>();

        try {
            var owner = entry.node().getOwner();

            if (owner instanceof DriveBlockEntity drive) {
                var inv = drive.getInternalInventory();

                for (int i = 0; i < inv.size(); i++) {
                    var stack = inv.getStackInSlot(i);

                    if (!stack.isEmpty() && stack.getItem() instanceof BulkCellItem) {
                        result.add(stack);
                    }
                }
            } else if (owner instanceof CellDockPart dock) {
                var stack = dock.getCellInventory().getStackInSlot(0);

                if (!stack.isEmpty() && stack.getItem() instanceof BulkCellItem) {
                    result.add(stack);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("枚举宿主大宗盘时出错", t);
        }

        return result;
    }

    /**
     * 给盘位写入 filter 标记，并让新标记立刻生效。
     *
     * @return 标记是否成功完成
     */
    public boolean mark(MountEntry entry, AEKey what) {
        if (!(what instanceof AEItemKey itemKey)) {
            return false;
        }

        var stack = locateBulkStack(entry, BulkCellState::canFitInsideCell);

        if (stack == null || !(stack.getItem() instanceof BulkCellItem cellItem)) {
            LOGGER.debug("找不到可标记的空大宗盘，宿主类型可能未适配：{}", entry.node().getOwner());
            return false;
        }

        // ConfigInventory 带 changeListener，写入第 0 格即完成组件持久化，无需手动 set 组件
        cellItem.getConfigInventory(stack).setStack(0, new GenericStack(itemKey, 1));

        refreshAndSave(entry);
        return true;
    }

    /**
     * 清除一块「已标记但无存量」的大宗盘的标记。
     *
     * @return 是否清除成功
     */
    public boolean clear(MountEntry entry) {
        var stack = locateBulkStack(entry, BulkCellState::isMarkedAndEmpty);

        if (stack == null || !(stack.getItem() instanceof BulkCellItem cellItem)) {
            LOGGER.debug("找不到可清除标记的大宗盘：{}", entry.node().getOwner());
            return false;
        }

        // setStack(0, null) 走 null 分支清空该配置格；changeListener 自动回写组件
        cellItem.getConfigInventory(stack).setStack(0, null);

        refreshAndSave(entry);
        return true;
    }

    /**
     * 读取盘位当前标记的物品（未标记或无法读取时返回 null）。
     */
    @Nullable
    public AEItemKey readMarkedItem(MountEntry entry) {
        var stack = locateBulkStack(entry, s -> true);

        if (stack != null && stack.getItem() instanceof BulkCellItem) {
            var filter = ((BulkCellItem) stack.getItem()).getConfigInventory(stack).getKey(0);

            if (filter instanceof AEItemKey key) {
                return key;
            }
        }

        return null;
    }

    /**
     * 宿主内是否已有大宗盘装着存量。
     */
    public boolean hasStoredContent(MountEntry entry) {
        return locateBulkStack(entry, BulkCellState::hasStoredStacks) != null;
    }

    /**
     * 宿主内是否存在「空闲、可打标记」的大宗盘（组件直读，不依赖挂载实例）。
     */
    public boolean canMark(MountEntry entry) {
        return locateBulkStack(entry, BulkCellState::canFitInsideCell) != null;
    }

    void refreshAndSave(MountEntry entry) {
        // AE2 19.2.18+ 的驱动器与 MEGA 单元坞都会缓存已构建的单元格实例（isCached），
        // refreshNodeStorageProvider 触发的重挂载不会重建 BulkCellInventory，
        // 新写入的 filter 要等重新加载世界才生效。这里必须先强制丢缓存。
        forceCellRebuild(entry.node().getOwner());

        // BulkCellInventory 在构造时才快照 filterItem，只改组件不会刷新内存中的实例
        entry.node().getGrid().getStorageService().refreshNodeStorageProvider(entry.node());
        markHostDirty(entry);
    }

    /**
     * 强制宿主丢弃单元格实例缓存，下次挂载时按 ItemStack 组件重建 {@link BulkCellInventory}。
     *
     * <p>旧版本（AE2 &lt; 19.2.18）没有该缓存字段，反射找不到时直接忽略——
     * 那时 {@code updateState()} 本身就是无条件重建的。
     */
    private static void forceCellRebuild(@Nullable Object owner) {
        if (owner == null) {
            return;
        }

        try {
            var field = owner.getClass().getDeclaredField("isCached");
            field.setAccessible(true);
            field.setBoolean(owner, false);
        } catch (NoSuchFieldException ignored) {
            // 无缓存机制的版本，无需处理
        } catch (Throwable t) {
            LOGGER.warn("丢弃宿主单元格缓存失败：{}", owner.getClass().getName(), t);
        }
    }

    /**
     * 按谓词在宿主容器中定位一块大宗盘对应的 ItemStack。
     *
     * <p>驱动器优先级是驱动器级别而非槽位级别，且宿主挂载时会跳过空槽位，
     * 因此不能用 mount 序号反推槽位；同一宿主内满足谓词的任一盘即可。
     * 谓词只看 ItemStack 组件，不构建单元格实例。
     */
    @Nullable
    ItemStack locateBulkStack(MountEntry entry, Predicate<ItemStack> predicate) {
        try {
            var owner = entry.node().getOwner();

            if (owner instanceof DriveBlockEntity drive) {
                var inv = drive.getInternalInventory();

                for (int i = 0; i < inv.size(); i++) {
                    var stack = inv.getStackInSlot(i);

                    if (isBulkCell(stack, predicate)) {
                        return stack;
                    }
                }
            } else if (owner instanceof CellDockPart dock) {
                var stack = dock.getCellInventory().getStackInSlot(0);

                if (isBulkCell(stack, predicate)) {
                    return stack;
                }
            }
        } catch (Throwable t) {
            // 宿主可能已经跨维度 / 被卸载，取到的 owner 未必有效
            LOGGER.warn("回查大宗盘所属容器时出错，本次操作中止", t);
        }

        return null;
    }

    private static boolean isBulkCell(ItemStack stack, Predicate<ItemStack> predicate) {
        return !stack.isEmpty()
                && stack.getItem() instanceof BulkCellItem
                && predicate.test(stack);
    }

    private void markHostDirty(MountEntry entry) {
        var owner = entry.node().getOwner();

        if (owner instanceof DriveBlockEntity drive) {
            drive.saveChanges();
        } else if (owner instanceof AEBasePart part && part.getHost() != null) {
            part.getHost().markForSave();
        }
    }
}
