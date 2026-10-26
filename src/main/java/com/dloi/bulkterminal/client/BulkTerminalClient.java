package com.dloi.bulkterminal.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import appeng.client.InitScreens;

import com.dloi.bulkterminal.BulkTerminal;
import com.dloi.bulkterminal.definition.BTMenus;

/**
 * 客户端专用注册。
 *
 * <p>关键一步是给菜单类型绑定屏幕：NeoForge 收到开屏包时若找不到对应的 Screen 工厂，
 * 会静默什么都不做（看起来就是「右键没反应、没有 UI」）。
 *
 * <p>样式基于 AE2 的终端样式文档派生：本模组在 ae2 命名空间下提供
 * {@code /screens/terminals/bulkterminal_terminal.json}（include 原版 terminal.json，
 * 关闭 supportsAutoCrafting 以隐藏「合成状态」按钮）。
 * AE2 的 {@code StyleManager} 通过资源包系统解析该路径，
 * 本模组 jar 内的 ae2 命名空间资源可以被读到。
 *
 * <p>NeoForge 26.1 移除了 {@code @EventBusSubscriber}，客户端注册改为
 * {@code @Mod(dist = Dist.CLIENT)} 类内 {@code addListener}（同 MEGA / merequester 范式）。
 */
@Mod(value = BulkTerminal.MOD_ID, dist = Dist.CLIENT)
public final class BulkTerminalClient {
    private static final String TERMINAL_STYLE = "/screens/terminals/bulkterminal_terminal.json";

    public BulkTerminalClient(IEventBus modEventBus) {
        modEventBus.addListener(BulkTerminalClient::onRegisterMenuScreens);
    }

    private static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        InitScreens.register(
                event,
                BTMenus.BULK_TERMINAL.get(),
                BulkTerminalScreen::new,
                TERMINAL_STYLE);
    }
}
