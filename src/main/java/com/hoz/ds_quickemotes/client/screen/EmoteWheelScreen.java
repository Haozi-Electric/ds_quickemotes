package com.hoz.ds_quickemotes.client.screen;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.body.emotes.DragonEmote;
import com.hoz.ds_quickemotes.bridge.DSEmoteBridge;
import com.hoz.ds_quickemotes.client.QuickEmoteKeybinds;
import com.hoz.ds_quickemotes.client.WheelMode;
import com.hoz.ds_quickemotes.client.anim.WheelAnim;
import com.hoz.ds_quickemotes.client.preview.DragonPreview;
import com.hoz.ds_quickemotes.client.util.EmoteAttributeLines;
import com.hoz.ds_quickemotes.client.wheel.WheelLayout;
import com.hoz.ds_quickemotes.client.wheel.WheelMemory;
import com.hoz.ds_quickemotes.client.wheel.WheelRenderer;
import com.hoz.ds_quickemotes.combo.ComboActivation;
import com.hoz.ds_quickemotes.combo.EmoteCombo;
import com.hoz.ds_quickemotes.combo.EmoteComboStore;
import com.hoz.ds_quickemotes.combo.ForceLoop;
import com.hoz.ds_quickemotes.client.widget.FadingWidget;
import com.hoz.ds_quickemotes.client.widget.SimpleButton;
import com.hoz.ds_quickemotes.client.widget.SimpleToggle;
import com.hoz.ds_quickemotes.client.widget.WheelStyle;
import com.hoz.ds_quickemotes.config.QuickEmotesConfig;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 表情轮盘屏幕。
 * 交互防呆：只有「松开触发键」路径会应用表情；Esc、切屏（removed()）一律不应用；
 * 本轮发生过「扇区点击」则松开触发键只关屏（clickedThisSession，实例每轮新建天然重置）。
 */
public class EmoteWheelScreen extends Screen {
    /** 轮盘上方预留：顶部目标名称（1.5 倍字号） */
    private static final int TOP_RESERVE = 42;
    /** 轮盘下方预留：页码 + 两行工具控件（26 + 18 + 3 + 18 + 余量） */
    private static final int BOTTOM_RESERVE = 72;
    /** 「编辑表情组合」按钮宽度（同时作为悬浮模式下两侧元素与轮盘的标准间距） */
    private static final int EDIT_BUTTON_WIDTH = 120;

    private final WheelMode mode;
    private int page;
    private double wheelRadius = 80;
    private boolean clickedThisSession;
    private SimpleButton styleButton;
    /** 完整轮盘：把当前正在使用的表情存为新组合（快捷轮盘没有此按钮） */
    private SimpleButton saveComboButton;
    /** 工具区（两行控件）的实际顶部位置（窗口过矮时会上提，页码据此避让） */
    private int toolsTop;

    // 开屏/关屏动画（屏幕实例每轮新建 → 状态天然重置）
    private enum Phase { OPENING, OPEN, CLOSING }

    private Phase phase = Phase.OPENING;
    private long phaseStartMs = Util.getMillis();
    private float openScale;
    private float openAlpha;
    private float closeStartA;
    private float closeStartS;
    /** 关屏动画已播完、本帧结束时关屏 */
    private boolean finishingClose;
    /** 触发键当前是否按住（关屏动画期间重新按住 → 播完后直接重开） */
    private boolean triggerHeld = true;
    private final List<FadingWidget> hudWidgets = new ArrayList<>();
    private long lastFrameMs;

    // 悬停高亮楔形（单个楔形沿最短弧滑向目标扇区；冷却期内再进入直接划过）
    private int hoveredSector = -1;
    private int lastHovered = -1;
    private float hoverPosDeg;
    private float hoverTargetDeg;
    private float hoverAlpha;
    private float redness;
    private float redTarget;
    /** 「从未有过目标」的哨兵时刻（≈ -11.5 天）：不能用 Long.MIN_VALUE——`now - 它` 会溢出成负数，冷却判断永远为真 */
    private static final long NO_TARGET_SINCE = -1_000_000_000L;
    private long noTargetSince = NO_TARGET_SINCE;

    // 指示灯动画（按扇区索引；颜色在熄灭时冻结，淡出用最后颜色）
    private final float[] indicatorAlphaArr = new float[WheelLayout.MAX_SECTORS];
    private final float[] indicatorR = new float[WheelLayout.MAX_SECTORS];
    private final float[] indicatorG = new float[WheelLayout.MAX_SECTORS];
    private final float[] indicatorB = new float[WheelLayout.MAX_SECTORS];
    /** 首帧快照：开屏时指示灯直接落在目标状态，避免与开屏淡入叠加成双重渐亮 */
    private boolean indicatorsInitialized;

    // 顶部名称 / 属性面板（与高亮冷却同步保持；穿过中心洞不闪断）
    private float titleAlpha;
    private float panelAlpha;
    /** 淡出期间沿用的最后内容（display 归 -1 后不能立刻清空，否则标题/面板会硬消失） */
    private String lastHeaderText = "";
    private WheelEntry lastPanelEntry;

    // 横版悬浮：选中标签放大强度（与冷却期同步保持）
    private final float[] labelGrowArr = new float[WheelLayout.MAX_SECTORS];

    // 翻页交叉淡化（旧页快照 ×(1-t) + 新页 ×t；含几何——每页扇区数可能不同）
    private static final long PAGE_NONE = -1_000_000_000L;
    private long pageT0 = PAGE_NONE;
    private WheelLayout prevPageLayout;
    /** 旧页快照对应的开屏缩放（用于还原名义排版基准） */
    private float prevPageScale = 1f;
    private List<WheelRenderer.Slot> prevPageSlots;
    /** 翻页后楔形直接吸附目标角度（扇区数变化时角度语义已变，滑动会晕） */
    private boolean snapWedge;

    // 预览区（每帧在 render 中更新）与拖拽状态
    private boolean previewVisible;
    /** 模型脚底锚点/当前缩放（上一帧渲染值，用于模型命中判定） */
    private int previewAnchorX;
    private int previewAnchorY;
    private float previewScale;
    private boolean draggingRotate;
    private boolean draggingPan;

