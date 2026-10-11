package com.dloi.bulkterminal.net;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import com.dloi.bulkterminal.BulkTerminal;

/**
 * 网络包注册。S2C 快照的处理器只在客户端执行，引用的 {@link PanelClientState}
 * 是纯数据类，无客户端专属依赖。
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = BulkTerminal.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class BTNetwork {
    private BTNetwork() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");

        registrar.playToClient(
                PanelSnapshotPayload.TYPE,
                PanelSnapshotPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelClientState.accept(payload)));
    }
}
