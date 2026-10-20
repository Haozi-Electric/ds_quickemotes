package com.hoz.ds_quickemotes.client.screen;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.body.emotes.DragonEmote;
import com.hoz.ds_quickemotes.bridge.DSEmoteBridge;
import com.hoz.ds_quickemotes.client.anim.WheelAnim;
import com.hoz.ds_quickemotes.client.preview.DragonPreview;
import com.hoz.ds_quickemotes.client.util.EmoteAttributeLines;
import com.hoz.ds_quickemotes.client.widget.FadingWidget;
import com.hoz.ds_quickemotes.client.widget.SimpleButton;
import com.hoz.ds_quickemotes.client.widget.SimpleRow;
import com.hoz.ds_quickemotes.client.widget.SimpleToggle;
import com.hoz.ds_quickemotes.client.widget.WheelStyle;
import com.hoz.ds_quickemotes.combo.EmoteCombo;
import com.hoz.ds_quickemotes.combo.EmoteComboStore;
import com.hoz.ds_quickemotes.config.QuickEmotesConfig;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 组合编辑器：单屏三栏（组合列表 / 当前成员 / 全部表情），按当前龙种分文件存储。
 */
public class ComboEditorScreen extends Screen {
    private static final int COLUMN_WIDTH = 150;
    private static final int COLUMN_GAP = 10;
    private static final int ROW_HEIGHT = 18;
    private static final int PANEL_PADDING = 6;
    private static final int HEADER_HEIGHT = 15;
    private static final int MAX_MEMBERS = 4;
    private static final int BOTTOM_BUTTON_WIDTH = 72;
    private static final int BOTTOM_BUTTON_GAP = 6;
    private static final int NAME_ROW_HEIGHT = 18;
    /** 输入框右侧的锁定按钮：宽度取中栏 1/3，与输入框的间隙同下方按钮组 */
    private static final int LOCK_BUTTON_WIDTH = COLUMN_WIDTH / 3;
    private static final int NAME_BOX_WIDTH = COLUMN_WIDTH - BOTTOM_BUTTON_GAP - LOCK_BUTTON_WIDTH;
    /** 成员面板 / 输入框 / 预览框之间的竖向间距 */
    private static final int STACK_GAP = 4;
    private static final int MEMBER_COUNT_HEIGHT = 12;
    /** 成员面板固定高度：标题 + 计数 + 4 行成员 + 内边距（保证 4 成员完整可见） */
    private static final int MEMBER_PANEL_HEIGHT = HEADER_HEIGHT + 2 + MEMBER_COUNT_HEIGHT
            + MAX_MEMBERS * ROW_HEIGHT + PANEL_PADDING;

    private final long openedAt = Util.getMillis();
    private long lastFrameMs;

    private ResourceLocation speciesId;
    private List<EmoteCombo> combos = new ArrayList<>();
    private List<DragonEmote> bodyEmotes = List.of();
    private int selected = -1;

    private final int[] columnLeft = new int[3];
    private int panelTop;
    private int panelBottom;
    private int bottomY;
    /** 中栏纵向布局：成员面板（缩短）→ 名称输入框 → 龙模型预览框，底边与左右栏对齐 */
    private int memberPanelBottom;
    private int inputY;
    private int inputBottom;
    private int dragonTop;
    private int dragonBottom;
    private boolean previewVisible;
    /** 模型脚底锚点/当前缩放（上一帧渲染值，用于模型命中判定） */
    private int previewAnchorX;
    private int previewAnchorY;
    private float previewScale;