    public EmoteWheelScreen(final WheelMode mode) {
        super(Component.empty());
        this.mode = mode;
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void init() {
        // 光标从轮盘中心出现（GUI 坐标 → 窗口像素坐标）
        Window window = minecraft.getWindow();
        double centerX = width / 2.0 * window.getScreenWidth() / window.getGuiScaledWidth();
        double centerY = height / 2.0 * window.getScreenHeight() / window.getGuiScaledHeight();
        InputConstants.grabOrReleaseMouse(window.getWindow(), GLFW.GLFW_CURSOR_NORMAL, centerX, centerY);

        wheelRadius = computeRadius();
        page = Mth.clamp(WheelMemory.get(memoryKey()), 0, pageCount() - 1);

        // 挂起 DS 全局表情快捷键：其监听在"非 DS 表情界面"的任意界面都会触发绑定表情
        // （例如绑定在鼠标左键上的表情会让每次点击都误触发、并干扰我们的同实例 toggle）
        DSEmoteBridge.suspendEmoteKeybinds();

        // 底部行基于名义半径定位（与文字排布模式无关，避免切换样式时控件跳动）
        int bottomY = height / 2 + (int) wheelRadius;

        // 工具区（矩形排版、元素几何空隙等宽）：
        // 第一行 [ⓘ][轮盘样式][预览锁定]；第二行 [编辑表情组合]（宽度占满第一行总宽）
        int gap = 3;
        int itemHeight = 18;

        SimpleButton info = new SimpleButton(0, 0, itemHeight, itemHeight, Component.literal("ⓘ"), () -> { });
        info.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(
                mode == WheelMode.QUICK ? "ds_quickemotes.wheel.info_tooltip_quick" : "ds_quickemotes.wheel.info_tooltip_full")));

