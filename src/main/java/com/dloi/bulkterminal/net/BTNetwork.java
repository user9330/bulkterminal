package com.dloi.bulkterminal.net;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 网络包注册。S2C 快照的处理器只在客户端执行，引用的 {@link PanelClientState}
 * 是纯数据类，无客户端专属依赖。
 *
 * <p>NeoForge 26.1 移除了 {@code @EventBusSubscriber}，改为在 {@code @Mod}
 * 构造器里 {@code modBus.addListener} 注册（见 {@link com.dloi.bulkterminal.BulkTerminal}）。
 */
public final class BTNetwork {
    private BTNetwork() {
    }

    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");

        registrar.playToClient(
                PanelSnapshotPayload.TYPE,
                PanelSnapshotPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelClientState.accept(payload)));
    }
}