    private final List<SimpleRow> comboRows = new ArrayList<>();
    private final List<SimpleRow> memberRows = new ArrayList<>();
    private final List<SimpleRow> libraryRows = new ArrayList<>();
    /** 与 libraryRows 同序：悬停行对应的表情 */
    private final List<DragonEmote> libraryEmotes = new ArrayList<>();
    private final List<FadingWidget> hudWidgets = new ArrayList<>();
    /** 预览目标（下一帧生效；悬停组合/表情时更新，移出为站桩） */
    private List<DragonEmote> previewTarget = List.of();
    /** 预览区拖拽：左键旋转、右键平移（与轮盘同款） */
    private boolean draggingRotate;
    private boolean draggingPan;
    /** 预览区滚轮缩放（仅编辑器内、仅预览区内生效） */
    private float previewZoom = 1f;
    private SimpleButton deleteButton;
    private EditBox nameBox;
    private boolean syncingName;
    /** 本帧悬停行的提示行（原版硬跟随 tooltip；每个 Component 一行） */
    private List<Component> tooltipHint;
    /** 首次 init 从磁盘加载；resize 重跑 init 时保留未提交的内存编辑 */
    private boolean loaded;
    private float scrollGoal;
    private float scroll;
    private float libraryScrollGoal;
    private float libraryScroll;

    public ComboEditorScreen() {
        super(Component.translatable("ds_quickemotes.editor.title"));
    }

    @Override
    protected void init() {
        // 编辑器打开期间同样挂起 DS 全局表情快捷键（其监听在任意非 DS 界面都会触发绑定表情）
        DSEmoteBridge.suspendEmoteKeybinds();

        Player player = minecraft.player;
        speciesId = player != null ? DSEmoteBridge.speciesIdOf(player) : null;
        if (!loaded) {
            combos = speciesId != null ? new ArrayList<>(EmoteComboStore.getCombos(speciesId)) : new ArrayList<>();
            loaded = true;
        }
        bodyEmotes = player != null ? DSEmoteBridge.emotesOf(player) : List.of();
        selected = combos.isEmpty() ? -1 : Mth.clamp(selected, 0, combos.size() - 1);

        int total = COLUMN_WIDTH * 3 + COLUMN_GAP * 2;
        int left = width / 2 - total / 2;
        for (int i = 0; i < 3; i++) {
            columnLeft[i] = left + i * (COLUMN_WIDTH + COLUMN_GAP);
        }
        panelTop = 44;
        panelBottom = panelTop + Math.max(90, Math.min(220, height - panelTop - 72));
        // 窗口过矮时底栏上提，保证按钮始终可点
        bottomY = Math.max(panelTop + 20, Math.min(panelBottom + 14, height - 22));

        // 中栏：成员面板缩短（仍完整容纳 4 成员），输入框随底边上移，空出的下方整块留给预览龙。
        // 成员面板 + 输入框 + 预览框的底边与左右栏对齐，整体组成一个矩形。
        memberPanelBottom = panelTop + MEMBER_PANEL_HEIGHT;
        inputY = memberPanelBottom + STACK_GAP;
        inputBottom = inputY + NAME_ROW_HEIGHT;
        dragonTop = inputBottom + STACK_GAP;
        dragonBottom = panelBottom;
        previewVisible = dragonBottom - dragonTop >= 20;

        SimpleButton create = new SimpleButton(0, 0, BOTTOM_BUTTON_WIDTH, 18,
                Component.translatable("ds_quickemotes.editor.new"), this::createCombo);
        create.setPosition(columnLeft[0], bottomY);
        deleteButton = new SimpleButton(0, 0, BOTTOM_BUTTON_WIDTH, 18,
                Component.translatable("ds_quickemotes.editor.delete"), this::deleteCombo);
        deleteButton.setPosition(columnLeft[0] + COLUMN_WIDTH - BOTTOM_BUTTON_WIDTH, bottomY);
        SimpleButton cancel = new SimpleButton(0, 0, BOTTOM_BUTTON_WIDTH, 18,
                Component.translatable("ds_quickemotes.editor.cancel"), this::onClose);
        cancel.setPosition(columnLeft[2], bottomY);
        SimpleButton done = new SimpleButton(0, 0, BOTTOM_BUTTON_WIDTH, 18,
                Component.translatable("ds_quickemotes.editor.done"), this::commitAndClose);
        done.setPosition(columnLeft[2] + COLUMN_WIDTH - BOTTOM_BUTTON_WIDTH, bottomY);

        // 组合名输入框：EditBox 无边框时文字绘于控件原点（无内边距、无垂直居中），
        // 因此把控件本体挪到名称行文字应有的位置（水平内边距 6、垂直居中）
        nameBox = new EditBox(font, columnLeft[1] + 6, inputY + 5,
                NAME_BOX_WIDTH - 12, 13, Component.empty());
        nameBox.setBordered(false);
        nameBox.setMaxLength(48);
        nameBox.setTextColor(0xFFFFFF);
        nameBox.setTextColorUneditable(0x8A9098);
        nameBox.setResponder(this::onNameChanged);

        // 右侧 1/3：编辑器预览锁定（独立配置项，不影响轮盘预览）
        SimpleToggle previewLock = new SimpleToggle(
                columnLeft[1] + NAME_BOX_WIDTH + BOTTOM_BUTTON_GAP, inputY, LOCK_BUTTON_WIDTH,
                Component.translatable("ds_quickemotes.editor.lock_preview"),
                QuickEmotesConfig.EDITOR_PREVIEW_LOCK.get(),
                checked -> {
                    QuickEmotesConfig.EDITOR_PREVIEW_LOCK.set(checked);
                    QuickEmotesConfig.SPEC.save();
                });
        previewLock.setTooltip(Tooltip.create(
                Component.translatable("ds_quickemotes.editor.lock_preview_tooltip")));

        addRenderableWidget(create);
        addRenderableWidget(deleteButton);
        addRenderableWidget(cancel);
        addRenderableWidget(done);
        addRenderableWidget(nameBox);
        addRenderableWidget(previewLock);

        hudWidgets.clear();
        hudWidgets.add(create);
        hudWidgets.add(deleteButton);
        hudWidgets.add(cancel);
        hudWidgets.add(done);
        hudWidgets.add(previewLock);

        rebuildRows();
    }

