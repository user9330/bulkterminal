package com.dloi.bulkterminal.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import guideme.PageAnchor;
import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import appeng.client.gui.AESubScreen;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.AE2Button;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.Scrollbar;
import appeng.menu.SlotSemantics;

import com.dloi.bulkterminal.menu.BulkTerminalMenu;
import com.dloi.bulkterminal.net.PanelClientState;
import com.dloi.bulkterminal.net.PanelSnapshotPayload;

/**
 * 「压缩管理」子界面：装/取压缩卡、切换压缩档位、一键转移、自动标记开关与保留阈值。
 *
 * <p>入口在终端主界面垂直按钮栏顶部（{@link BulkTerminalScreen}），返回键也直接回主界面。
 * 数据来自服务端推送的 {@link PanelSnapshotPayload}（打开时主动请求一次，
 * 之后每次操作服务端都会推新快照）；控件在快照到达后于 containerTick 重建。
 * 行区域用 AE2 Scrollbar 滚动（装卡按钮右侧；captureMouseWheel=true，滚轮全屏生效）。
 * 搜索框按压缩链任意一档物品（或 #盘号）过滤磁盘行。
 */
public class BulkCellManageScreen extends AESubScreen<BulkTerminalMenu, BulkTerminalScreen> {
    private static final String STYLE = "/screens/terminals/bulkterminal_manage.json";

    private static final int ROW_HEIGHT = 34;
    private static final int ROWS_TOP = 88; // 64..80 是搜索框
    private static final int PANEL_HEIGHT = 232;
    private static final int WINDOW_ROWS = (PANEL_HEIGHT - ROWS_TOP - 8) / ROW_HEIGHT;

    // 对话框背景：本模组自己的 256×256 九宫格贴图（边框 4px，内部平铺），
    // 算法与 AE2 BackgroundGenerator 相同，贴图不再依赖 ae2 命名空间
    private static final ResourceLocation DIALOG_BG = BTPictureButton.texture("manage_dialog_bg.png");
    private static final int BG_SIZE = 256;
    private static final int BG_BORDER = 4;
    private static final int BG_TILE = BG_SIZE - 2 * BG_BORDER;

    private final Scrollbar scrollbar;
    private PanelSnapshotPayload data = PanelClientState.peek();
    private boolean sentInitialRefresh;
    private int lastScroll;

    private AE2Button autoMarkButton;
    private AE2Button transferButton;
    private AETextField thresholdBox;
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();

    /** 搜索框 = AE2 原生 AETextField（与终端搜索框同源），命名控件，位置/尺寸由样式 JSON 维护 */
    private AETextField searchField;
    private String searchFilter = "";
    /** 搜索过滤后的可见行 → 数据行下标；空搜索时是全量映射 */
    private List<Integer> view = List.of();
    /**
     * 全部行的显示顺序（会话行 id 列表）：打开界面收到首个快照时排一次，之后<strong>冻结</strong>——
     * 会话期间档位/名字变化只更新行数据、不重排，新出现的盘追加到末尾；
     * 重新打开界面才再次排序。与服务端的会话冻结（PanelOps.orderedStacks）配合，
     * 避免装卡/取卡后行跳动导致的误触。
     */
    private List<Integer> order = List.of();
    private boolean orderBuilt;
    /** 行 id → 当前快照数据下标（每次快照后重建） */
    private Map<Integer, Integer> idToRow = Map.of();
    /** 行 id → 稳定盘号（1 起；行首「#N」与 #N 搜索都用它，会话内不变） */
    private Map<Integer, Integer> rowSeq = Map.of();

    public BulkCellManageScreen(BulkTerminalScreen parent) {
        super(parent, STYLE);

        // 自绘文字走 AE2 前景层：工具提示在前景层之后绘制，永远盖在内容之上（不得改回 render() 里直接画）
        widgets.add("btForeground", new BTScreenForeground(this::drawForegroundContent));

        // AE2 原生滚动条：BIG 样式 = 终端同款，经 WidgetContainer 原生注册路径
        scrollbar = widgets.addScrollBar("scrollbar", Scrollbar.BIG);

        // 搜索框 = AE2 原生 AETextField，经 addTextField 与终端搜索框同一路径注册；
        // 位置/尺寸由样式 JSON "search" 条目给出（原生边框贴图高 12px，条目高度必须是 12，
        // 拉高会让原版黑框从 AE2 边框下缘露出来，看起来像两个框叠在一起）
        searchField = widgets.addTextField("search");

        // 阈值输入同走 addTextField 原生路径（setBordered(false) + 白字 + AE2 选区色）
        thresholdBox = widgets.addTextField("threshold");

        // 命名控件只能注册一次：AEBaseScreen.init 每次只会重新 populate，
        // WidgetContainer 的 id 表不随 rebuildWidgets 清空，重复注册会崩 Duplicate id
        addBackButton();
    }

