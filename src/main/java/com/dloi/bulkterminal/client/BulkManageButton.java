package com.dloi.bulkterminal.client;

import appeng.client.gui.Icon;
import appeng.client.gui.widgets.IconButton;
import net.minecraft.network.chat.Component;

/**
 * 终端工具栏上的「压缩管理」入口按钮：AE2 原生 {@link IconButton}（与「打开指南」按钮同基类、
 * 同一条渲染路径——底图、图标、tooltip 全部由 AE2 自己绘制）。
 * 图标 = {@link Icon#VIEW_MODE_ALL}（states.png 32,16，用户指定）。
 *
 * <p>历史上曾用自绘贴图（BTPictureButton + Blitter.texture），在主终端的工具栏上不渲染
 * （tooltip 能出、按钮本体看不见），换成 fork 自己的 IconButton 基类后与其它工具栏按钮同路径。
 */
public class BulkManageButton extends IconButton {
    public BulkManageButton(Runnable onPress) {
        super(b -> onPress.run());
        setMessage(Component.translatable("gui.bulkterminal.manage_open"));
    }

    @Override
    protected Icon getIcon() {
        return Icon.VIEW_MODE_ALL;
    }
}
