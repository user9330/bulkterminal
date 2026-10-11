package com.dloi.bulkterminal.menu;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

import appeng.api.storage.ITerminalHost;
import appeng.menu.me.common.MEStorageMenu;

import com.dloi.bulkterminal.BulkTerminal;
import com.dloi.bulkterminal.definition.BTMenus;
import com.dloi.bulkterminal.part.BulkTerminalPart;
import com.dloi.bulkterminal.storage.PanelOps;

/**
 * 大宗终端的菜单。
 *
 * <p>父类 {@link MEStorageMenu} 在构造时会读取 {@code host.getInventory()}，
 * 因此所有的显示 / 存入 / 提取都会自动走终端部件返回的 {@link com.dloi.bulkterminal.storage.BulkOnlyStorage}。
 *
 * <p>「大宗盘管理」的交互走 AE2 菜单自带的 client action 通道（GSON 序列化参数），
 * 服务端处理完经 S2C 快照包把最新状态推回界面。
 *
 * <p>所有 action 处理器都经 {@link #safeAction} 包装：本模组发生异常时把错误原因
 * （含堆栈）写入日志文件（logs/latest.log 等），并告知玩家，而不是让异常继续上抛。
 */
public class BulkTerminalMenu extends MEStorageMenu {
    private static final String ACT_REFRESH = "panelRefresh";
    private static final String ACT_INSTALL_CARD = "panelInstallCard";
    private static final String ACT_TAKE_CARD = "panelTakeCard";
    private static final String ACT_CYCLE_CUTOFF = "panelCycleCutoff";
    private static final String ACT_TRANSFER = "panelTransfer";
    private static final String ACT_TRANSFER_AUTO = "panelTransferAuto";
    private static final String ACT_SET_AUTO_MARK = "panelSetAutoMark";
    private static final String ACT_SET_THRESHOLD = "panelSetThreshold";

    public BulkTerminalMenu(int id, Inventory playerInventory, ITerminalHost host) {
        super(BTMenus.BULK_TERMINAL.get(), id, playerInventory, host);

        safeAction(ACT_REFRESH, Void.class, v -> {
            // 打开「压缩管理」= 新会话：丢弃旧行序，按最新状态重排。
            // 排序只在这个时机生效，会话期间行序冻结，避免操作后行跳动误触。
            PanelOps.clearSession(player().getUUID());
            PanelOps.sendSnapshot(part(), player());
        });
        safeAction(ACT_INSTALL_CARD, String.class,
                s -> PanelOps.installCard(part(), player(), Integer.parseInt(s)));
        safeAction(ACT_TAKE_CARD, String.class,
                s -> PanelOps.takeCard(part(), player(), Integer.parseInt(s)));
        safeAction(ACT_CYCLE_CUTOFF, String.class, s -> {
            var parts = s.split(":");
            PanelOps.cycleCutoff(part(), player(), Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        });
        safeAction(ACT_TRANSFER, String.class,
                s -> PanelOps.transferAll(part(), player(), Long.parseLong(s)));
        safeAction(ACT_TRANSFER_AUTO, Void.class,
                v -> PanelOps.transferAuto(part(), player()));
        safeAction(ACT_SET_AUTO_MARK, String.class,
                s -> part().setAutoMarkEnabled(Boolean.parseBoolean(s)));
        safeAction(ACT_SET_THRESHOLD, String.class,
                s -> part().setTransferThreshold(Long.parseLong(s)));
    }

    /**
     * 包装 client action 的服务端处理：处理器抛出任何异常时，把错误原因与堆栈
     * 写入日志文件并向玩家反馈，而不是让异常沿 AE2 的包处理链上抛导致断线。
     */
    private <T> void safeAction(String name, Class<T> argType, Consumer<T> handler) {
        registerClientAction(name, argType, v -> {
            try {
                handler.accept(v);
            } catch (Throwable t) {
                BulkTerminal.LOGGER.error("处理界面动作 [{}] 时发生异常", name, t);

                var p = getPlayer();
                if (p != null) {
                    p.displayClientMessage(Component.literal(
                            Component.translatable("gui.bulkterminal.action_error").getString())
                            .withStyle(ChatFormatting.RED), false);
                }
            }
        });
    }

    /**
     * 菜单关闭（退出终端界面）时丢弃冻结行序：下次打开按最新状态重排，
     * 同时避免会话缓存泄漏。
     */
    @Override
    public void removed(Player player) {
        super.removed(player);
        PanelOps.clearSession(player.getUUID());
    }

    /**
     * 关闭「配置可见类型」按钮（KeyTypeSelectionButton，能量/物品/流体筛选）。
     *
     * <p>AE2 的 {@code MEStorageScreen} 仅在 {@code canConfigureTypeFilter()} 为真时
     * 向工具栏添加该按钮；父类默认按 {@code host instanceof KeyTypeSelectionHost} 判断，
     * 而 {@code AbstractTerminalPart} 实现了该接口导致按钮被带进来。大宗盘只能标记物品，
     * 类型筛选没有意义，这里直接关闭。
     */
    @Override
    public boolean canConfigureTypeFilter() {
        return false;
    }

    // ===== 界面调用的公共入口：客户端转发，服务端由 action 处理 =====

    public void panelRefresh() {
        if (isClientSide()) {
            sendClientAction(ACT_REFRESH);
        }
    }

    public void panelInstallCard(int index) {
        if (isClientSide()) {
            sendClientAction(ACT_INSTALL_CARD, Integer.toString(index));
        }
    }

    public void panelTakeCard(int index) {
        if (isClientSide()) {
            sendClientAction(ACT_TAKE_CARD, Integer.toString(index));
        }
    }

    public void panelCycleCutoff(int index, int delta) {
        if (isClientSide()) {
            sendClientAction(ACT_CYCLE_CUTOFF, index + ":" + delta);
        }
    }

    public void panelTransfer(long threshold) {
        if (isClientSide()) {
            sendClientAction(ACT_TRANSFER, Long.toString(threshold));
        }
    }

    /**
     * Shift+点击「一键转移」：自动转移超过 {@link PanelOps#AUTO_TRANSFER_KEEP} 的物品
     * 到大宗盘（未标记的空闲盘先自动标记再转移）。客户端转发，服务端处理。
     */
    public void panelTransferAuto() {
        if (isClientSide()) {
            sendClientAction(ACT_TRANSFER_AUTO);
        }
    }

    public void panelSetAutoMark(boolean enabled) {
        if (isClientSide()) {
            sendClientAction(ACT_SET_AUTO_MARK, Boolean.toString(enabled));
        }
    }

    public void panelSetThreshold(long threshold) {
        if (isClientSide()) {
            sendClientAction(ACT_SET_THRESHOLD, Long.toString(threshold));
        }
    }

    private BulkTerminalPart part() {
        return (BulkTerminalPart) getHost();
    }

    private ServerPlayer player() {
        return (ServerPlayer) getPlayer();
    }
}