    @Override
    public void removed() {
        // 未点击「完成」的编辑一律丢弃（取消 / Esc 同语义）
        DSEmoteBridge.restoreEmoteKeybinds();

        // 每次关闭界面都把预览的朝向/位置恢复默认，避免模型被拖到屏幕外后找不回来
        DragonPreview.resetView();

        super.removed();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(null);
    }

    // ------------------------------------------------------------ 数据（即时保存）

    /** 只有点击「完成」才写回磁盘并更新缓存；取消 / Esc 一律丢弃 */
    private void persist() {
        if (speciesId != null) {
            EmoteComboStore.setCombos(speciesId, combos);
            EmoteComboStore.save(speciesId);
        }
    }

    private void commitAndClose() {
        persist();
        onClose();
    }

    private void createCombo() {
        if (speciesId == null) {
            return;
        }
        // 新组合默认留空名：单表情组合会自动以该表情命名（组合列表与轮盘同一套兜底规则）
        combos.add(new EmoteCombo("", List.of()));
        selected = combos.size() - 1;
        rebuildRows();
        scrollGoal = Math.max(0f, combos.size() * ROW_HEIGHT - contentHeight());   // 新行滚入视野
    }

    private void deleteCombo() {
        if (speciesId == null || selected < 0 || selected >= combos.size()) {
            return;
        }
        combos.remove(selected);
        selected = Math.min(selected, combos.size() - 1);
        rebuildRows();
    }

    private void selectCombo(final int index) {
        selected = index;
        rebuildRows();
    }

    /** 名字输入：只改内存，点击「完成」才落盘 */
    private void onNameChanged(final String text) {
        if (syncingName || selected < 0 || selected >= combos.size()) {
            return;
        }
        EmoteCombo current = combos.get(selected);
        combos.set(selected, new EmoteCombo(text, current.emoteKeys()));
        if (selected < comboRows.size()) {
            comboRows.get(selected).setMessage(comboLabel(combos.get(selected)));
        }
    }