    @Override
    protected void init() {
        super.init();

        setSlotsHidden(SlotSemantics.TOOLBOX, true);

        if (!sentInitialRefresh) {
            sentInitialRefresh = true;
            menu.panelRefresh();
        }

        buildStaticWidgets();
        reindex();
        refreshView();
        buildRowWidgets();
    }

    /**
     * 管理页不显示「打开指南」按钮（用户要求：指南按钮只在主终端置顶）。
     * AE2 在 updateBeforeRender 里每帧执行 setVisibility(getHelpTopic() != null)，
     * 所以必须覆写本方法返回 null，单次 setVisibility(false) 会被每帧覆盖回去。
     */
    @Override
    @Nullable
    protected PageAnchor getHelpTopic() {
        return null;
    }

    @Override
    public void containerTick() {
        super.containerTick();

        if (PanelClientState.consumeUpdated()) {
            data = PanelClientState.peek();

            if (!orderBuilt) {
                // 本界面会话的首个快照：排出整个会话的固定行序
                orderBuilt = true;
                buildOrder();
            } else {
                // 后续快照：冻结行序，只调和增删
                reconcileOrder();
            }

            reindex();
            refreshView();
            rebuildWidgets();
        }

        // 滚动条被拖拽/滚轮后（captureMouseWheel=true，全屏滚轮都归它），同步重建行按钮
        if (scrollbar.getCurrentScroll() != lastScroll) {
            lastScroll = scrollbar.getCurrentScroll();
            rebuildRowWidgets();
        }
    }

    /**
     * 每帧同步滚动条几何：轨道盖住整个行区，可滚动范围 = 盘数 - 可见行数。
     */
    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();

