package com.dloi.bulkterminal.definition;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredRegister;

import appeng.core.definitions.ItemDefinition;

import com.dloi.bulkterminal.BulkTerminal;

public final class BTCreativeTab {
    public static final DeferredRegister<CreativeModeTab> DR =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BulkTerminal.MOD_ID);

    public static final String TRANSLATION_KEY = "itemGroup." + BulkTerminal.MOD_ID;

    static {
        DR.register("tab", () -> CreativeModeTab.builder()
                .title(Component.translatable(TRANSLATION_KEY))
                .icon(BTItems.BULK_TERMINAL::stack)
                .displayItems(BTCreativeTab::populateTab)
                .build());
    }

    private static void populateTab(CreativeModeTab.ItemDisplayParameters params, CreativeModeTab.Output output) {
        for (ItemDefinition<?> itemDef : BTItems.getItems()) {
            output.accept(itemDef);
        }
    }
}