    private void addMember(final String key) {
        if (selected < 0 || selected >= combos.size()) {
            return;
        }
        EmoteCombo combo = combos.get(selected);
        if (combo.emoteKeys().size() >= MAX_MEMBERS || combo.emoteKeys().contains(key)) {
            return;
        }
        DragonEmote target = DSEmoteBridge.findEmote(bodyEmotes, key);
        boolean hasNonBlend = combo.emoteKeys().stream()
                .map(member -> DSEmoteBridge.findEmote(bodyEmotes, member))
                .anyMatch(member -> member != null && !member.blend());
        if (target != null && !target.blend() && hasNonBlend) {
            return;
        }

        List<String> keys = new ArrayList<>(combo.emoteKeys());
        keys.add(key);
        combos.set(selected, new EmoteCombo(combo.name(), keys));
        rebuildRows();
    }

    private void removeMember(final String key) {
        if (selected < 0 || selected >= combos.size()) {
            return;
        }
        EmoteCombo combo = combos.get(selected);
        List<String> keys = new ArrayList<>(combo.emoteKeys());
        if (!keys.remove(key)) {
            return;
        }
        combos.set(selected, new EmoteCombo(combo.name(), keys));
        rebuildRows();
    }

    private void rebuildRows() {
        // 左：组合列表
        comboRows.clear();
        for (int i = 0; i < combos.size(); i++) {
            int index = i;
            SimpleRow row = new SimpleRow(0, 0, COLUMN_WIDTH - PANEL_PADDING * 2, ROW_HEIGHT,
                    comboLabel(combos.get(i)), () -> selectCombo(index));
            row.setSelected(i == selected);
            comboRows.add(row);
        }

        // 中：成员行（点击整行 = 移回右栏，悬停显示 → 箭头）；右：表情库行（驱赶式：已加入项隐藏）
        memberRows.clear();
        libraryRows.clear();
        libraryEmotes.clear();

        if (selected >= 0 && selected < combos.size()) {
            EmoteCombo combo = combos.get(selected);
            List<String> keys = combo.emoteKeys();

            for (String key : keys) {
                DragonEmote emote = DSEmoteBridge.findEmote(bodyEmotes, key);
                String label = "· " + (emote != null ? emote.name().getString() : key);
                SimpleRow row = new SimpleRow(0, 0, COLUMN_WIDTH - PANEL_PADDING * 2, ROW_HEIGHT,
                        Component.literal(label), () -> removeMember(key));
                row.setHoverArrow("→");
                if (emote == null) {
                    row.setTextColorOverride(0xFFFF6B6B);
                    row.setHoverHint(Component.translatable("ds_quickemotes.editor.invalid_member"));
                }
                memberRows.add(row);
            }

            boolean hasNonBlend = keys.stream()
                    .map(member -> DSEmoteBridge.findEmote(bodyEmotes, member))
                    .anyMatch(member -> member != null && !member.blend());
            boolean full = keys.size() >= MAX_MEMBERS;

            for (DragonEmote emote : bodyEmotes) {
                if (keys.contains(emote.key())) {
                    continue;   // 驱赶式：已在组合中的表情从右栏隐藏，而非置灰
                }
                SimpleRow row = new SimpleRow(0, 0, COLUMN_WIDTH - PANEL_PADDING * 2, ROW_HEIGHT,
                        emote.name(), () -> addMember(emote.key()));
                row.setHoverArrow("←");
                if (full) {
                    row.active = false;
                    row.setHoverHint(Component.translatable("ds_quickemotes.editor.reason_full", MAX_MEMBERS));
                } else if (!emote.blend() && hasNonBlend) {
                    row.active = false;
                    row.setTextColorOverride(0xFFFF6B6B);
                    row.setHoverHint(Component.translatable("ds_quickemotes.editor.reason_nonblend"));
                } else {
                    // 可添加时：悬停显示该表情的属性列表（不含使用中/强制循环状态行）
                    row.setHoverLines(attributeLines(emote));
                }
                libraryRows.add(row);
                libraryEmotes.add(emote);
            }
        }

        if (deleteButton != null) {
            deleteButton.active = selected >= 0 && selected < combos.size();
        }
        if (nameBox != null) {
            syncingName = true;
            boolean hasSelection = selected >= 0 && selected < combos.size();
            nameBox.setVisible(hasSelection);
            nameBox.setValue(hasSelection ? combos.get(selected).name() : "");
            syncingName = false;
        }

        float maxScroll = Math.max(0f, combos.size() * ROW_HEIGHT - contentHeight());
        scrollGoal = Mth.clamp(scrollGoal, 0f, maxScroll);
        scroll = Mth.clamp(scroll, 0f, maxScroll);
        float maxLibraryScroll = Math.max(0f, libraryRows.size() * ROW_HEIGHT - contentHeight());
        libraryScrollGoal = Mth.clamp(libraryScrollGoal, 0f, maxLibraryScroll);
        libraryScroll = Mth.clamp(libraryScroll, 0f, maxLibraryScroll);
    }

