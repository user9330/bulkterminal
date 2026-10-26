package com.dloi.bulkterminal.client;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.Rect2i;

import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.ITooltip;

import com.dloi.bulkterminal.BulkTerminal;

/**
 * 本模组自绘按钮的底座：贴图一律取自 {@code bulkterminal:textures/gui/}，
 * 不读 AE2 / MEGA 的图集；并补上 AE2 工具栏所需的 tooltip 协议。
 */
abstract class BTPictureButton extends Button implements ITooltip {
    protected BTPictureButton(Component message, OnPress onPress) {
        super(0, 0, 16, 16, message, onPress, Button.DEFAULT_NARRATION);
    }

    protected static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath(BulkTerminal.MOD_ID, "textures/gui/" + name);
    }

    protected static void blitTexture(GuiGraphicsExtractor guiGraphics, Identifier texture, int texWidth,
            int texHeight, int x, int y) {
        Blitter.texture(texture, texWidth, texHeight).dest(x, y).blit(guiGraphics);
    }

    @Override
    public Rect2i getTooltipArea() {
        return new Rect2i(getX(), getY(), width, height);
    }

    @Override
    public boolean isTooltipAreaVisible() {
        return visible;
    }

    @Override
    public List<Component> getTooltipMessage() {
        return Collections.singletonList(getMessage());
    }
}
