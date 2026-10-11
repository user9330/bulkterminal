package com.dloi.bulkterminal.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;

import appeng.client.Point;
import appeng.client.gui.ICompositeWidget;

/**
 * 把界面的自绘文字挂进 AE2 的前景层（renderLabels 阶段绘制）。
 *
 * <p>AE2 的工具提示（「打开指南」、控件提示等）在整帧渲染的最后一步
 * （AEBaseScreen.renderTooltips）绘制；如果在 render() 里 super.render() 之后
 * 自绘内容，就会压在工具提示之上（真机曾出现行文字盖住「打开指南」提示）。
 * 通过本控件把自绘内容交给 AE2 前景层，工具提示就永远处于内容之上。
 */
class BTScreenForeground implements ICompositeWidget {
    interface Drawer {
        void draw(GuiGraphics guiGraphics);
    }

    private final Drawer drawer;
    private final Rect2i bounds = new Rect2i(0, 0, 0, 0);

    BTScreenForeground(Drawer drawer) {
        this.drawer = drawer;
    }

    @Override
    public boolean isVisible() {
        return true;
    }

    @Override
    public void setPosition(Point position) {
        // 无需定位：只借用前景层绘制时机。注意前景层在 renderLabels 的平移坐标系里，
        // 回调绘制必须用相对面板左上角的坐标（叠加 leftPos/topPos 会整体偏移出面板）
    }

    @Override
    public void setSize(int width, int height) {
    }

    @Override
    public Rect2i getBounds() {
        return bounds;
    }

    @Override
    public void drawForegroundLayer(GuiGraphics guiGraphics, Rect2i bounds, Point mouse) {
        drawer.draw(guiGraphics);
    }
}