        scrollbar.setHeight(PANEL_HEIGHT - ROWS_TOP - 8);
        scrollbar.setRange(0, Math.max(0, view.size() - WINDOW_ROWS), 1);
    }

    /**
     * 打开界面后的首个快照：按档位 + 本地化名字母序排出整个会话的固定行序。
     *
     * <p>排序键是<strong>本地化显示名</strong>，照 JEI 的做法
     * （{@code IngredientSorterComparators.getAlphabeticalComparator} 直接
     * {@code Comparator.comparing(i -> i.getNames().getFirst())}，不做拼音转换）。
     * JEI 能这么做是因为它是纯客户端模组；这里行名同样只能在客户端取，
     * 所以字母序这一段放在客户端而不是服务端。
     */
    private void buildOrder() {
        var rows = data.rows();
        var all = new ArrayList<Integer>(rows.size());

        for (int i = 0; i < rows.size(); i++) {
            all.add(i);
        }

        all.sort(Comparator.comparingInt(this::rowRank).thenComparing(this::rowSortName));

        var ids = new ArrayList<Integer>(all.size());

        for (var idx : all) {
            ids.add(rows.get(idx).id());
        }

        order = ids;
    }

    /**
     * 后续快照：<strong>不重新排序</strong>（避免装卡/取卡后行跳动误触）。
     * 旧顺序里还在的行保持原位（数据取最新）；消失的行剔除；新出现的行追加到末尾。
     */
    private void reconcileOrder() {
        var rows = data.rows();
        var present = new HashSet<Integer>();

        for (var row : rows) {
            present.add(row.id());
        }

        var next = new ArrayList<Integer>(rows.size());

        for (var id : order) {
            if (present.remove(id)) {
                next.add(id);
            }
        }

        // 新行按服务端顺序（档位序）追加
        for (var row : rows) {
            if (present.contains(row.id())) {
                next.add(row.id());
            }
        }

        order = next;
    }

    /** 每次快照后重建「行 id → 数据下标」与稳定盘号（#N）。 */
    private void reindex() {
        var rows = data.rows();
        var byId = new HashMap<Integer, Integer>(rows.size());

        for (int i = 0; i < rows.size(); i++) {
            byId.put(rows.get(i).id(), i);
        }

        idToRow = byId;

        var seq = new HashMap<Integer, Integer>(order.size());

        for (int i = 0; i < order.size(); i++) {
            seq.put(order.get(i), i + 1);
        }

        rowSeq = seq;
    }

    /**
     * 按当前搜索词重算可见行映射（空词 = 全部磁盘）。只过滤、不重排：
     * 顺序来自 {@link #order}（打开界面时冻结）。
     *
     * <p>{@code view} 存的是<strong>快照里的数据行下标</strong>，点击动作回传的是
     * 行 id（{@code Row.id()}），两者经 {@link #idToRow} 换算，都不随重排漂移。
     */
    private void refreshView() {
        var q = searchFilter.toLowerCase();
        var rows = data.rows();
        var result = new ArrayList<Integer>(rows.size());

        for (var id : order) {
            var idx = idToRow.get(id);

            if (idx != null && matchesSearch(rows.get(idx), q)) {
                result.add(idx);
            }
        }

        view = result;
    }

    /**
     * 行的可用性档位，与服务端 {@code PanelOps.sortRank} 的判定保持一致：
     * 0 链+卡 / 1 链+无卡 / 2 无链+卡 / 3 无链+无卡+已标记 / 4 未标记。
     * 快照已带 hasCard / chain / filterId / storedId，客户端无需再问服务端。
     */
    private int rowRank(int index) {
        var row = data.rows().get(index);

        // 与服务端 isMarked 对齐：filter 或存量任一非空即已标记
        // （不要用 canFit 判——它额外要求无存量，会误判「有存量但 filter 被清」的盘）
        if (row.filterId().isEmpty() && row.storedId().isEmpty()) {
            return 4;
        }

        boolean hasChain = !row.chain().isEmpty();

        if (hasChain && row.hasCard()) {
            return 0;
        }

        if (hasChain) {
            return 1;
        }

        return row.hasCard() ? 2 : 3;
    }

    /**
     * 行排序名：取该盘显示的本地化物品名（与 {@link #drawRow} 画出来的名字一致）。
     * 空闲盘没有物品，显示的是「空闲盘」翻译，落到同一串里参与排序。
     */
    private String rowSortName(int index) {
        var row = data.rows().get(index);
        var stack = itemById(row.filterId());

        if (stack.isEmpty()) {
            stack = itemById(row.storedId());
        }

        return stack.isEmpty() ? Component.translatable("gui.bulkterminal.idle").getString()
                : stack.getHoverName().getString();
    }

    /**
     * 搜索命中规则：压缩链上任意一档、标记/存量物品的名字或注册 ID，
     * 另支持「#盘号」按稳定盘号直查（如 #3 → 盘号 3 的盘；盘号会话内不变）。
     */
    private boolean matchesSearch(PanelSnapshotPayload.Row row, String q) {
        if (q.isEmpty()) {
            return true;
        }

        if (q.startsWith("#")) {
            try {
                return Integer.parseInt(q.substring(1).trim()) == rowSeq.getOrDefault(row.id(), -1);
            } catch (NumberFormatException e) {
                return false;
            }
        }

        if (matchItem(row.filterId(), q) || matchItem(row.storedId(), q)) {
            return true;
        }

        for (var step : row.chain()) {
            if (matchItem(step, q)) {
                return true;
            }
        }

        return false;
    }

    private boolean matchItem(String id, String q) {
        if (id == null || id.isEmpty()) {
            return false;
        }

        if (id.toLowerCase().contains(q)) {
            return true;
        }

        var stack = itemById(id);
        return !stack.isEmpty() && stack.getHoverName().getString().toLowerCase().contains(q);
    }

    /**
     * 滚动窗口变化后只重建行按钮，不整屏 rebuildWidgets（避免打断阈值输入框的输入状态）。
     */
    private void rebuildRowWidgets() {
        for (var w : rowWidgets) {
            removeWidget(w);
        }
        rowWidgets.clear();
        buildRowWidgets();
    }

    /**
     * 用本模组贴图画对话框背景（尺寸仍取样式 JSON 的 generatedBackground，
     * 那里只提供宽高数值，不再让 AE2 去画它的 background.png）。
     */
    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        var generated = style.getGeneratedBackground();

        if (generated == null) {
            return;
        }

        drawNinePatch(guiGraphics, offsetX, offsetY, generated.getWidth(), generated.getHeight());
    }

    private void drawNinePatch(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        if (width < 2 * BG_BORDER || height < 2 * BG_BORDER) {
            return;
        }

        int right = x + width;
        int bottom = y + height;

        // 四角
        blitBg(guiGraphics, 0, 0, BG_BORDER, BG_BORDER, x, y);
        blitBg(guiGraphics, BG_SIZE - BG_BORDER, 0, BG_BORDER, BG_BORDER, right - BG_BORDER, y);
        blitBg(guiGraphics, 0, BG_SIZE - BG_BORDER, BG_BORDER, BG_BORDER, x, bottom - BG_BORDER);
        blitBg(guiGraphics, BG_SIZE - BG_BORDER, BG_SIZE - BG_BORDER, BG_BORDER, BG_BORDER,
                right - BG_BORDER, bottom - BG_BORDER);

        int innerWidth = width - 2 * BG_BORDER;
        int innerHeight = height - 2 * BG_BORDER;

        // 上下边（横向平铺）
        for (int cx = 0; cx < innerWidth; cx += BG_TILE) {
            int w = Math.min(BG_TILE, innerWidth - cx);
            blitBg(guiGraphics, BG_BORDER, 0, w, BG_BORDER, x + BG_BORDER + cx, y);
            blitBg(guiGraphics, BG_BORDER, BG_SIZE - BG_BORDER, w, BG_BORDER, x + BG_BORDER + cx, bottom - BG_BORDER);
        }

        // 左右边（纵向平铺）
        for (int cy = 0; cy < innerHeight; cy += BG_TILE) {
            int h = Math.min(BG_TILE, innerHeight - cy);
            blitBg(guiGraphics, 0, BG_BORDER, BG_BORDER, h, x, y + BG_BORDER + cy);
            blitBg(guiGraphics, BG_SIZE - BG_BORDER, BG_BORDER, BG_BORDER, h, right - BG_BORDER, y + BG_BORDER + cy);
        }

        // 中央（二维平铺）
        for (int cx = 0; cx < innerWidth; cx += BG_TILE) {
            int w = Math.min(BG_TILE, innerWidth - cx);

            for (int cy = 0; cy < innerHeight; cy += BG_TILE) {
                int h = Math.min(BG_TILE, innerHeight - cy);
                blitBg(guiGraphics, BG_BORDER, BG_BORDER, w, h, x + BG_BORDER + cx, y + BG_BORDER + cy);
            }
        }
    }

    private static void blitBg(GuiGraphics guiGraphics, int srcX, int srcY, int srcW, int srcH, int destX, int destY) {
        Blitter.texture(DIALOG_BG, BG_SIZE, BG_SIZE)
                .src(srcX, srcY, srcW, srcH)
                .dest(destX, destY)
                .blit(guiGraphics);
    }

    private void addBackButton() {
        var label = menu.getHost().getMainMenuIcon().getHoverName();
        widgets.add("back", new ManageBackButton(label, btn -> returnToParent()));
    }

    private void buildStaticWidgets() {
        // 所有按钮用 AE2 原生 AE2Button（eaep 上传映射同款的平面浅紫底 + 深色文字）
        autoMarkButton = new AE2Button(leftPos + 8, topPos + 22, 96, 16, automarkLabel(), b -> {
            menu.panelSetAutoMark(!data.autoMark());
            data = new PanelSnapshotPayload(data.cards(), !data.autoMark(), data.threshold(), data.rows());
            autoMarkButton.setMessage(automarkLabel());
        });
        addRenderableWidget(autoMarkButton);

        thresholdBox.setValue(String.valueOf(data.threshold()));
        thresholdBox.setResponder(value -> {
            try {
                menu.panelSetThreshold(Long.parseLong(value.trim()));
            } catch (NumberFormatException ignored) {
                // 输入过程中的临时状态
            }
        });
        addRenderableWidget(thresholdBox);

        transferButton = new TransferButton(leftPos + 8, topPos + 44, 96, 16,
                Component.translatable("gui.bulkterminal.transfer"),
                b -> {
                    // Shift+点击 = 自动转移：超过 20000 的物品转入大宗盘（未标记的空闲盘先自动标记）；
                    // 悬停提示由 TransferButton 在按住 Shift 时给出
                    if (Screen.hasShiftDown()) {
                        menu.panelTransferAuto();
                    } else {
                        menu.panelTransfer(currentThreshold());
                    }
                });
        addRenderableWidget(transferButton);

        // 搜索框：按压缩链任意一档物品名/ID（或 #盘号）过滤磁盘行，输入即时生效；
        // 空态提示由 AETextField 的 setPlaceholder 负责
        searchField.setMaxLength(60);
        searchField.setPlaceholder(Component.translatable("gui.bulkterminal.search_hint"));
        searchField.setValue(searchFilter);
        searchField.setResponder(value -> {
            var q = value.trim();

            if (q.equals(searchFilter)) {
                return; // init/rebuild 回填同值，避免 init 流程里递归重建
            }

            searchFilter = q;
            refreshView();
            // 过滤后视窗收窄时把滚动位置夹回范围，避免句柄停在界外
            scrollbar.setCurrentScroll(Math.clamp(scrollbar.getCurrentScroll(),
                    0, Math.max(0, view.size() - WINDOW_ROWS)));
            rebuildRowWidgets();
        });

        // 网络压缩卡数量：保留阈值输入框正下方，外观与左侧按钮同款（反馈走聊天框，界面内不再放状态行）
        addRenderableWidget(new BTStatBadge(
                Component.translatable("gui.bulkterminal.cards", data.cards()),
                leftPos + 148, topPos + 44, 52));
    }

    private Component automarkLabel() {
        return Component.translatable("gui.bulkterminal.automark",
                Component.translatable(data.autoMark()
                        ? "gui.bulkterminal.on" : "gui.bulkterminal.off"));
    }

    private long currentThreshold() {
        try {
            return Math.max(0, Long.parseLong(thresholdBox.getValue().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void buildRowWidgets() {
        // rebuildWidgets 会清屏重建，本列表必须同步清空，否则旧一代按钮会被反复叠加
        rowWidgets.clear();

        var rows = data.rows();
        int visible = Math.min(view.size(), WINDOW_ROWS);
        int first = Math.clamp(scrollbar.getCurrentScroll(), 0, Math.max(0, view.size() - visible));

        for (int i = 0; i < visible; i++) {
            // 屏幕上是滚动后的窗口行 i；view 存的是数据行下标，操作按会话行 id 回传
            final int dataIdx = view.get(first + i);
            var row = rows.get(dataIdx);
            int y = topPos + ROWS_TOP + i * ROW_HEIGHT;
            boolean operable = !row.mismatch();

            if (operable && row.chain().size() > 1) {
                var down = new AE2Button(leftPos + 122, y + 10, 14, 14,
                        Component.literal("<"), b -> menu.panelCycleCutoff(row.id(), -1));
                down.active = row.cutoff() > 0;
                rowWidgets.add(down);

                var up = new AE2Button(leftPos + 156, y + 10, 14, 14,
                        Component.literal(">"), b -> menu.panelCycleCutoff(row.id(), 1));
                up.active = row.cutoff() < row.chain().size() - 1;
                rowWidgets.add(up);
            }

            if (operable) {
                var cardBtn = new AE2Button(leftPos + 178, y + 10, 34, 14,
                        Component.translatable(row.hasCard()
                                ? "gui.bulkterminal.take_card" : "gui.bulkterminal.install_card"),
                        b -> {
                            if (row.hasCard()) {
                                menu.panelTakeCard(row.id());
                            } else {
                                menu.panelInstallCard(row.id());
                            }
                        });
                cardBtn.active = row.hasCard() || data.cards() > 0;
                rowWidgets.add(cardBtn);
            }
        }

        rowWidgets.forEach(this::addRenderableWidget);
    }

    /**
     * 界面自绘内容（阈值标签、盘行、溢出提示）。
     * 经 {@link BTScreenForeground} 在 AE2 前景层绘制——早于所有工具提示，
     * 不会压住「打开指南」等提示框。
     *
     * <p>前景层运行在 renderLabels 的平移坐标系里，坐标<b>相对面板左上角</b>；
     * 这里不得再叠加 leftPos/topPos，否则内容整体偏移出面板（控件走绝对坐标、前景层走相对坐标）。
     */
    private void drawForegroundContent(GuiGraphics guiGraphics) {
        // 保留阈值标签（输入框上方）
        guiGraphics.drawString(font,
                Component.translatable("gui.bulkterminal.threshold"),
                148, 13, 0x404040, false);

        // 盘行（滚动窗口：屏幕第 i 行 = view 第 first + i 项，序号仍显示原始盘号）
        var rows = data.rows();
        int visible = Math.min(view.size(), WINDOW_ROWS);
        int first = Math.clamp(scrollbar.getCurrentScroll(), 0, Math.max(0, view.size() - visible));

        for (int i = 0; i < visible; i++) {
            drawRow(guiGraphics, rows.get(view.get(first + i)), ROWS_TOP + i * ROW_HEIGHT);
        }

        // 搜索无结果时给一行提示，面板不至于变成白板
        if (visible == 0 && !rows.isEmpty() && !searchFilter.isEmpty()) {
            guiGraphics.drawString(font,
                    Component.translatable("gui.bulkterminal.search_none"),
                    8, ROWS_TOP + 4, 0x8a8a8a, false);
        }
    }

    private void drawRow(GuiGraphics guiGraphics, PanelSnapshotPayload.Row row, int y) {
        // 行首：稳定盘号（会话内不变，#N 搜索同号） + 宿主 + 优先级
        guiGraphics.drawString(font,
                Component.literal("#" + rowSeq.getOrDefault(row.id(), 0) + " ")
                        .append(Component.literal(row.host() + " · P" + row.priority())
                                .withStyle(ChatFormatting.GRAY)),
                8, y, 0x404040, false);

        // 标记 / 存量
        if (row.canFit()) {
            guiGraphics.drawString(font,
                    Component.translatable("gui.bulkterminal.idle").withStyle(ChatFormatting.DARK_GRAY),
                    26, y + 13, 0x555555, false);
        } else {
            var filterStack = itemById(row.filterId());
            var storedStack = itemById(row.storedId());

            // 标记与存量图标并排画（存量与标记相同时不重复画）
            int iconX = 26;

            if (!filterStack.isEmpty()) {
                guiGraphics.renderItem(filterStack, iconX, y + 9);
                iconX += 18;
            }

            if (!storedStack.isEmpty() && !storedStack.getItem().equals(filterStack.getItem())) {
                guiGraphics.renderItem(storedStack, iconX, y + 9);
                iconX += 18;
            }

            var line = Component.literal("").append(filterStack.isEmpty()
                    ? Component.translatable("gui.bulkterminal.idle")
                    : filterStack.getHoverName());

            if (row.hasCard()) {
                line.append(" ")
                        .append(Component.translatable("gui.bulkterminal.card_ok")
                                .withStyle(ChatFormatting.DARK_GREEN));
            }

            guiGraphics.drawString(font, line, iconX + 1, y + 13, 0x404040, false);
        }

        // 档位图标（按钮之间的展示位）
        if (!row.mismatch() && row.chain().size() > 1) {
            var step = itemById(row.chain().get(Math.min(row.cutoff(), row.chain().size() - 1)));

            if (!step.isEmpty()) {
                guiGraphics.renderItem(step, 138, y + 9);
            }
        }

        // 第三行仅异常盘显示警告（正常盘不再画「终端显示」行——用户要求去除）
        if (row.mismatch()) {
            guiGraphics.drawString(font,
                    Component.translatable("gui.bulkterminal.mismatch").withStyle(ChatFormatting.DARK_RED),
                    8, y + 25, 0xa03030, false);
        }
    }

    private static ItemStack itemById(String id) {
        if (id == null || id.isEmpty()) {
            return ItemStack.EMPTY;
        }

        try {
            return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id))
                    .map(ItemStack::new)
                    .orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }
}
