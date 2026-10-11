package com.dloi.bulkterminal.client;

import java.lang.reflect.Field;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.gui.components.AbstractWidget;

import appeng.client.gui.AEBaseScreen;

/**
 * 「打开指南」按钮（AEBaseScreen.helpButton，OpenGuideButton）的置顶保障。
 *
 * <p>它在 AEBaseScreen 构造器里进入垂直按钮栏首位（界面最顶部的按钮位置），
 * 但作为子控件注册得最早——渲染在最底层、点击命中测试在最后。
 * 各界面 init 末尾用 {@link #findHelpButton} 取出后重新注册为最后一个子控件：
 * 渲染置顶、点击最先命中，保证它的图标、工具提示与点击操作永远处于最上层。
 * （removeWidget/addRenderableWidget 是 protected，重注册必须在界面自身完成。）
 */
final class BTScreens {
    private static final Logger LOGGER = LoggerFactory.getLogger(BTScreens.class);

    private BTScreens() {
    }

    /**
     * 反射查找 AEBaseScreen 私有的 helpButton 字段；失败返回 null（按钮仍在原位可用）。
     */
    static AbstractWidget findHelpButton(AEBaseScreen<?> screen) {
        try {
            Field field = AEBaseScreen.class.getDeclaredField("helpButton");
            field.setAccessible(true);
            return (AbstractWidget) field.get(screen);
        } catch (Throwable t) {
            LOGGER.warn("无法取得「打开指南」按钮引用", t);
            return null;
        }
    }
}
