package com.dloi.bulkterminal.definition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredRegister;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartItem;
import appeng.api.parts.PartModels;
import appeng.core.definitions.ItemDefinition;
import appeng.items.parts.PartItem;
import appeng.items.parts.PartModelsHelper;

import com.dloi.bulkterminal.BulkTerminal;
import com.dloi.bulkterminal.part.BulkTerminalPart;

public final class BTItems {
    public static final DeferredRegister.Items DR = DeferredRegister.createItems(BulkTerminal.MOD_ID);

    private static final List<ItemDefinition<?>> ITEMS = new ArrayList<>();

    /**
     * 大宗存储终端部件本体。
     */
    public static final ItemDefinition<PartItem<BulkTerminalPart>> BULK_TERMINAL =
            part("Bulk Terminal", "bulk_terminal", BulkTerminalPart.class, BulkTerminalPart::new);

    public static List<ItemDefinition<?>> getItems() {
        return Collections.unmodifiableList(ITEMS);
    }

    /**
     * 照抄 MEGA Cells 的部件注册范式：先把部件声明的模型登记给 AE2，再注册对应的 {@link PartItem}。
     */
    private static <T extends IPart> ItemDefinition<PartItem<T>> part(
            String englishName, String id, Class<T> partClass, Function<IPartItem<T>, T> factory) {
        PartModels.registerModels(PartModelsHelper.createModels(partClass));
        return item(englishName, id, p -> new PartItem<>(p, partClass, factory));
    }

    private static <T extends Item> ItemDefinition<T> item(
            String englishName, String id, Function<Item.Properties, T> factory) {
        var definition = new ItemDefinition<>(englishName, DR.registerItem(id, factory));
        ITEMS.add(definition);
        return definition;
    }
}