    /** 悬停属性行：与轮盘属性面板同源（不含使用中/强制循环状态行），每个元素一行 */
    private List<Component> attributeLines(final DragonEmote emote) {
        return EmoteAttributeLines.of(minecraft.player, emote);
    }

    /** 未命名组合的展示名与轮盘一致：单表情用表情名，多表情用兜底名 */
    private Component comboLabel(final EmoteCombo combo) {
        if (!combo.name().isBlank()) {
            return Component.literal(combo.name());
        }
        List<String> keys = combo.emoteKeys();
        if (keys.size() == 1) {
            DragonEmote emote = DSEmoteBridge.findEmote(bodyEmotes, keys.get(0));
            return Component.literal(emote != null ? emote.name().getString() : keys.get(0));
        }
        return Component.translatable("ds_quickemotes.combo.default_name");
    }

    // ------------------------------------------------------------ 几何

    private int contentTop() {
        return panelTop + HEADER_HEIGHT + 2;
    }

    private int contentBottom() {
        return panelBottom - PANEL_PADDING;
    }

    private int contentHeight() {
        return contentBottom() - contentTop();
    }

    private boolean overColumn(final int column, final double mouseX, final double mouseY) {
        // 中栏只到缩短后的成员面板底部（输入框与预览框不是列表区）
        int bottom = column == 1 ? Math.min(contentBottom(), memberPanelBottom - PANEL_PADDING) : contentBottom();
        return mouseX >= columnLeft[column] && mouseX < columnLeft[column] + COLUMN_WIDTH
                && mouseY >= contentTop() && mouseY < bottom;
    }

    // ------------------------------------------------------------ 渲染

    @Override
    public void renderBackground(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        // 暗色底由 render() 手动绘制：super.render 的调用顺序在 UI 之后，背景放这里会盖住界面
    }

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        long now = Util.getMillis();
        float dt = lastFrameMs == 0L ? 0f : Mth.clamp(now - lastFrameMs, 0L, (long) WheelAnim.MAX_FRAME_MS);
        lastFrameMs = now;
        float openAlpha = WheelAnim.easeOutCubic(Mth.clamp((now - openedAt) / WheelAnim.OPEN_MS, 0f, 1f));
        scroll = WheelAnim.smooth(scroll, scrollGoal, 60f, dt);
        libraryScroll = WheelAnim.smooth(libraryScroll, libraryScrollGoal, 60f, dt);

        // 程序化暗色底：世界隐约可见，与轮盘的简约风格一致
        graphics.fill(0, 0, width, height, 0xD0090A0E);

        // 开放式预览：先画龙（DragonPreview 内部做深度清理），之后再画的 UI 全部覆盖在模型之上
        renderPreview(graphics, minecraft.player, partialTick, openAlpha);
        List<DragonEmote> previousPreview = previewTarget;
        previewTarget = List.of();

        for (FadingWidget widget : hudWidgets) {
            widget.setAlpha(openAlpha);
        }

        if (openAlpha > 0.004f) {
            graphics.drawCenteredString(font, title, width / 2, panelTop - 20,
                    WheelAnim.mulAlpha(0xFFFFFF, openAlpha));
        }

        tooltipHint = null;
        drawPanels(graphics, openAlpha);
        drawComboList(graphics, mouseX, mouseY, partialTick, openAlpha);
        drawMembers(graphics, mouseX, mouseY, partialTick, openAlpha);
        drawLibrary(graphics, mouseX, mouseY, partialTick, openAlpha);

