package com.dloi.bulkterminal.client;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.PreeditEvent;

import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AETextField;

/**
 * 不接管中文输入法的 {@link AETextField}：关闭 MC 26.1 的游戏内组合条（IMEPreeditOverlay），
 * 把候选窗的定位交还系统输入法自管（候选窗自动锚定到输入框附近），上屏文字照常进框。
 *
 * <p><b>设计背景</b>：MC 26.1 官方行为是把输入法组合串直接画进游戏内（黑色组合条，
 * 由 {@code EditBox} 私有的 {@code preeditOverlay} 字段持有），候选窗定位也交给该 overlay
 * 负责。对自带界面边框的终端来说游戏内组合条与 AE2 风格割裂，且 overlay 定位策略不可定制。
 * 本类覆写 {@link #preeditUpdated(PreeditEvent)} 且<b>不调 super</b>——父类默认实现会创建
 * 游戏内组合条，其 private 字段无 setter（MC 26.1.2 反编译确认），不调 super 即该字段恒为
 * null、组合条永不创建/渲染；同时组合期间把输入框矩形（外扩 2px）上报给
 * {@code TextInputManager.setTextInputArea}，由系统输入法自行把候选窗锚定到输入框附近。
 *
 * <p><b>上屏不受影响</b>：候选选定后的文字仍走 {@code EditBox} 原生的 charTyped 路径，
 * 本类不做任何干预；AETextField 经继承保留的 keyPressed 吞键逻辑（防 e 键关界面）同样未动。
 *
 * <p><b>维护注意</b>：「覆写不调 super」成立的前提是父类 preeditOverlay 无 setter 且
 * preeditUpdated 的默认实现只负责组合条（26.1.2 反编译确认）；MC 大版本升级后需复核
 * 该默认实现是否新增了其他职责，必要时改为调 super 后再撤销 overlay。
 */
public class ImeFreeTextField extends AETextField {
    /** 当前是否存在进行中的输入法组合串（由 preeditUpdated 事件驱动，随每帧渲染兜底使用） */
    private boolean preeditActive;

    public ImeFreeTextField(ScreenStyle style, Font font, int x, int y, int width, int height) {
        super(style, font, x, y, width, height);
    }

    /**
     * 组合串更新回调：不调 super——父类默认实现会创建游戏内组合条 IMEPreeditOverlay，
     * 其 private 字段无 setter，不调 super 即恒为 null、组合条永不创建/渲染。
     *
     * <p>组合期间（event != null）把输入框矩形外扩 2px 后上报给系统输入法，
     * 候选窗随即锚定到输入框附近；组合结束（event == null）只清除标志。
     */
    @Override
    public boolean preeditUpdated(@Nullable PreeditEvent event) {
        if (event != null) {
            preeditActive = true;
            // width/height 是 AbstractWidget 的 protected 字段，子类直接可访问
            Minecraft.getInstance().textInputManager()
                    .setTextInputArea(getX() - 2, getY() - 2, getX() + width + 2, getY() + height + 2);
        } else {
            preeditActive = false;
        }
        return true;
    }

    /**
     * 每帧兜底：组合进行中且仍持有焦点时再次上报锚定矩形，窗口缩放/布局变化后
     * 候选窗锚点仍正确。非组合期不调 setTextInputArea，避免无谓的 GLFW 调用。
     */
    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
            float partialTick) {
        super.extractWidgetRenderState(guiGraphics, mouseX, mouseY, partialTick);

        if (preeditActive && isFocused()) {
            Minecraft.getInstance().textInputManager()
                    .setTextInputArea(getX() - 2, getY() - 2, getX() + width + 2, getY() + height + 2);
        }
    }
}
