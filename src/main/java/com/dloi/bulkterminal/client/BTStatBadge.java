package com.dloi.bulkterminal.client;

import appeng.client.gui.widgets.AE2Button;
import appeng.core.AppEng;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 纯展示的数值牌：外观与 AE2 原生按钮（AE2Button 用的 ae2:button 贴图）同款，
 * 但不响应点击、悬停与聚焦，不会高亮。
 */
class BTStatBadge extends Button {
    /** 与 AE2Button.SPRITES 同源：AppEng.makeId("button") = ae2:button（写错 ID 会渲染成缺贴图洋红块） */
    private static final Identifier SPRITE = AppEng.makeId("button");

    BTStatBadge(Component message, int x, int y, int width) {
        super(x, y, width, 16, message, b -> {
        }, DEFAULT_NARRATION);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, SPRITE, getX(), getY(), width, height);

        // 文字经 AE2Button 自己的渲染路径，与界面上其他按钮完全一致：
        // fork 的 AE2Button 正常态 = 0xF2F2F2 白字、无阴影（禁用 0x413F54 / 悬停 0x517497），
        // 参数 (内边距2, 正常态state=1)；自己另写颜色会和按钮不一致（真机踩过）
        AE2Button.renderButtonText(guiGraphics, Minecraft.getInstance().font, getMessage(),
                getX() + 2, getY(), getX() + getWidth() - 2, getY() + getHeight(), 1, 0xFFF2F2F2);
    }
}
