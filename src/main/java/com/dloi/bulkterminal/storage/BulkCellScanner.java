package com.dloi.bulkterminal.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGrid;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.api.storage.cells.StorageCell;
import appeng.me.storage.DriveWatcher;

import gripe._90.megacells.item.cell.BulkCellInventory;

/**
 * PMCS（优先级挂载收集扫描）的扫描器 + 带 TTL 的结果缓存。
 *
 * <p>做法：遍历网格内所有 {@link IStorageProvider}，让它把已挂载的存储交给我们自己的收集器，
 * 收集时顺带拿到挂载优先级，最后按优先级降序排序。这样不依赖任何具体宿主类型，
 * 第三方存储设备也能参与（只在打标记那一步才需要回查宿主类型）。
 *
 * <p>缓存 TTL 为 10 tick：终端界面每 tick 都会拉取可用物品列表，不能每次全量扫描。
 */
public final class BulkCellScanner {
    /**
     * 缓存有效期，单位 tick。
     */
    private static final int CACHE_TTL_TICKS = 10;

    private final Supplier<IGrid> grid;
    private final LongSupplier tickSource;

    @Nullable
    private List<MountEntry> cache;

    private long scannedTick = Long.MIN_VALUE;

    public BulkCellScanner(Supplier<IGrid> grid, LongSupplier tickSource) {
        this.grid = grid;
        this.tickSource = tickSource;
    }

    /**
     * 按优先级降序返回网络中的大宗盘快照。可能为空，永不为 null。
     */
    public List<MountEntry> entries() {
        var current = cache;

        if (current == null || tickSource.getAsLong() - scannedTick >= CACHE_TTL_TICKS) {
            current = scan(grid.get());
            cache = current;
            scannedTick = tickSource.getAsLong();
        }

        return current;
    }

    /**
     * 使缓存失效。打标记成功后必须调用：宿主会重新挂载，旧快照指向的 {@link BulkCellInventory} 已经过期。
     */
    public void invalidate() {
        cache = null;
        scannedTick = Long.MIN_VALUE;
    }

    private static List<MountEntry> scan(@Nullable IGrid grid) {
        if (grid == null) {
            return List.of();
        }

        var result = new ArrayList<MountEntry>();

        // 注意：不能用 grid.getMachineNodes(IStorageProvider.class)。
        // Grid 内部的机器表以 owner.getClass()（具体类）为键，用接口查询永远得到空集合；
        // 必须像 AE2 自己的 StorageService 一样遍历全部节点再逐个 getService。
        for (var node : grid.getNodes()) {
            var provider = node.getService(IStorageProvider.class);

            if (provider == null) {
                continue;
            }

            provider.mountInventories((inv, priority) -> {
                var cell = unwrap(inv);

                if (cell instanceof BulkCellInventory bulk) {
                    result.add(new MountEntry(node, provider, priority, bulk));
                }
            });
        }

        // 优先级降序。列表遍历顺序在同一个 tick 内稳定，因此同优先级的结果是确定性的
        result.sort(Comparator.comparingInt(MountEntry::priority).reversed());
        return result;
    }

    /**
     * 从挂载出来的 {@link MEStorage} 里取出真正的磁盘实例。
     */
    @Nullable
    private static StorageCell unwrap(MEStorage inv) {
        if (inv instanceof DriveWatcher watcher) {
            return watcher.getCell();
        }

        if (inv instanceof StorageCell cell) {
            return cell;
        }

        return null;
    }
}
