package com.dloi.bulkterminal.menu;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

import appeng.api.storage.ITerminalHost;
import appeng.menu.guisync.ClientActionKey;
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
 * <p>「大宗盘管理」的交互走 AE2 菜单自带的 client action 通道（StreamCodec 二进制编码参数），
 * 服务端处理完经 S2C 快照包把最新状态推回界面。
 *
 * <p>所有 action 处理器都经 {@link #safeAction} 包装：本模组发生异常时把错误原因
 * （含堆栈）写入日志文件（logs/latest.log 等），并告知玩家，而不是让异常继续上抛。
 */
public class BulkTerminalMenu extends MEStorageMenu {
    private static final ClientActionKey<Void> ACT_REFRESH = new ClientActionKey<>("panelRefresh");
    private static final ClientActionKey<String> ACT_INSTALL_CARD = new ClientActionKey<>("panelInstallCard");
    private static final ClientActionKey<String> ACT_TAKE_CARD = new ClientActionKey<>("panelTakeCard");
    private static final ClientActionKey<String> ACT_CYCLE_CUTOFF = new ClientActionKey<>("panelCycleCutoff");
    private static final ClientActionKey<String> ACT_TRANSFER = new ClientActionKey<>("panelTransfer");
    private static final ClientActionKey<Void> ACT_TRANSFER_AUTO = new ClientActionKey<>("panelTransferAuto");
    private static final ClientActionKey<String> ACT_SET_AUTO_MARK = new ClientActionKey<>("panelSetAutoMark");
    private static final ClientActionKey<String> ACT_SET_THRESHOLD = new ClientActionKey<>("panelSetThreshold");

    public BulkTerminalMenu(int id, Inventory playerInventory, ITerminalHost host) {
        super(BTMenus.BULK_TERMINAL.get(), id, playerInventory, host);

        safeVoidAction(ACT_REFRESH, () -> {
            // 打开「压缩管理」= 新会话：丢弃旧行序，按最新状态重排。
            // 排序只在这个时机生效，会话期间行序冻结，避免操作后行跳动误触。
            PanelOps.clearSession(player().getUUID());
            PanelOps.sendSnapshot(part(), player());
        });
        safeAction(ACT_INSTALL_CARD, ByteBufCodecs.STRING_UTF8,
                s -> PanelOps.installCard(part(), player(), Integer.parseInt(s)));
        safeAction(ACT_TAKE_CARD, ByteBufCodecs.STRING_UTF8,
                s -> PanelOps.takeCard(part(), player(), Integer.parseInt(s)));
        safeAction(ACT_CYCLE_CUTOFF, ByteBufCodecs.STRING_UTF8, s -> {
            var parts = s.split(":");
            PanelOps.cycleCutoff(part(), player(), Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        });
        safeAction(ACT_TRANSFER, ByteBufCodecs.STRING_UTF8,
                s -> PanelOps.transferAll(part(), player(), Long.parseLong(s)));
        safeVoidAction(ACT_TRANSFER_AUTO,
                () -> PanelOps.transferAuto(part(), player()));
        safeAction(ACT_SET_AUTO_MARK, ByteBufCodecs.STRING_UTF8,
                s -> part().setAutoMarkEnabled(Boolean.parseBoolean(s)));
        safeAction(ACT_SET_THRESHOLD, ByteBufCodecs.STRING_UTF8,
                s -> part().setTransferThreshold(Long.parseLong(s)));
    }

    /**
     * 包装 client action 的服务端处理：处理器抛出任何异常时，把错误原因与堆栈
     * 写入日志文件并向玩家反馈，而不是让异常沿 AE2 的包处理链上抛导致断线。
     */
    private <T> void safeAction(ClientActionKey<T> key,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec, Consumer<T> handler) {
        registerClientAction(key, codec, v -> {
            try {
                handler.accept(v);
            } catch (Throwable t) {
                logActionError(key, t);
            }
        });
    }

    /**
     * {@link #safeAction} 的无参数变体：AE2 对 {@code ClientActionKey<Void>} 提供的是
     * Runnable 注册重载，异常防护语义与带参数版本一致。
     */
    private void safeVoidAction(ClientActionKey<Void> key, Runnable callback) {
        registerClientAction(key, () -> {
            try {
                callback.run();
            } catch (Throwable t) {
                logActionError(key, t);
            }
        });
    }

    private void logActionError(ClientActionKey<?> key, Throwable t) {
        BulkTerminal.LOGGER.error("处理界面动作 [{}] 时发生异常", key.name(), t);

        var p = getPlayer();
        if (p != null) {
            p.sendSystemMessage(Component.literal(
                    Component.translatable("gui.bulkterminal.action_error").getString())
                    .withStyle(ChatFormatting.RED));
        }
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
