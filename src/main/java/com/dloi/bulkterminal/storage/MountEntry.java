package com.dloi.bulkterminal.storage;

import appeng.api.networking.IGridNode;
import appeng.api.storage.IStorageProvider;

import gripe._90.megacells.item.cell.BulkCellInventory;

/**
 * 网络中一块大宗盘的挂载快照。
 *
 * @param node     宿主在网格中的节点，重新挂载时要用到它
 * @param provider 挂载来源（驱动 / 单元坞 / 第三方），保留备用
 * @param priority 该存储挂载时携带的优先级，用于挑盘顺序
 * @param cell     大宗盘自身的存储视图；宿主重新挂载后本实例会失效，所以不能长期持有
 */
public record MountEntry(IGridNode node, IStorageProvider provider, int priority, BulkCellInventory cell) {}