        // 编辑器预览锁定：没有悬停行时沿用上一帧的目标；关闭时回到站桩
        if (previewTarget.isEmpty() && QuickEmotesConfig.EDITOR_PREVIEW_LOCK.get()) {
            previewTarget = previousPreview;
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        // 原版硬跟随 tooltip（直接按鼠标位置绘制，无延迟/滑动动画；多行用 List）
        if (tooltipHint != null) {
            graphics.renderTooltip(font, tooltipHint, Optional.empty(), mouseX, mouseY);
        }
    }

    /** 中栏名称框下方预览框内的龙（复用轮盘的 DragonPreview；空间不足时整块隐藏） */
    private void renderPreview(final GuiGraphics graphics, final Player player,
                               final float partialTick, final float openAlpha) {
        if (player == null || !previewVisible) {
            return;
        }

        int top = dragonTop;
        int bottom = dragonBottom;
        int areaHeight = bottom - top;
        if (areaHeight < 20) {
            return;
        }

        // 与轮盘预览同款：开放式绘制，不裁剪；按可用高度收敛缩放，再乘区域内的滚轮缩放系数
        previewScale = Mth.clamp(areaHeight / 3.4f, 12f, 40f) * previewZoom * (0.9f + 0.1f * openAlpha);
        previewAnchorX = columnLeft[1] + COLUMN_WIDTH / 2;
        previewAnchorY = bottom - 4;
        DragonPreview.setTarget(player, previewTarget);
        DragonPreview.render(graphics, player, partialTick, previewAnchorX, previewAnchorY, previewScale);
    }

    /** 组合 → 预览用的表情列表（跳过失效/重复项，顺序与轮盘实际应用一致：组合成员自上而下） */
    private List<DragonEmote> emotesOfCombo(final EmoteCombo combo) {
        List<DragonEmote> emotes = new ArrayList<>(combo.emoteKeys().size());
        for (String key : combo.emoteKeys()) {
            DragonEmote emote = DSEmoteBridge.findEmote(bodyEmotes, key);
            if (emote != null && !emotes.contains(emote)) {
                emotes.add(emote);
            }
        }
        return emotes;
    }

    private void drawPanels(final GuiGraphics graphics, final float openAlpha) {
        if (openAlpha <= 0.004f) {
            return;
        }
        Component[] headers = {
                Component.translatable("ds_quickemotes.editor.combo_list"),
                Component.translatable("ds_quickemotes.editor.members"),
                Component.translatable("ds_quickemotes.editor.emotes")
        };
        int fill = WheelAnim.mulAlpha(WheelStyle.FILL, openAlpha);
        int border = WheelAnim.mulAlpha(WheelStyle.BORDER, openAlpha);
        int headerColor = WheelAnim.mulAlpha(WheelStyle.TEXT_DIM, openAlpha);

        for (int i = 0; i < 3; i++) {
            int left = columnLeft[i];
            int right = left + COLUMN_WIDTH;
            // 中栏成员面板缩短：输入框与预览框占用其下方空间
            int columnBottom = i == 1 ? memberPanelBottom : panelBottom;
            WheelStyle.panel(graphics, left, panelTop, right, columnBottom, fill, border);
            graphics.drawString(font, headers[i], left + PANEL_PADDING, panelTop + 5, headerColor, false);
            graphics.fill(left + PANEL_PADDING, panelTop + HEADER_HEIGHT, right - PANEL_PADDING, panelTop + HEADER_HEIGHT + 1,
                    WheelAnim.mulAlpha(WheelStyle.BORDER, openAlpha * 0.6f));
        }

        if (selected >= 0 && selected < combos.size()) {
            // 输入框占中栏左侧 2/3，右侧 1/3 是锁定按钮
            WheelStyle.panel(graphics, columnLeft[1], inputY, columnLeft[1] + NAME_BOX_WIDTH, inputBottom, fill, border);
        }
        // 预览龙为开放式：不画边框、不裁剪，UI 在其后绘制覆盖模型
    }