        styleButton = new SimpleButton(0, 0, 80, itemHeight, styleLabel(QuickEmotesConfig.LABEL_STYLE.get()), this::toggleLabelStyle);
        styleButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("ds_quickemotes.wheel.style_tooltip")));

        // 预览锁定：直接读写配置项（可记忆，与配置界面同一数据源）
        SimpleToggle previewLock = new SimpleToggle(0, 0, Component.translatable("ds_quickemotes.wheel.lock_preview"), QuickEmotesConfig.PREVIEW_LOCK.get(), checked -> {
            QuickEmotesConfig.PREVIEW_LOCK.set(checked);
            QuickEmotesConfig.SPEC.save();
        });
        previewLock.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("ds_quickemotes.wheel.lock_preview_tooltip")));

        int rowWidth = info.getWidth() + gap + styleButton.getWidth() + gap + previewLock.getWidth();
        int rowX = width / 2 - rowWidth / 2;
        // 底部兜底：窗口过矮（或半径倍率设得很大）时把工具区上提到屏幕内，保证始终可点
        int toolsHeight = itemHeight + gap + itemHeight;
        int rowY = Math.max(4, Math.min(bottomY + 26, height - toolsHeight - 4));
        toolsTop = rowY;

        info.setPosition(rowX, rowY);
        rowX += info.getWidth() + gap;
        styleButton.setPosition(rowX, rowY);
        rowX += styleButton.getWidth() + gap;
        previewLock.setPosition(rowX, rowY);

        // 第二行：完整轮盘把编辑按钮对半切开，右半是「存为组合」；快捷轮盘保持整条
        boolean splitEdit = mode == WheelMode.FULL;
        int saveGap = gap;   // 与第一行控件间隙等宽
        int editWidth = splitEdit ? (rowWidth - saveGap) / 2 : rowWidth;
        int secondRowX = width / 2 - rowWidth / 2;
        int secondRowY = rowY + itemHeight + gap;
        SimpleButton editCombos = new SimpleButton(secondRowX, secondRowY, editWidth, itemHeight,
                Component.translatable("ds_quickemotes.wheel.edit_combos"), () -> minecraft.setScreen(new ComboEditorScreen()));

        addRenderableWidget(info);
        addRenderableWidget(styleButton);
        addRenderableWidget(previewLock);
        addRenderableWidget(editCombos);

        // 收集供开屏/关屏统一淡入淡出的控件（窗口 resize 会重跑 init，先清再收）
        hudWidgets.clear();
        hudWidgets.add(info);
        hudWidgets.add(styleButton);
        hudWidgets.add(previewLock);
        hudWidgets.add(editCombos);

        saveComboButton = null;
        if (splitEdit) {
            saveComboButton = new SimpleButton(secondRowX + editWidth + saveGap, secondRowY,
                    rowWidth - editWidth - saveGap, itemHeight,
                    Component.translatable("ds_quickemotes.wheel.save_combo"), this::saveActiveAsCombo);
            saveComboButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("ds_quickemotes.wheel.save_combo_tooltip")));
            addRenderableWidget(saveComboButton);
            hudWidgets.add(saveComboButton);
        }
    }

    /**
     * 把当前正在使用的表情存为一个新组合。已有内容相同的组合时不再重复创建，
     * 只把那个组合标记为本次激活（快捷轮盘的指示灯据此亮起）。
     */
    private void saveActiveAsCombo() {
        Player player = minecraft.player;
        if (player == null) {
            return;
        }

        List<DragonEmote> active = DSEmoteBridge.activeEmotes(player);
        if (active.isEmpty()) {
            return;
        }
        ResourceLocation speciesId = DSEmoteBridge.speciesIdOf(player);
        if (speciesId == null) {
            return;
        }

        List<String> keys = active.stream().map(DragonEmote::key).distinct().toList();
        String fingerprint = ComboActivation.keyOf(new EmoteCombo("", keys));
        List<EmoteCombo> existing = EmoteComboStore.getCombos(speciesId);
        for (EmoteCombo combo : existing) {
            if (ComboActivation.keyOf(combo).equals(fingerprint)) {
                ComboActivation.activate(fingerprint);
                return;
            }
        }

        // 与编辑器的新建组合同一规则：名字留空，单成员时自动显示表情名
        List<EmoteCombo> combos = new ArrayList<>(existing);
        combos.add(new EmoteCombo("", keys));
        EmoteComboStore.setCombos(speciesId, combos);
        EmoteComboStore.save(speciesId);
        ComboActivation.activate(fingerprint);
    }

    /** 直接在配置里切换文字排布方式（与配置界面同一数据源），并立即持久化 */
    private void toggleLabelStyle() {
        QuickEmotesConfig.LabelStyle next = QuickEmotesConfig.LABEL_STYLE.get() == QuickEmotesConfig.LabelStyle.RADIAL
                ? QuickEmotesConfig.LabelStyle.FLOATING
                : QuickEmotesConfig.LabelStyle.RADIAL;
        QuickEmotesConfig.LABEL_STYLE.set(next);
        QuickEmotesConfig.SPEC.save();

        if (styleButton != null) {
            styleButton.setMessage(styleLabel(next));
        }
    }

    /** 样式按钮的短标签（配置界面里仍用完整名称） */
    private static Component styleLabel(final QuickEmotesConfig.LabelStyle style) {
        return Component.translatable(style == QuickEmotesConfig.LabelStyle.FLOATING
                ? "ds_quickemotes.wheel.style_floating"
                : "ds_quickemotes.wheel.style_radial");
    }

    @Override
    public void removed() {
        // 记下关闭时的页码（Esc、应用、切屏都会经过这里）
        WheelMemory.set(memoryKey(), page);

        // 恢复被挂起的 DS 全局表情快捷键
        DSEmoteBridge.restoreEmoteKeybinds();

        // 每次关闭界面都把预览的朝向/位置恢复默认，避免模型被拖到屏幕外后找不回来
        DragonPreview.resetView();

        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------ 开屏 / 关屏动画

    private void updatePhase(final long now) {
        float elapsed = now - phaseStartMs;

        switch (phase) {
            case OPENING -> {
                float p = Mth.clamp(elapsed / WheelAnim.OPEN_MS, 0f, 1f);
                openScale = WheelAnim.easeOutCubic(p);
                openAlpha = Mth.clamp(elapsed / WheelAnim.OPEN_ALPHA_MS, 0f, 1f);
                if (p >= 1f) {
                    phase = Phase.OPEN;
                }
            }
            case OPEN -> {
                openScale = 1f;
                openAlpha = 1f;
            }
            case CLOSING -> {
                // 从当前视觉值出发（开屏中途松手也平滑），收缩 + 淡出
                float e = WheelAnim.easeOutCubic(Mth.clamp(elapsed / WheelAnim.CLOSE_MS, 0f, 1f));
                openAlpha = closeStartA * (1f - e);
                openScale = closeStartS * (1f - WheelAnim.CLOSE_SHRINK * e);

                if (elapsed >= WheelAnim.CLOSE_MS) {
                    if (triggerHeld) {
                        // 关屏动画期间又按住了触发键 → 直接重新展开
                        phase = Phase.OPENING;
                        phaseStartMs = now;
                    } else {
                        finishingClose = true;
                    }
                }
            }
        }
    }

    private void startClosing() {
        if (phase == Phase.CLOSING) {
            return;
        }
        phaseStartMs = Util.getMillis();
        closeStartA = openAlpha;
        closeStartS = openScale;
        phase = Phase.CLOSING;
    }

    /** 视觉布局：半径乘开屏进度；交互判定始终用名义布局（宽容手感） */
    private WheelLayout visualLayout() {
        boolean narrowRing = QuickEmotesConfig.LABEL_STYLE.get() == QuickEmotesConfig.LabelStyle.FLOATING;
        return new WheelLayout(wheelRadius * openScale, 1 + entriesOnPage(page), narrowRing);
    }

    private boolean isOpenish() {
        return phase != Phase.CLOSING;
    }

    /** 展示用扇区：悬停中 = 实时鼠标；冷却期内沿用最后指向项；关屏动画期间放弃 */
    private int displaySector(final long now) {
        if (!isOpenish()) {
            return -1;
        }
        if (hoveredSector >= 0) {
            return hoveredSector;
        }
        return now - noTargetSince < WheelAnim.HOVER_HOLD_MS ? lastHovered : -1;
    }

    /**
     * 逐帧动画推进：高亮楔形（滑动 / 冷却保持 / 蓝红变色）、指示灯（亮灭与变色）、标题与面板保持。
     * 位置与颜色都用帧率无关的指数平滑——任何帧率下手感一致，快速连动不排队。
     */
    private void updateAnimations(final long now, final float dt, final WheelLayout layout,
                                  final List<WheelEntry> entries, final double dx, final double dy) {
        // ---- 悬停楔形 ----
        int prevHover = hoveredSector;
        hoveredSector = isOpenish() ? layout.sectorAt(dx, dy) : -1;

        if (hoveredSector >= 0) {
            noTargetSince = NO_TARGET_SINCE;
            hoverTargetDeg = (float) (hoveredSector * layout.sectorDegrees());
            redTarget = hoveredSector == WheelLayout.CANCEL_SECTOR ? 1f : 0f;
            if (snapWedge || hoverAlpha < 0.05f) {
                // 翻页后（角度语义已变）或已熄灭时进入 → 位置与颜色都直接吸附目标：
                // 从不可见中出现没有"源颜色"可过渡，否则淡入前几帧会先闪蓝再变红；
                // 颜色渐变只留给「可见状态下滑动」（蓝色划过去才逐渐变红）
                hoverPosDeg = hoverTargetDeg;
                redness = redTarget;
                snapWedge = false;
            }
            lastHovered = hoveredSector;
        } else if (prevHover >= 0) {
            noTargetSince = now;               // 刚失去目标 → 开始冷却
        }

        // 沿最短弧滑动（边界抖动时目标原地翻转、位置几乎不动）
        float diff = Mth.wrapDegrees(hoverTargetDeg - hoverPosDeg);
        hoverPosDeg += diff * (1f - (float) Math.exp(-dt / WheelAnim.HOVER_SLIDE_TAU));
        if (Math.abs(diff) < 0.05f) {
            hoverPosDeg = hoverTargetDeg;
        }

        // 不透明度：悬停中 = 1；移出后的冷却期内保持；冷却耗尽才淡出
        float hoverTargetA = 0f;
        if (isOpenish()) {
            if (hoveredSector >= 0) {
                hoverTargetA = 1f;
            } else if (now - noTargetSince < WheelAnim.HOVER_HOLD_MS) {
                hoverTargetA = 1f;
            }
        }
        hoverAlpha = WheelAnim.smooth(hoverAlpha, hoverTargetA, WheelAnim.HOVER_FADE_TAU, dt);

        // 颜色只看目标（途中掠过取消扇区不会闪红）
        redness = WheelAnim.smooth(redness, redTarget, WheelAnim.HOVER_COLOR_TAU, dt);

        // ---- 指示灯（亮度与颜色都平滑；黄↔橙↔红自动交叉淡化）----
        for (int sector = 1; sector < layout.sectorCount(); sector++) {
            int index = sector - 1;
            WheelRenderer.IndicatorState state = index < entries.size()
                    ? indicatorState(entries.get(index)) : WheelRenderer.IndicatorState.OFF;

            if (!indicatorsInitialized) {
                indicatorAlphaArr[sector] = state == WheelRenderer.IndicatorState.OFF ? 0f : 1f;
                setIndicatorColor(sector, state);
                continue;
            }

            float visibilityBefore = indicatorAlphaArr[sector];
            indicatorAlphaArr[sector] = WheelAnim.smooth(indicatorAlphaArr[sector],
                    state == WheelRenderer.IndicatorState.OFF ? 0f : 1f, WheelAnim.INDICATOR_TAU, dt);

            if (state != WheelRenderer.IndicatorState.OFF) {
                if (visibilityBefore < 0.05f) {
                    // 从熄灭中亮起：颜色直接吸附到目标色，渐亮完全交给 alpha——
                    // 否则会从残留的黑色平滑过去，出现极轻微的暗闪
                    setIndicatorColor(sector, state);
                } else {
                    int color = indicatorRgb(state);
                    indicatorR[sector] = WheelAnim.smooth(indicatorR[sector], (color >> 16) & 0xFF, WheelAnim.INDICATOR_TAU, dt);
                    indicatorG[sector] = WheelAnim.smooth(indicatorG[sector], (color >> 8) & 0xFF, WheelAnim.INDICATOR_TAU, dt);
                    indicatorB[sector] = WheelAnim.smooth(indicatorB[sector], color & 0xFF, WheelAnim.INDICATOR_TAU, dt);
                }
            }
        }
        indicatorsInitialized = true;

        // ---- 顶部名称 / 属性面板：与高亮同步（冷却期内保持）----
        int display = displaySector(now);
        titleAlpha = WheelAnim.smooth(titleAlpha, display >= 0 ? 1f : 0f, WheelAnim.TITLE_TAU, dt);
        panelAlpha = WheelAnim.smooth(panelAlpha, display > 0 ? 1f : 0f, WheelAnim.TITLE_TAU, dt);

        // ---- 横版悬浮：选中标签放大（与冷却期同步保持）----
        for (int sector = 0; sector < WheelLayout.MAX_SECTORS; sector++) {
            labelGrowArr[sector] = WheelAnim.smooth(labelGrowArr[sector],
                    display == sector ? 1f : 0f, WheelAnim.LABEL_GROW_TAU, dt);
        }
    }

    private static int indicatorRgb(final WheelRenderer.IndicatorState state) {
        return switch (state) {
            case FULL -> WheelRenderer.COLOR_INDICATOR;
            case PARTIAL -> WheelRenderer.COLOR_INDICATOR_PARTIAL;
            case FORCED -> WheelRenderer.COLOR_INDICATOR_FORCED;
            case OFF -> 0;
        };
    }

    private void setIndicatorColor(final int sector, final WheelRenderer.IndicatorState state) {
        int color = indicatorRgb(state);
        indicatorR[sector] = (color >> 16) & 0xFF;
        indicatorG[sector] = (color >> 8) & 0xFF;
        indicatorB[sector] = color & 0xFF;
    }

    @Override
    public void renderBackground(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        // 世界保持可见：不绘制原版暗化背景
    }

    // ------------------------------------------------------------ 渲染

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        // 开屏 / 关屏动画推进；关屏动画播完的帧直接关屏（removed 负责收尾）
        long now = Util.getMillis();
        float dt = lastFrameMs == 0L ? 0f : Mth.clamp(now - lastFrameMs, 0L, (long) WheelAnim.MAX_FRAME_MS);
        lastFrameMs = now;
        updatePhase(now);
        if (finishingClose) {
            finishingClose = false;
            minecraft.setScreen(null);
            return;
        }

        // 控件随整体淡入淡出
        for (FadingWidget widget : hudWidgets) {
            widget.setAlpha(openAlpha);
        }

        Player player = minecraft.player;
        // 没有正在使用的表情时，「存为组合」置灰（完整轮盘才有此按钮）
        if (saveComboButton != null) {
            saveComboButton.active = player != null && !DSEmoteBridge.activeEmotes(player).isEmpty();
        }
        int centerX = width / 2;
        int centerY = height / 2;
        WheelLayout layout = currentLayout();
        List<WheelEntry> entries = currentEntries();

        // 悬停判定（名义几何）+ 逐帧动画推进（楔形 / 指示灯 / 标题面板）
        updateAnimations(now, dt, layout, entries, mouseX - centerX, mouseY - centerY);

        List<WheelRenderer.Slot> slots = buildSlots(layout, entries);
        int display = displaySector(now);
        WheelEntry displayEntry = display > 0 && display - 1 < entries.size() ? entries.get(display - 1) : null;

        // 左右元素几何：径向模式紧贴轮盘；悬浮模式贴屏幕边缘，给悬浮文字让出中央走廊（避免大量重叠）
        QuickEmotesConfig.LabelStyle labelStyle = QuickEmotesConfig.LABEL_STYLE.get();
        boolean floating = labelStyle == QuickEmotesConfig.LabelStyle.FLOATING;
        int outer = (int) layout.outerRadius();
        int sideHeight = Mth.clamp((int) (layout.outerRadius() * 1.5), 80, Math.max(80, height - 24));
        int sideTop = Mth.clamp(centerY - sideHeight / 2, 4, Math.max(4, height - sideHeight - 4));
        int sideElementWidth = Mth.clamp((int) (layout.outerRadius() * 1.3), 100, 140);

        int leftPanelLeft;
        int leftPanelRight;
        int rightPanelLeft;
        int rightPanelRight;

        // 两侧元素：正常时与轮盘保持标准间距（悬浮 = 一个编辑按钮宽度，径向 = 12）；
        // 窗口变窄时元素贴住窗口边框、保持完整宽度，与轮盘的间距连续收缩（可见的挤压过程），
        // 收缩到几乎贴住轮盘才隐藏
        int standardGap = floating ? EDIT_BUTTON_WIDTH : 12;
        int leftBorderMargin = 4;
        int rightBorderMargin = 8;
        int minWheelGap = 2;

        // 左侧：越出屏幕时贴左边界放置（保持完整宽度 → 右缘自然向轮盘挤压）
        leftPanelRight = centerX - outer - standardGap;
        leftPanelLeft = leftPanelRight - sideElementWidth;
        if (leftPanelLeft < leftBorderMargin) {
            leftPanelLeft = leftBorderMargin;
            leftPanelRight = leftPanelLeft + sideElementWidth;
        }

        // 右侧：同理贴右边界放置
        rightPanelLeft = centerX + outer + standardGap;
        rightPanelRight = rightPanelLeft + sideElementWidth;
        if (rightPanelRight > width - rightBorderMargin) {
            rightPanelRight = width - rightBorderMargin;
            rightPanelLeft = rightPanelRight - sideElementWidth;
        }

        boolean infoPanelSpace = leftPanelRight <= centerX - outer - minWheelGap;
        boolean showPreview = rightPanelLeft >= centerX + outer + minWheelGap && player != null;

        // 悬浮文字的横向走廊（被两侧元素夹出）；极窄屏时放弃走廊，交给文字自身截断
        int labelLeftBound = infoPanelSpace ? leftPanelRight + 6 : 4;
        int labelRightBound = showPreview ? rightPanelLeft - 6 : width - 4;
        if (labelRightBound - labelLeftBound < 60) {
            labelLeftBound = 4;
            labelRightBound = width - 4;
        }

        previewVisible = showPreview;

        // 预览龙最先渲染（自身负 z 后移）→ 完全位于所有 UI 元素背后，穿模也不会挡住 UI
        if (showPreview) {
            // 预览目标：悬停「取消所有表情」扇区 = 主动复位为站桩；
            // 冷却期内沿用最后指向项（与高亮/标题/面板同步保持）；
            // 预览锁定：勾选时鼠标移出扇区后保持最后的目标（null = 不更新）；
            // 关屏动画期间不更新目标，保持当前姿态随缩放退出
            List<DragonEmote> previewTarget;
            if (!isOpenish()) {
                previewTarget = null;
            } else if (display == WheelLayout.CANCEL_SECTOR) {
                previewTarget = List.of();
            } else if (displayEntry != null) {
                previewTarget = displayEntry.emotes();
            } else {
                previewTarget = QuickEmotesConfig.PREVIEW_LOCK.get() ? null : List.of();
            }

            if (previewTarget != null) {
                DragonPreview.setTarget(player, previewTarget);
            }

            previewAnchorX = (rightPanelLeft + rightPanelRight) / 2;
            previewAnchorY = sideTop + sideHeight - 18;
            previewScale = Mth.clamp((rightPanelRight - rightPanelLeft) * 0.4f, 20f, 45f);
            // 预览随开屏/关屏轻微缩放（实体做 alpha 淡化有渲染风险，缩放是安全的过渡手段）
            previewScale *= 0.9f + 0.1f * openAlpha;
            DragonPreview.render(graphics, player, partialTick, previewAnchorX, previewAnchorY, previewScale);
        }

        // 轮盘本体：翻页时旧页快照 ×(1-t) + 新页 ×t 交叉淡化（标签与几何统一处理，
        // 每页扇区数可能不同所以几何也要淡化）；半径随开屏动画缩放，楔形高亮由动画状态驱动
        float pageBlend = WheelAnim.easeOutCubic(Mth.clamp((now - pageT0) / WheelAnim.PAGE_MS, 0f, 1f));
        if (prevPageSlots != null && pageBlend < 0.999f) {
            // 旧页快照冻结在翻页瞬间的视觉尺寸；传入同一缩放以还原名义排版基准
            WheelRenderer.render(graphics, prevPageLayout, centerX, centerY, prevPageSlots, labelStyle,
                    labelLeftBound, labelRightBound, openAlpha * (1f - pageBlend), prevPageScale, 0f, 0f, 0f);
        }
        WheelRenderer.render(graphics, visualLayout(), centerX, centerY, slots, labelStyle,
                labelLeftBound, labelRightBound, openAlpha * pageBlend, openScale, hoverPosDeg, hoverAlpha, redness);

        // 顶部悬停名称（放大；冷却期内保持，与高亮同步淡入淡出；
        // 淡出期间沿用最后文本——随 display 归零清空会硬消失，淡出 alpha 还没走完）
        if (display >= 0 && display < slots.size()) {
            lastHeaderText = slots.get(display).label();
        }
        // 窗口过矮时标题贴顶，避免被裁出屏幕
        drawHeaderLabel(graphics, lastHeaderText, centerX, Math.max(4, centerY - outer - 25));

        // 左侧：紧凑属性面板（宽高随内容、相对圆心上下居中、程序化背景；淡出期间沿用最后条目）
        if (displayEntry != null) {
            lastPanelEntry = displayEntry;
        }
        if (infoPanelSpace) {
            renderInfoPanel(graphics, leftPanelRight, leftPanelRight - leftPanelLeft, lastPanelEntry);
        }

        // 底部第一行：页码（与工具区对齐；工具区上提时页码跟随避让，全透明帧跳过）。
        // 快捷轮盘没有组合时用引导文案代替页码（空盘不是出错）
        if (openAlpha > 0.004f) {
            int pageY = Math.max(2, Math.min(centerY + (int) wheelRadius + 12, toolsTop - 10));
            if (mode == WheelMode.QUICK && totalEntryCount() == 0) {
                graphics.drawCenteredString(font, Component.translatable("ds_quickemotes.wheel.quick_empty_hint"),
                        centerX, pageY, WheelAnim.mulAlpha(WheelStyle.TEXT_DIM, openAlpha));
            } else {
                graphics.drawCenteredString(font, (page + 1) + "/" + pageCount(), centerX, pageY,
                        WheelAnim.mulAlpha(0xFFFFFF, openAlpha));
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawHeaderLabel(final GuiGraphics graphics, final String text, final int centerX, final int topY) {
        // 完全透明时不能画：mulAlpha 会产出 alpha 位为 0 的颜色，被 MC 字体当成不透明（全亮闪现）
        float visibility = openAlpha * titleAlpha;
        if (text.isEmpty() || visibility <= 0.004f) {
            return;
        }

        float scale = 1.5f;
        int scaledWidth = (int) (font.width(text) * scale);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX - scaledWidth / 2.0, topY, 0);
        pose.scale(scale, scale, 1);
        graphics.drawString(font, text, 0, 0, WheelAnim.mulAlpha(0xFFFFFF, visibility), true);
        pose.popPose();
    }

    /** 紧凑属性面板：宽度随内容、高度随行数，相对轮盘圆心上下居中，程序化暗色背景 + 细描边 */
    private void renderInfoPanel(final GuiGraphics graphics, final int right, final int maxWidth, final WheelEntry entry) {
        float visibility = openAlpha * panelAlpha;
        if (entry == null || visibility <= 0.004f) {
            return;
        }

        List<AttrLine> lines = new ArrayList<>();
        int widest = 0;

        for (AttrLine line : attributeLines(entry)) {
            String text = line.text();
            String truncated = font.plainSubstrByWidth(text, maxWidth - 14);
            if (!truncated.equals(text)) {
                truncated += "…";
            }
            lines.add(new AttrLine(truncated, line.color()));
            widest = Math.max(widest, font.width(truncated));
        }

        int panelWidth = Mth.clamp(widest + 12, 60, maxWidth);
        int panelHeight = lines.size() * 10 + 8;
        int left = right - panelWidth;
        int top = Mth.clamp(height / 2 - panelHeight / 2, 4, Math.max(4, height - 4 - panelHeight));

        WheelStyle.panel(graphics, left, top, right, top + panelHeight,
                WheelAnim.mulAlpha(WheelStyle.FILL, visibility), WheelAnim.mulAlpha(WheelStyle.BORDER, visibility));

        int lineY = top + 5;
        for (AttrLine line : lines) {
            graphics.drawString(font, line.text(), left + 6, lineY, WheelAnim.mulAlpha(line.color(), visibility), true);
            lineY += 10;
        }
    }

    /** 属性面板的一行：文本 + 颜色（状态行用指示灯同款配色） */
    private record AttrLine(String text, int color) { }

    private List<AttrLine> attributeLines(final WheelEntry entry) {
        List<AttrLine> lines = new ArrayList<>();

        if (entry.emotes().isEmpty()) {
            // 空组合 ≠ 表情失效：成员数为 0 的完整组合提示「组合为空」
            Component text = entry.comboKey() != null && entry.declaredMembers() == 0
                    ? Component.translatable("ds_quickemotes.attr.combo_empty")
                    : Component.translatable("ds_quickemotes.attr.unavailable");
            lines.add(new AttrLine(text.getString(), WheelStyle.TEXT_DIM));
            return lines;
        }

        if (entry.comboKey() != null) {
            lines.add(new AttrLine(Component.translatable("ds_quickemotes.attr.combo_count", entry.emotes().size()).getString(), WheelStyle.TEXT));
            for (DragonEmote emote : entry.emotes()) {
                lines.add(new AttrLine("· " + emote.name().getString(), WheelStyle.TEXT));
            }
        } else {
            // 单表情属性（与编辑器悬停提示共用同一套文案）
            for (Component line : EmoteAttributeLines.of(minecraft.player, entry.emotes().get(0))) {
                lines.add(new AttrLine(line.getString(), WheelStyle.TEXT));
            }
        }

        // 尾行：使用状态（强制循环中 优先于 使用中；都不满足则不显示）
        Player player = minecraft.player;
        if (ForceLoop.isForced(entry.emotes())) {
            lines.add(new AttrLine(Component.translatable("ds_quickemotes.attr.status_forced").getString(), WheelRenderer.COLOR_INDICATOR_FORCED));
        } else if (player != null) {
            boolean inUse = entry.comboKey() != null
                    ? ComboActivation.isActive(entry.comboKey())
                    : entry.emotes().stream().anyMatch(emote -> DSEmoteBridge.isPlaying(player, emote));
            if (inUse) {
                lines.add(new AttrLine(Component.translatable("ds_quickemotes.attr.status_active").getString(), WheelRenderer.COLOR_INDICATOR));
            }
        }

        return lines;
    }

    // ------------------------------------------------------------ 输入

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (phase == Phase.CLOSING
                && (QuickEmoteKeybinds.QUICK_WHEEL.matches(keyCode, scanCode)
                || QuickEmoteKeybinds.FULL_WHEEL.matches(keyCode, scanCode))) {
            // 关屏动画期间重新按住触发键 → 动画播完后直接重开
            triggerHeld = true;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(final int keyCode, final int scanCode, final int modifiers) {
        if (QuickEmoteKeybinds.QUICK_WHEEL.matches(keyCode, scanCode)
                || QuickEmoteKeybinds.FULL_WHEEL.matches(keyCode, scanCode)) {
            triggerHeld = false;
            applyHoveredAndClose();
            return true;
        }
        if (phase == Phase.CLOSING) {
            return true;   // 关屏动画期间锁定其余输入
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
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
        if (QuickEmoteKeybinds.QUICK_WHEEL.matchesMouse(button)
                || QuickEmoteKeybinds.FULL_WHEEL.matchesMouse(button)) {
            triggerHeld = false;
            applyHoveredAndClose();
            return true;
        }
        if (phase == Phase.CLOSING) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button, final double dragX, final double dragY) {
        if (phase == Phase.CLOSING) {
            return true;
        }
        if (draggingRotate && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // 预览区内左键拖拽 = 旋转（横向自旋、纵向翻转，无死区）
            DragonPreview.addRotation((float) (-dragX / 5.0), (float) (-dragY / 5.0));
            return true;
        }
        if (draggingPan && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            // 右键拖拽 = 平移（不限制边界；滚轮保留给翻页，不做缩放）
            DragonPreview.addPan(dragX, dragY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (phase == Phase.CLOSING) {
            if (QuickEmoteKeybinds.QUICK_WHEEL.matchesMouse(button)
                    || QuickEmoteKeybinds.FULL_WHEEL.matchesMouse(button)) {
                triggerHeld = true;   // 关屏动画期间重新按住 → 播完后直接重开
            }
            return true;
        }

        // 控件（编辑按钮等）优先消费点击，避免误触扇区切换
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // 扇区点击 = 直接切换（左键=普通开关，右键=强制循环开关；编辑按钮的点击不会走到这里）。
        // 与其它交互区重叠时，扇区优先于预览拖拽。
        if ((button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                && !QuickEmoteKeybinds.QUICK_WHEEL.matchesMouse(button)
                && !QuickEmoteKeybinds.FULL_WHEEL.matchesMouse(button)) {
            WheelLayout layout = currentLayout();
            int sector = layout.sectorAt(mouseX - width / 2.0, mouseY - height / 2.0);

            if (sector >= 0) {
                applySector(sector, button == GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                clickedThisSession = true;
                // 与原版按钮一致的点击音效
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                return true;
            }
        }

        // 模型上按下：左键 = 旋转、右键 = 平移；命中区随模型平移，可拖到屏幕任意位置
        if (previewVisible
                && (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                && !QuickEmoteKeybinds.QUICK_WHEEL.matchesMouse(button)
                && !QuickEmoteKeybinds.FULL_WHEEL.matchesMouse(button)
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
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        if (phase == Phase.CLOSING) {
            return true;
        }
        if (scrollY != 0 && pageCount() > 1) {
            int next = Mth.clamp(page + (scrollY < 0 ? 1 : -1), 0, pageCount() - 1);
            if (next != page) {
                // 捕获旧页快照（几何 + 槽位动画值）用于 120ms 交叉淡化；楔形翻页后吸附目标
                prevPageLayout = visualLayout();
                prevPageScale = openScale;
                prevPageSlots = buildSlots(currentLayout(), currentEntries());
                pageT0 = Util.getMillis();
                snapWedge = true;
                page = next;
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------ 应用

    private void applyHoveredAndClose() {
        if (phase == Phase.CLOSING) {
            return;   // 关屏动画进行中，防重入（防呆已点过也不会二次触发）
        }

        if (!clickedThisSession) {
            WheelLayout layout = currentLayout();
            applySector(layout.sectorAt(mouseGuiX() - width / 2.0, mouseGuiY() - height / 2.0), false);
        }

        clickedThisSession = false; // 不影响下一轮（屏幕实例本就每轮新建）
        // 表情已在松手瞬间生效；随后播放 90ms 收起动画（期间输入锁定），播完才真正关屏
        startClosing();
    }

    private void applySector(final int sector, final boolean forceToggle) {
        Player player = minecraft.player;
        if (player == null || sector < 0) {
            return;
        }

        if (sector == WheelLayout.CANCEL_SECTOR) {
            ComboActivation.clear();
            ForceLoop.clear();
            DSEmoteBridge.stopAll(player);
            return;
        }

        List<WheelEntry> entries = currentEntries();
        int index = sector - 1;
        if (index >= entries.size()) {
            return;
        }

        WheelEntry entry = entries.get(index);
        List<DragonEmote> emotes = entry.emotes();

        // 右键：强制循环开关（强制中再右键 = 与普通取消一致，直接停止并清除）
        if (forceToggle) {
            if (ForceLoop.isForced(emotes)) {
                DSEmoteBridge.stopGroup(player, emotes);
                ForceLoop.setForced(emotes, false);
                if (entry.comboKey() != null) {
                    ComboActivation.deactivate(entry.comboKey());
                }
            } else if (ForceLoop.setForced(emotes, true)) {
                // 登记成功才启动：按 DS 槽位预算（全局 ≤4）与非 blend 互斥规则校验，不满足则按不动
                DSEmoteBridge.startGroup(player, emotes);
                if (entry.comboKey() != null) {
                    ComboActivation.activate(entry.comboKey());
                }
            }
            return;
        }

        if (entry.comboKey() == null) {
            // 完整轮盘单表情：按「是否在播」切换；手动操作即放弃相关强制
            DSEmoteBridge.toggleGroup(player, emotes);
            for (DragonEmote emote : emotes) {
                ForceLoop.removeContaining(emote);
            }
            return;
        }

        // 快捷轮盘组合：按「激活状态」切换（与指示灯语义一致）；手动切换即清除强制
        if (ComboActivation.isActive(entry.comboKey())) {
            DSEmoteBridge.stopGroup(player, emotes);
            ComboActivation.deactivate(entry.comboKey());
        } else {
            DSEmoteBridge.startGroup(player, emotes);
            ComboActivation.activate(entry.comboKey());
        }
        ForceLoop.setForced(emotes, false);
    }

    private double mouseGuiX() {
        Window window = minecraft.getWindow();
        return minecraft.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
    }

    private double mouseGuiY() {
        Window window = minecraft.getWindow();
        return minecraft.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();
    }

    // ------------------------------------------------------------ 数据

    private WheelLayout currentLayout() {
        boolean narrowRing = QuickEmotesConfig.LABEL_STYLE.get() == QuickEmotesConfig.LabelStyle.FLOATING;
        return new WheelLayout(wheelRadius, 1 + entriesOnPage(page), narrowRing);
    }

    private double computeRadius() {
        // 高度优先：为顶部名称与底部页码/工具控件预留固定空间（窗口矮时轮盘主动缩小），
        // 宽度只做兜底（横屏窗口一般足够放下左右元素）
        double heightLimited = (height - TOP_RESERVE - BOTTOM_RESERVE) / 2.0;
        double widthLimited = width / 2.0 - 12.0;
        double base = Math.min(heightLimited, widthLimited);
        return Mth.clamp(base * QuickEmotesConfig.WHEEL_SIZE_MULTIPLIER.get(), 40, (double) QuickEmotesConfig.MAX_WHEEL_RADIUS.get());
    }

    /**
     * 当前页的一个可选条目（不含固定的取消扇区）：FULL = 单表情；QUICK = 组合（已解析表情实例）。
     * declaredMembers = 组合声明的成员数（含解析不到的失效 key），用于区分「空组合」与「成员全部失效」。
     */
    private record WheelEntry(String label, List<DragonEmote> emotes, String comboKey, int declaredMembers) { }

    /** 当前页条目；QUICK 模式的组合在此对照当前身体表情集解析，失效项忽略 */
    private List<WheelEntry> currentEntries() {
        Player player = minecraft.player;
        if (player == null || !DSEmoteBridge.isDragon(player)) {
            return List.of();
        }

        int pageSize = pageSize();
        int from = page * pageSize;
        List<WheelEntry> entries = new ArrayList<>();

        if (mode == WheelMode.FULL) {
            List<DragonEmote> emotes = DSEmoteBridge.emotesOf(player);
            int to = Math.min(from + pageSize, emotes.size());

            for (int i = from; i < to; i++) {
                DragonEmote emote = emotes.get(i);
                entries.add(new WheelEntry(emote.name().getString(), List.of(emote), null, 1));
            }
            return entries;
        }

        ResourceLocation speciesId = DSEmoteBridge.speciesIdOf(player);
        if (speciesId == null) {
            return entries;
        }

        List<EmoteCombo> combos = EmoteComboStore.getCombos(speciesId);
        List<DragonEmote> bodyEmotes = DSEmoteBridge.emotesOf(player);
        int to = Math.min(from + pageSize, combos.size());

        for (int i = from; i < to; i++) {
            EmoteCombo combo = combos.get(i);
            List<DragonEmote> resolved = new ArrayList<>();

            for (String key : combo.emoteKeys()) {
                DragonEmote emote = DSEmoteBridge.findEmote(bodyEmotes, key);
                if (emote != null && !resolved.contains(emote)) {
                    resolved.add(emote);
                }
            }

            entries.add(new WheelEntry(entryLabel(combo, resolved), List.copyOf(resolved),
                    ComboActivation.keyOf(combo), combo.emoteKeys().size()));
        }

        return entries;
    }

    private static String entryLabel(final EmoteCombo combo, final List<DragonEmote> resolved) {
        if (!combo.name().isBlank()) {
            return combo.name();
        }
        if (resolved.size() == 1) {
            return resolved.get(0).name().getString();
        }
        return Component.translatable("ds_quickemotes.combo.default_name").getString();
    }

    /** 由动画状态构建渲染槽位（指示灯颜色/强度/标签放大已由 updateAnimations 逐帧平滑） */
    private List<WheelRenderer.Slot> buildSlots(final WheelLayout layout, final List<WheelEntry> entries) {
        List<WheelRenderer.Slot> slots = new ArrayList<>(layout.sectorCount());
        slots.add(new WheelRenderer.Slot(Component.translatable("ds_quickemotes.wheel.cancel_all").getString(),
                0, 0f, labelGrowArr[WheelLayout.CANCEL_SECTOR]));

        for (int sector = 1; sector < layout.sectorCount(); sector++) {
            int index = sector - 1;

            if (index >= entries.size()) {
                slots.add(new WheelRenderer.Slot("", 0, 0f, 0f));
                continue;
            }

            float intensity = indicatorAlphaArr[sector];
            int color = (Mth.clamp((int) (intensity * 255f), 0, 255) << 24)
                    | ((int) indicatorR[sector] << 16)
                    | ((int) indicatorG[sector] << 8)
                    | (int) indicatorB[sector];
            slots.add(new WheelRenderer.Slot(entries.get(index).label(), color, intensity, labelGrowArr[sector]));
        }

        return slots;
    }

    /** 指示灯状态：强制循环=红；组合=「由轮盘启动过」才亮；单表情=全部在播才亮（四态见 IndicatorState） */
    private WheelRenderer.IndicatorState indicatorState(final WheelEntry entry) {
        Player player = minecraft.player;
        if (player == null || entry.emotes().isEmpty()) {
            return WheelRenderer.IndicatorState.OFF;
        }

        if (ForceLoop.isForced(entry.emotes())) {
            return WheelRenderer.IndicatorState.FORCED;
        }

        int playing = 0;
        for (DragonEmote emote : entry.emotes()) {
            if (DSEmoteBridge.isPlaying(player, emote)) {
                playing++;
            }
        }

        if (entry.comboKey() != null) {
            if (!ComboActivation.isActive(entry.comboKey())) {
                return WheelRenderer.IndicatorState.OFF;
            }
            if (playing == 0) {
                ComboActivation.deactivate(entry.comboKey()); // 成员全部退出 → 自动取消激活
                return WheelRenderer.IndicatorState.OFF;
            }
        } else if (playing == 0) {
            return WheelRenderer.IndicatorState.OFF;
        }

        return playing == entry.emotes().size() ? WheelRenderer.IndicatorState.FULL : WheelRenderer.IndicatorState.PARTIAL;
    }

    private int pageSize() {
        return QuickEmotesConfig.PAGE_SIZE.get();
    }

    private int pageCount() {
        return Math.max(1, (int) Math.ceil(totalEntryCount() / (double) pageSize()));
    }

    private int entriesOnPage(final int targetPage) {
        return Mth.clamp(totalEntryCount() - targetPage * pageSize(), 0, pageSize());
    }

    private int totalEntryCount() {
        Player player = minecraft.player;
        if (player == null || !DSEmoteBridge.isDragon(player)) {
            return 0;
        }

        if (mode == WheelMode.FULL) {
            return DSEmoteBridge.emotesOf(player).size();
        }

        ResourceLocation speciesId = DSEmoteBridge.speciesIdOf(player);
        return speciesId != null ? EmoteComboStore.getCombos(speciesId).size() : 0;
    }

    private String memoryKey() {
        Player player = minecraft.player;
        ResourceLocation body = player != null ? DSEmoteBridge.bodyIdOf(player) : null;
        return mode.name() + "|" + (body != null ? body : "none");
    }
}
