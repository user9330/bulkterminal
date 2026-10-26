package com.dloi.bulkterminal.part;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNodeListener;
import appeng.api.parts.IPartItem;
import appeng.api.storage.MEStorage;
import appeng.parts.reporting.AbstractTerminalPart;

import com.dloi.bulkterminal.definition.BTMenus;
import com.dloi.bulkterminal.storage.BulkOnlyStorage;

/**
 * 大宗存储终端部件。
 *
 * <p>关键点只有一处：覆写 {@link AbstractTerminalPart#getInventory()} 返回自定义的存储视图，
 * 之后显示 / 存入 / 提取三条路径全部走它。
 *
 * <p>26.1.2 起 AE2 部件静态模型改为数据驱动（assets/&lt;ns&gt;/ae2/parts/&lt;id&gt;.json），
 * 旧 {@code @PartModels} / {@code getStaticModels()} 注解已移除。
 */
public class BulkTerminalPart extends AbstractTerminalPart {
    private static final String TAG_AUTO_MARK = "autoMarkEnabled";
    private static final String TAG_TRANSFER_THRESHOLD = "transferThreshold";

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
    public void writeToNBT(ValueOutput output) {
        super.writeToNBT(output);
        output.putBoolean(TAG_AUTO_MARK, autoMarkEnabled);
        output.putLong(TAG_TRANSFER_THRESHOLD, transferThreshold);
    }

    @Override
    public void readFromNBT(ValueInput input) {
        super.readFromNBT(input);
        this.autoMarkEnabled = input.getBooleanOr(TAG_AUTO_MARK, true);
        this.transferThreshold = input.getLongOr(TAG_TRANSFER_THRESHOLD, 0);
    }
}
