package com.dloi.bulkterminal.client;

import java.util.Iterator;
import java.util.List;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.config.ActionItems;
import appeng.api.config.Settings;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.ActionButton;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.client.gui.widgets.VerticalButtonBar;

import com.dloi.bulkterminal.menu.BulkTerminalMenu;

/**
 * 大宗终端的客户端界面。
 *
 * <p>在 AE2 {@link MEStorageScreen} 之上做以下定制：
 * <ol>
 *   <li>保留「终端风格」（TERMINAL_STYLE）切换按钮（可修改终端高度）；</li>
 *   <li>移除「视图模式」（VIEW_MODE）切换按钮——本终端只显示已标记物品、没有合成体系；
 *       「排序按」（SORT_BY）与「排序顺序」（SORT_DIRECTION）保留；</li>
 *   <li>移除 AE2 原设置齿轮，换成打开本模组设置子界面的齿轮（不含三项合成相关设置）；
 *       「配置可见类型」（KeyTypeSelectionButton，能量/物品/流体筛选）由
 *       {@link BulkTerminalMenu#canConfigureTypeFilter()} 返回 false 从源头移除；</li>
 *   <li>在垂直按钮栏最底部（最下面按钮的下方）加入「大宗盘管理」入口
 *       （{@link BulkManageButton}，压缩卡物品图标），直接打开管理子界面，不经过设置界面；
 *       「打开指南」按钮因此保持在按钮栏首位。</li>
 * </ol>
 *
 * <p><b>按钮栏生命周期（已核实 19.2.18 字节码）：</b>垂直按钮栏在 AEBaseScreen 构造器中创建，
 * 工具栏按钮在 {@code MEStorageScreen} 构造器中一次性加入——每个屏幕实例的按钮列表天然稳定，
 * 不会累积重复。这里在 init 时做一次<b>幂等清理</b>（剔除不需要的按钮 + 注册我们的齿轮），
 * 反射失败时保留 AE2 原状。
 */
public class BulkTerminalScreen extends MEStorageScreen<BulkTerminalMenu> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BulkTerminalScreen.class);

    private ActionButton settingsButton;
    private BulkManageButton manageButton;

    public BulkTerminalScreen(BulkTerminalMenu menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
    }

    @Override
    public void init() {
        super.init();

        if (settingsButton == null) {
            settingsButton = new ActionButton(ActionItems.TERMINAL_SETTINGS, this::openSettings);
        }

        if (manageButton == null) {
            manageButton = new BulkManageButton(this::openManage);
        }

        var toolbar = getToolbarButtons();

        if (toolbar == null) {
            // 无法访问按钮栏（理论上不会发生）：保留 AE2 原状，仅补注册我们的齿轮与管理入口
            if (!children().contains(settingsButton)) {
                addRenderableWidget(settingsButton);
            }
            if (!children().contains(manageButton)) {
                addRenderableWidget(manageButton);
            }
            raiseHelpButton();
            LOGGER.warn("无法访问 AE2 垂直按钮栏，保留原按钮布局");
            return;
        }

        // 1. 幂等剔除不需要的按钮（含上次 init 遗留的我们自己的两个按钮）
        var it = toolbar.iterator();

        while (it.hasNext()) {
            var b = it.next();

            if (b == settingsButton || b == manageButton || shouldRemove(b)) {
                it.remove();
                removeWidget(b);
            }
        }

        // 2. 管理入口放到按钮栏最底部（最下面按钮的下方），「打开指南」保持按钮栏首位
        toolbar.add(settingsButton);
        toolbar.add(manageButton);

        if (!children().contains(manageButton)) {
            addRenderableWidget(manageButton);
        }

        if (!children().contains(settingsButton)) {
            addRenderableWidget(settingsButton);
        }

        // 「打开指南」按钮渲染置顶 + 点击最先命中（见 BTScreens）
        raiseHelpButton();
    }

    @Override
    public void containerTick() {
        super.containerTick();
        enforceToolbarLayout();
    }

    /**
     * 每 tick 兜底：把我们的两个按钮强制回按钮栏列表末尾（视觉 = 工具栏最底部）并保持可见。
     * 真机上曾出现按钮被其它流程挤到列表中间的情况，这里不依赖 init 时机。
     */
    private void enforceToolbarLayout() {
        var list = getToolbarButtons();

        if (list == null) {
            return;
        }

        int n = list.size();

        if (n >= 2 && list.get(n - 2) == settingsButton && list.get(n - 1) == manageButton
                && settingsButton.visible && manageButton.visible) {
            return;
        }

        list.remove(settingsButton);
        list.remove(manageButton);
        list.add(settingsButton);
        list.add(manageButton);
        settingsButton.visible = true;
        manageButton.visible = true;
    }

    /**
     * 「打开指南」按钮渲染置顶 + 点击最先命中（见 {@link BTScreens}）。
     */
    private void raiseHelpButton() {
        var help = BTScreens.findHelpButton(this);

        if (help != null && children().contains(help)) {
            removeWidget(help);
            addRenderableWidget(help);
        }
    }

    /**
     * 是否剔除该按钮。
     *
     * <p>剔除：AE2 原设置齿轮（本界面上唯一的 ActionButton，替换为本模组裁剪版）、
     * 「视图模式」（VIEW_MODE）切换按钮。
     * 保留：搜索模式、「终端风格」（TERMINAL_STYLE）、排序按（SORT_BY）、
     * 排序方向（SORT_DIRECTION）、指南等。
     */
    private static boolean shouldRemove(Button b) {
        if (b instanceof ActionButton) {
            return true; // 原版设置齿轮
        }

        if (b instanceof SettingToggleButton<?> toggle) {
            return toggle.getSetting() == Settings.VIEW_MODE;
        }

        return false;
    }

    /**
     * 读取 AEBaseScreen.verticalToolbar 内部的按钮列表（可变引用）。
     *
     * @return 按钮列表；反射失败时返回 null
     */
    @SuppressWarnings("unchecked")
    @org.jetbrains.annotations.Nullable
    private List<Button> getToolbarButtons() {
        try {
            var field = AEBaseScreen.class.getDeclaredField("verticalToolbar");
            field.setAccessible(true);
            var bar = (VerticalButtonBar) field.get(this);

            var listField = VerticalButtonBar.class.getDeclaredField("buttons");
            listField.setAccessible(true);
            return (List<Button>) listField.get(bar);
        } catch (Throwable t) {
            LOGGER.warn("访问 AE2 垂直按钮栏失败", t);
            return null;
        }
    }

    private void openSettings() {
        this.switchToScreen(new BTTerminalSettingsScreen(this));
    }

    private void openManage() {
        this.switchToScreen(new BulkCellManageScreen(this));
    }
}
