package com.dloi.bulkterminal.definition;

import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredRegister;

import appeng.api.storage.ITerminalHost;
import appeng.menu.implementations.MenuTypeBuilder;

import com.dloi.bulkterminal.BulkTerminal;
import com.dloi.bulkterminal.menu.BulkTerminalMenu;

public final class BTMenus {
    public static final DeferredRegister<MenuType<?>> DR =
            DeferredRegister.create(Registries.MENU, BulkTerminal.MOD_ID);

    public static final Supplier<MenuType<BulkTerminalMenu>> BULK_TERMINAL = DR.register(
            "bulk_terminal",
            () -> MenuTypeBuilder.create(BulkTerminalMenu::new, ITerminalHost.class)
                    .withMenuTitle(host -> Component.translatable("bulkterminal.menu.title"))
                    .buildUnregistered(BulkTerminal.makeId("bulk_terminal")));
}