    private void drawComboList(final GuiGraphics graphics, final int mouseX, final int mouseY,
                               final float partialTick, final float openAlpha) {
        if (openAlpha <= 0.004f) {
            return;
        }
        int left = columnLeft[0] + PANEL_PADDING;
        int top = contentTop();
        int bottom = contentBottom();

        if (combos.isEmpty()) {
            graphics.drawString(font, Component.translatable("ds_quickemotes.editor.empty_list"),
                    left, top + 2, WheelAnim.mulAlpha(WheelStyle.TEXT_DIM, openAlpha), false);
            return;
        }

        boolean inside = overColumn(0, mouseX, mouseY);
        int rowY = top - (int) scroll;
        graphics.enableScissor(columnLeft[0] + 1, top, columnLeft[0] + COLUMN_WIDTH - 1, bottom);
        for (int i = 0; i < comboRows.size(); i++) {
            SimpleRow row = comboRows.get(i);
            row.setX(left);
            row.setY(rowY);
            row.setAlpha(openAlpha);
            row.render(graphics, inside ? mouseX : -1, inside ? mouseY : -1, partialTick);
            if (inside && row.isHovered()) {
                previewTarget = emotesOfCombo(combos.get(i));
            }
            rowY += ROW_HEIGHT;
        }
        graphics.disableScissor();
    }

    private void drawMembers(final GuiGraphics graphics, final int mouseX, final int mouseY,
                             final float partialTick, final float openAlpha) {
        if (openAlpha <= 0.004f) {
            return;
        }
        int left = columnLeft[1] + PANEL_PADDING;
        int dim = WheelAnim.mulAlpha(WheelStyle.TEXT_DIM, openAlpha);
        int countY = contentTop();
        int rowsTop = countY + MEMBER_COUNT_HEIGHT;

        if (selected < 0 || selected >= combos.size()) {
            graphics.drawString(font, Component.translatable("ds_quickemotes.editor.select_hint"),
                    left, rowsTop, dim, false);
            return;
        }

        EmoteCombo combo = combos.get(selected);
        graphics.drawString(font, Component.translatable("ds_quickemotes.editor.member_count",
                        combo.emoteKeys().size(), MAX_MEMBERS),
                left, countY, WheelAnim.mulAlpha(WheelStyle.TEXT, openAlpha), false);

        if (memberRows.isEmpty()) {
            graphics.drawString(font, Component.translatable("ds_quickemotes.editor.empty_members"),
                    left, rowsTop, dim, false);
            return;
        }

        boolean inside = overColumn(1, mouseX, mouseY);
        graphics.enableScissor(columnLeft[1] + 1, rowsTop,
                columnLeft[1] + COLUMN_WIDTH - 1, memberPanelBottom - PANEL_PADDING);
        int rowY = rowsTop;
        for (int i = 0; i < memberRows.size(); i++) {
            SimpleRow row = memberRows.get(i);
            row.setX(left);
            row.setY(rowY);
            row.setAlpha(openAlpha);
            row.render(graphics, inside ? mouseX : -1, inside ? mouseY : -1, partialTick);
            if (inside && row.isHovered()) {
                if (row.getHoverHint() != null) {
                    tooltipHint = row.getHoverHint();
                }
                DragonEmote emote = DSEmoteBridge.findEmote(bodyEmotes, combo.emoteKeys().get(i));
                previewTarget = emote != null ? List.of(emote) : List.of();
            }
            rowY += ROW_HEIGHT;
        }
        graphics.disableScissor();
    }

