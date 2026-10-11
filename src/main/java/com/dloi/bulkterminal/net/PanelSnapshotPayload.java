package com.dloi.bulkterminal.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.dloi.bulkterminal.BulkTerminal;

/**
 * 服务端 → 客户端的「大宗盘管理」界面快照：网络卡数、开关/阈值、每块盘一行。
 * 操作反馈不走快照，由服务端直接发聊天框（见 PanelOps.feedback）。
 *
 * <p>物品一律以 ID 字符串传输（空串 = 无），客户端自行解析图标；
 * display 行格式为 {@code itemId|count}（已按档位发布，与终端可见内容一致）。
 */
public record PanelSnapshotPayload(
        long cards,
        boolean autoMark,
        long threshold,
        List<Row> rows) implements CustomPacketPayload {

    public static final PanelSnapshotPayload EMPTY = new PanelSnapshotPayload(
            0, true, 0, List.of());

    public static final Type<PanelSnapshotPayload> TYPE =
            new Type<>(BulkTerminal.makeId("panel_snapshot"));

    public static final StreamCodec<FriendlyByteBuf, PanelSnapshotPayload> STREAM_CODEC =
            StreamCodec.ofMember(PanelSnapshotPayload::write, PanelSnapshotPayload::read);

    /**
     * 界面里一块大宗盘的行数据。
     *
     * <p>{@code id} 是一次界面会话内稳定的行号（服务端在会话建立时分配，单调递增），
     * 与行在列表里的位置无关：界面打开期间档位变化只更新数据、不重排行序，
     * 装卡 / 取卡 / 切档位的点击也按 id 回传定位，避免因实时重排导致的误触。
     */
    public record Row(
            int priority,
            String host,
            String filterId,
            String storedId,
            boolean hasCard,
            boolean mismatch,
            boolean canFit,
            int cutoff,
            List<String> chain,
            List<String> display,
            int id) {

        private void write(FriendlyByteBuf buf) {
            buf.writeVarInt(priority);
            buf.writeUtf(host);
            buf.writeUtf(filterId);
            buf.writeUtf(storedId);
            buf.writeBoolean(hasCard);
            buf.writeBoolean(mismatch);
            buf.writeBoolean(canFit);
            buf.writeVarInt(cutoff);
            writeStrings(buf, chain);
            writeStrings(buf, display);
            buf.writeVarInt(id);
        }

        private static Row read(FriendlyByteBuf buf) {
            return new Row(
                    buf.readVarInt(),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readVarInt(),
                    readStrings(buf),
                    readStrings(buf),
                    buf.readVarInt());
        }
    }

    private static void writeStrings(FriendlyByteBuf buf, List<String> strings) {
        buf.writeVarInt(strings.size());

        for (var s : strings) {
            buf.writeUtf(s);
        }
    }

    private static List<String> readStrings(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        var result = new ArrayList<String>(size);

        for (int i = 0; i < size; i++) {
            result.add(buf.readUtf());
        }

        return result;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarLong(cards);
        buf.writeBoolean(autoMark);
        buf.writeVarLong(threshold);
        buf.writeVarInt(rows.size());

        for (var row : rows) {
            row.write(buf);
        }
    }

    private static PanelSnapshotPayload read(FriendlyByteBuf buf) {
        return new PanelSnapshotPayload(
                buf.readVarLong(),
                buf.readBoolean(),
                buf.readVarLong(),
                readRows(buf));
    }

    private static List<Row> readRows(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        var result = new ArrayList<Row>(size);

        for (int i = 0; i < size; i++) {
            result.add(Row.read(buf));
        }

        return result;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
