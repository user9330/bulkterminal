package com.dloi.bulkterminal.client;

import java.util.List;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

import appeng.client.gui.widgets.AE2Button;
import appeng.client.gui.widgets.ITooltip;

/**
 * 「一键转移」按钮：按住 Shift 点击触发自动转移（把超过
 * {@link com.dloi.bulkterminal.storage.PanelOps#AUTO_TRANSFER_KEEP} 的物品转入大宗盘，
 * 未标记的空闲盘先自动标记）；按住 Shift 悬停时提示该功能。
 *
 * <p>走 AE2 的 {@link ITooltip} 前景层提示机制（与工具栏图标按钮同一条渲染路径），
 * 仅在 Shift 按下时返回提示内容，平时不弹提示、与原行为一致。
 */
public class TransferButton extends AE2Button implements ITooltip {

    public TransferButton(int x, int y, int width, int height, Component label, OnPress onPress) {
        super(x, y, width, height, label, onPress);
    }

    @Override
    public List<Component> getTooltipMessage() {
        return Screen.hasShiftDown()
                ? List.of(Component.translatable("gui.bulkterminal.transfer_shift_hint"))
                : List.of();
    }

    @Override
    public Rect2i getTooltipArea() {
        return new Rect2i(getX(), getY(), getWidth(), getHeight());
    }

    @Override
    public boolean isTooltipAreaVisible() {
        return this.visible;
    }
}
