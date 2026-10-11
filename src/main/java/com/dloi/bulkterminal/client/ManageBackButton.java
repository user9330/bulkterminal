package com.dloi.bulkterminal.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 管理子界面的返回键：tab 底图（20×20，聚焦态 22×22）+ 返回箭头（16×16），
 * 全部绘制自本模组贴图；尺寸与位置由样式 JSON 的 back 条目决定。
 */
public class ManageBackButton extends BTPictureButton {
    private static final ResourceLocation BG = texture("manage_back_bg.png");
    private static final ResourceLocation BG_FOCUS = texture("manage_back_bg_focus.png");
    private static final ResourceLocation ARROW = texture("manage_back_arrow.png");

    public ManageBackButton(Component label, OnPress onPress) {
        super(label, onPress);
        this.width = 20;
        this.height = 20;
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (isFocused()) {
            blitTexture(guiGraphics, BG_FOCUS, 22, 22, getX() - 1, getY() - 1);
        } else {
            blitTexture(guiGraphics, BG, 20, 20, getX(), getY());
        }

        blitTexture(guiGraphics, ARROW, 16, 16, getX() + 2, getY() + 2);
    }
}
