package com.dloi.bulkterminal.definition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredRegister;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartItem;
import appeng.core.definitions.ItemDefinition;
import appeng.items.parts.PartItem;

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
     * 注册部件对应的 {@link PartItem}。26.1.2 起部件静态模型为数据驱动
     * （assets/bulkterminal/ae2/parts/&lt;id&gt;.json），不再需要注册模型代码。
     */
    private static <T extends IPart> ItemDefinition<PartItem<T>> part(
            String englishName, String id, Class<T> partClass, Function<IPartItem<T>, T> factory) {
        return item(englishName, id, p -> new PartItem<>(p, partClass, factory));
    }

    private static <T extends Item> ItemDefinition<T> item(
            String englishName, String id, Function<Item.Properties, T> factory) {
        var definition = new ItemDefinition<>(englishName, DR.registerItem(id, factory));
        ITEMS.add(definition);
        return definition;
    }
}