    private void drawLibrary(final GuiGraphics graphics, final int mouseX, final int mouseY,
                             final float partialTick, final float openAlpha) {
        if (openAlpha <= 0.004f) {
            return;
        }
        int left = columnLeft[2] + PANEL_PADDING;
        int top = contentTop();
        int bottom = contentBottom();

        if (bodyEmotes.isEmpty()) {
            graphics.drawString(font, Component.translatable("ds_quickemotes.editor.no_body"),
                    left, top + 2, WheelAnim.mulAlpha(WheelStyle.TEXT_DIM, openAlpha), false);
            return;
        }
        if (libraryRows.isEmpty()) {
            graphics.drawString(font, Component.translatable("ds_quickemotes.editor.select_hint"),
                    left, top + 2, WheelAnim.mulAlpha(WheelStyle.TEXT_DIM, openAlpha), false);
            return;
        }

        boolean inside = overColumn(2, mouseX, mouseY);
        graphics.enableScissor(columnLeft[2] + 1, top, columnLeft[2] + COLUMN_WIDTH - 1, bottom);
        int rowY = top - (int) libraryScroll;
        for (int i = 0; i < libraryRows.size(); i++) {
            SimpleRow row = libraryRows.get(i);
            row.setX(left);
            row.setY(rowY);
            row.setAlpha(openAlpha);
            row.render(graphics, inside ? mouseX : -1, inside ? mouseY : -1, partialTick);
            if (inside && row.isHovered()) {
                if (row.getHoverHint() != null) {
                    tooltipHint = row.getHoverHint();
                }
                previewTarget = List.of(libraryEmotes.get(i));
            }
            rowY += ROW_HEIGHT;
        }
        graphics.disableScissor();
    }

    // ------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        // 点击输入框以外的地方 = 失去焦点
        if (nameBox != null && nameBox.isFocused() && !nameBox.isMouseOver(mouseX, mouseY)) {
            nameBox.setFocused(false);
        }

        // 底部按钮优先
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // 点击会触发 rebuildRows 重建列表，命中后必须立即返回，避免并发修改。
            // 列表行优先于预览拖拽（重叠时拖拽优先级最低）。
            if (overColumn(0, mouseX, mouseY)) {
                for (SimpleRow row : comboRows) {
                    if (row.mouseClicked(mouseX, mouseY, button)) {
                        return true;
                    }
                }
            } else if (overColumn(1, mouseX, mouseY)) {
                for (SimpleRow row : memberRows) {
                    if (row.mouseClicked(mouseX, mouseY, button)) {
                        return true;
                    }
                }
            } else if (overColumn(2, mouseX, mouseY)) {
                for (SimpleRow row : libraryRows) {
                    if (row.mouseClicked(mouseX, mouseY, button)) {
                        return true;
                    }
                }
            }
        }

        // 模型上按下：左键开始旋转、右键开始平移；命中区随模型平移，可拖到屏幕任意位置
        if (previewVisible
                && (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                && DragonPreview.isMouseOverModel(previewAnchorX, previewAnchorY, previewScale, mouseX, mouseY)) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                draggingRotate = true;
            } else {
                draggingPan = true;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button, final double dragX, final double dragY) {
        if (draggingRotate && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // 预览区内左键拖拽 = 旋转（与轮盘相同灵敏度）
            DragonPreview.addRotation((float) (-dragX / 5.0), (float) (-dragY / 5.0));
            return true;
        }
        if (draggingPan && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            // 右键拖拽 = 平移（不限制边界，可拖到屏幕任意位置）
            DragonPreview.addPan(dragX, dragY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (draggingRotate && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            draggingRotate = false;
            return true;
        }
        if (draggingPan && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            draggingPan = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean inPreviewArea(final double mouseX, final double mouseY) {
        return mouseX >= columnLeft[1] && mouseX < columnLeft[1] + COLUMN_WIDTH
                && mouseY >= dragonTop && mouseY < dragonBottom;
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        if (scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        // 预览区内滚轮 = 缩放（列表滚动仍走下面的列判定）
        if (previewVisible && inPreviewArea(mouseX, mouseY)) {
            previewZoom = Mth.clamp((float) (previewZoom * Math.pow(1.1, scrollY)), 0.5f, 2.5f);
            return true;
        }
        if (overColumn(0, mouseX, mouseY)) {
            float maxScroll = Math.max(0f, combos.size() * ROW_HEIGHT - contentHeight());
            scrollGoal = Mth.clamp(scrollGoal - (float) scrollY * 14f, 0f, maxScroll);
            return true;
        }
        if (overColumn(2, mouseX, mouseY)) {
            float maxScroll = Math.max(0f, libraryRows.size() * ROW_HEIGHT - contentHeight());
            libraryScrollGoal = Mth.clamp(libraryScrollGoal - (float) scrollY * 14f, 0f, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
