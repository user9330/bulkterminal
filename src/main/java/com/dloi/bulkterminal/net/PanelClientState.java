package com.dloi.bulkterminal.net;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerPlayer;

import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端持有的最新管理界面快照（纯数据，无客户端专属类依赖）。
 *
 * <p>快照到达时置 updated 标记，界面在 containerTick 里消费后重建控件。
 */
public final class PanelClientState {
    private static volatile PanelSnapshotPayload latest = PanelSnapshotPayload.EMPTY;
    private static volatile boolean updated = false;

    private PanelClientState() {
    }

    public static void accept(PanelSnapshotPayload payload) {
        latest = payload;
        updated = true;
    }

    public static PanelSnapshotPayload peek() {
        return latest;
    }

    public static boolean consumeUpdated() {
        var flag = updated;
        updated = false;
        return flag;
    }

    /**
     * 服务端：把快照发给指定玩家。
     */
    public static void sendTo(ServerPlayer player, PanelSnapshotPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }
}
