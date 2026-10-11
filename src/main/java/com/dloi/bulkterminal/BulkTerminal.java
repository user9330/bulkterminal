package com.dloi.bulkterminal;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dloi.bulkterminal.definition.BTCreativeTab;
import com.dloi.bulkterminal.definition.BTItems;
import com.dloi.bulkterminal.definition.BTMenus;

/**
 * 大宗存储终端（Bulk Terminal）。
 *
 * <p>AE2 的附属、且依赖 MEGA Cells：提供一个线缆终端部件，专门面向 MEGA 大宗存储元件，
 * 并在首次存入某个物品时自动为一块空闲的大宗盘打上该物品的标记。
 *
 * <p>设计与实现的权威依据是仓库根的 {@code docs/bulk-terminal-design.md}。
 */
@Mod(BulkTerminal.MOD_ID)
public class BulkTerminal {
    public static final String MOD_ID = "bulkterminal";

    public static final Logger LOGGER = LoggerFactory.getLogger("BulkTerminal");

    public BulkTerminal(IEventBus modBus) {
        BTItems.DR.register(modBus);
        BTMenus.DR.register(modBus);
        BTCreativeTab.DR.register(modBus);

        LOGGER.info("Bulk Terminal 初始化完成");
    }

    public static ResourceLocation makeId(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
