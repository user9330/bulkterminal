package com.dloi.bulkterminal.client;

import net.minecraft.network.chat.Component;

import appeng.client.gui.AESubScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AECheckbox;
import appeng.client.gui.widgets.TabButton;
import appeng.client.gui.Icon;
import appeng.core.AEConfig;
import appeng.core.localization.GuiText;
import appeng.integration.abstraction.ItemListMod;
import appeng.menu.SlotSemantics;

import com.dloi.bulkterminal.menu.BulkTerminalMenu;

/**
 * 大宗终端专用的「终端设置」子界面。
 *
 * <p>与 AE2 原版 {@code TerminalSettingsScreen} 的唯一区别：**不包含**三项合成相关设置
 * （置顶自动合成物品 / 合成完成提醒 / 关闭时清空合成槽）——大宗终端没有合成体系，
 * 这些设置没有意义。搜索框设置与原版行为完全一致，状态同样保存在 {@link AEConfig}。
 *
 * <p>样式文档 {@code /screens/terminals/bulkterminal_settings.json} 位于本模组资源内
 * （ae2 命名空间下新建文件），它 include 了 AE2 原版的 terminal_settings.json 并重排了控件位置。
 */
public class BTTerminalSettingsScreen extends AESubScreen<BulkTerminalMenu, BulkTerminalScreen> {
    private static final String STYLE = "/screens/terminals/bulkterminal_settings.json";

    private final AEConfig config = AEConfig.instance();

    private final AECheckbox useInternalSearchRadio;
    private final AECheckbox useExternalSearchRadio;

    private final AECheckbox rememberCheckbox;
    private final AECheckbox autoFocusCheckbox;
    private final AECheckbox syncWithExternalCheckbox;
    private final AECheckbox clearExternalCheckbox;

    public BTTerminalSettingsScreen(BulkTerminalScreen parent) {
        super(parent, STYLE);

        addBackButton();

        Component externalSearchMod;
        boolean hasExternalSearch;
        if (ItemListMod.isEnabled()) {
            externalSearchMod = Component.literal(ItemListMod.getShortName());
            hasExternalSearch = true;
        } else {
            externalSearchMod = Component.literal("REI/EMI");
            hasExternalSearch = false;
        }

        useInternalSearchRadio = widgets.addCheckbox("useInternalSearchRadio",
                GuiText.SearchSettingsUseInternalSearch.text(), this::switchToAeSearch);
        useInternalSearchRadio.setRadio(true);
        useExternalSearchRadio = widgets.addCheckbox("useExternalSearchRadio",
                GuiText.SearchSettingsUseExternalSearch.text(externalSearchMod), this::switchToExternalSearch);
        useExternalSearchRadio.setRadio(true);
        useExternalSearchRadio.active = hasExternalSearch;

        rememberCheckbox = widgets.addCheckbox("rememberCheckbox",
                GuiText.SearchSettingsRememberSearch.text(), this::save);
        autoFocusCheckbox = widgets.addCheckbox("autoFocusCheckbox",
                GuiText.SearchSettingsAutoFocus.text(), this::save);

        syncWithExternalCheckbox = widgets.addCheckbox("syncWithExternalCheckbox",
                GuiText.SearchSettingsSyncWithExternal.text(externalSearchMod), this::save);
        clearExternalCheckbox = widgets.addCheckbox("clearExternalCheckbox",
                GuiText.SearchSettingsClearExternal.text(externalSearchMod), this::save);

        updateState();
    }

    @Override
    protected void init() {
        super.init();

        // 样式 JSON 含工具箱区域，但本界面用不到
        setSlotsHidden(SlotSemantics.TOOLBOX, true);

        raiseHelpButton();
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

    private void switchToAeSearch() {
        useInternalSearchRadio.setSelected(true);
        useExternalSearchRadio.setSelected(false);
        save();
    }

    private void switchToExternalSearch() {
        useInternalSearchRadio.setSelected(false);
        useExternalSearchRadio.setSelected(true);
        save();
    }

    private void addBackButton() {
        var label = menu.getHost().getMainMenuIcon().getHoverName();
        TabButton button = new TabButton(Icon.BACK, label, btn -> returnToParent());
        widgets.add("back", button);
    }

    private void updateState() {
        useInternalSearchRadio.setSelected(!config.isUseExternalSearch());
        useExternalSearchRadio.setSelected(config.isUseExternalSearch());
        rememberCheckbox.setSelected(config.isRememberLastSearch());
        autoFocusCheckbox.setSelected(config.isAutoFocusSearch());
        syncWithExternalCheckbox.setSelected(config.isSyncWithExternalSearch());
        clearExternalCheckbox.setSelected(config.isClearExternalSearchOnOpen());

        rememberCheckbox.visible = useInternalSearchRadio.isSelected();
        autoFocusCheckbox.visible = useInternalSearchRadio.isSelected();
        syncWithExternalCheckbox.visible = useInternalSearchRadio.isSelected();

        clearExternalCheckbox.visible = useExternalSearchRadio.isSelected();
    }

    private void save() {
        config.setUseExternalSearch(useExternalSearchRadio.isSelected());
        config.setRememberLastSearch(rememberCheckbox.isSelected());
        config.setAutoFocusSearch(autoFocusCheckbox.isSelected());
        config.setSyncWithExternalSearch(syncWithExternalCheckbox.isSelected());
        config.setClearExternalSearchOnOpen(clearExternalCheckbox.isSelected());

        updateState();
    }
}
