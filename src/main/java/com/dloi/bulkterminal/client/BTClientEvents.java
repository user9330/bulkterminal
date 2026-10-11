package com.dloi.bulkterminal.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import appeng.init.client.InitScreens;

import com.dloi.bulkterminal.BulkTerminal;
import com.dloi.bulkterminal.definition.BTMenus;

/**
 * 客户端专用注册。
 *
 * <p>关键一步是给菜单类型绑定屏幕：NeoForge 收到开屏包时若找不到对应的 Screen 工厂，
 * 会静默什么都不做（看起来就是「右键没反应、没有 UI」）。之前正是缺了这一步。
 *
 * <p>样式基于 AE2 的终端样式文档派生：本模组在 ae2 命名空间下提供
 * {@code /screens/terminals/bulkterminal_terminal.json}（include 原版 terminal.json，
 * 关闭 supportsAutoCrafting 以隐藏「合成状态」按钮）。
 * AE2 的 {@link appeng.client.gui.style.StyleManager} 通过资源包系统解析该路径，
 * 本模组 jar 内的 ae2 命名空间资源可以被读到。
 */
// bus = MOD 是显式写法（注解属性在 NeoForge 21.1 已被标记待移除，但仍是唯一能拿到该事件的总线）
@SuppressWarnings("removal")
@EventBusSubscriber(modid = BulkTerminal.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BTClientEvents {
    private static final String TERMINAL_STYLE = "/screens/terminals/bulkterminal_terminal.json";

    private BTClientEvents() {
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        InitScreens.register(
                event,
                BTMenus.BULK_TERMINAL.get(),
                BulkTerminalScreen::new,
                TERMINAL_STYLE);
    }
}
