package com.dloi.bulkterminal.part;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNodeListener;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.storage.MEStorage;
import appeng.items.parts.PartModels;
import appeng.parts.PartModel;
import appeng.parts.reporting.AbstractTerminalPart;

import com.dloi.bulkterminal.BulkTerminal;
import com.dloi.bulkterminal.definition.BTMenus;
import com.dloi.bulkterminal.storage.BulkOnlyStorage;

/**
 * 大宗存储终端部件。
 *
 * <p>关键点只有一处：覆写 {@link AbstractTerminalPart#getInventory()} 返回自定义的存储视图，
 * 之后显示 / 存入 / 提取三条路径全部走它。
 */
public class BulkTerminalPart extends AbstractTerminalPart {
    private static final String TAG_AUTO_MARK = "autoMarkEnabled";
    private static final String TAG_TRANSFER_THRESHOLD = "transferThreshold";

    @PartModels
    private static final IPartModel MODEL_OFF = new PartModel(
            MODEL_BASE, BulkTerminal.makeId("part/bulk_terminal_off"), MODEL_STATUS_OFF);

    @PartModels
    private static final IPartModel MODEL_ON = new PartModel(
            MODEL_BASE, BulkTerminal.makeId("part/bulk_terminal_on"), MODEL_STATUS_ON);

    @Nullable
    private BulkOnlyStorage bulkInventory;

    /**
     * 自动标记空闲大宗盘的开关（持久化）。关闭后退回 MEGA 原版行为：未标记盘拒收一切。
     */
    private boolean autoMarkEnabled = true;

    /**
     * 一键转移时每种物品在普通存储中的保留量（持久化）。0 = 全部搬入大宗盘。
     */
    private long transferThreshold = 0;

    public BulkTerminalPart(IPartItem<?> partItem) {
        super(partItem);
    }

    @Override
    public MenuType<?> getMenuType(Player player) {
        return BTMenus.BULK_TERMINAL.get();
    }

    @Override
    public MEStorage getInventory() {
        if (bulkInventory == null) {
            bulkInventory = new BulkOnlyStorage(this);
        }

        return bulkInventory;
    }

    @Override
    public IPartModel getStaticModels() {
        return isPowered() && isActive() ? MODEL_ON : MODEL_OFF;
    }

    @Override
    protected void onMainNodeStateChanged(IGridNodeListener.State reason) {
        super.onMainNodeStateChanged(reason);
        invalidateCache();
    }

    public void invalidateCache() {
        if (bulkInventory != null) {
            bulkInventory.invalidate();
        }
    }

    @Nullable
    public IGrid getGrid() {
        var node = getGridNode();
        return node == null ? null : node.getGrid();
    }

    /**
     * 供扫描器做缓存失效判断，单位为 tick。
     */
    public long getCurrentTick() {
        var level = getLevel();
        return level == null ? 0 : level.getGameTime();
    }

    @Nullable
    public BulkOnlyStorage getBulkInventory() {
        return bulkInventory;
    }

    public boolean isAutoMarkEnabled() {
        return autoMarkEnabled;
    }

    public void setAutoMarkEnabled(boolean enabled) {
        this.autoMarkEnabled = enabled;

        var host = getHost();
        if (host != null) {
            host.markForSave();
        }
    }

    public long getTransferThreshold() {
        return transferThreshold;
    }

    public void setTransferThreshold(long threshold) {
        this.transferThreshold = Math.max(0, threshold);

        var host = getHost();
        if (host != null) {
            host.markForSave();
        }
    }

    @Override
    public void writeToNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.writeToNBT(data, registries);
        data.putBoolean(TAG_AUTO_MARK, autoMarkEnabled);
        data.putLong(TAG_TRANSFER_THRESHOLD, transferThreshold);
    }

    @Override
    public void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.readFromNBT(data, registries);
        this.autoMarkEnabled = data.contains(TAG_AUTO_MARK) ? data.getBoolean(TAG_AUTO_MARK) : true;
        this.transferThreshold = data.contains(TAG_TRANSFER_THRESHOLD) ? data.getLong(TAG_TRANSFER_THRESHOLD) : 0;
    }
}
