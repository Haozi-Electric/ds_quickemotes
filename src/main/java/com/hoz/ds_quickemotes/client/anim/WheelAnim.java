package com.hoz.ds_quickemotes.client.anim;

import net.minecraft.util.Mth;

/** 动效参数与插值工具：全部时长/τ 集中在此。 */
public final class WheelAnim {
    /** 开屏：半径 0→1（easeOutCubic） */
    public static final float OPEN_MS = 150f;
    /** 开屏：整体 alpha 到位时间 */
    public static final float OPEN_ALPHA_MS = 80f;
    /** 关屏：淡出 + 收缩 */
    public static final float CLOSE_MS = 90f;
    /** 关屏：半径收缩比例（1 → 0.92） */
    public static final float CLOSE_SHRINK = 0.08f;
    /** 帧间隔上限：切后台回来插值不跳变 */
    public static final float MAX_FRAME_MS = 100f;

    // ---- 指数平滑 τ（ms）----
    /** 悬停高亮：楔形滑向目标扇区 */
    public static final float HOVER_SLIDE_TAU = 50f;
    /** 移出轮盘后的冷却期：期间高亮保持，冷却内再进入直接滑过去 */
    public static final float HOVER_HOLD_MS = 250f;
    /** 高亮淡入 / 冷却耗尽后的淡出 */
    public static final float HOVER_FADE_TAU = 60f;
    /** 高亮蓝↔红平滑变色 */
    public static final float HOVER_COLOR_TAU = 70f;
    /** 指示灯亮灭与黄/橙/红变色 */
    public static final float INDICATOR_TAU = 60f;
    /** 顶部名称 / 属性面板 淡入淡出（与高亮冷却同步保持） */
    public static final float TITLE_TAU = 45f;
    /** 按钮 / 开关 hover */
    public static final float BUTTON_TAU = 40f;
    /** 横版悬浮：选中标签放大 / 缩回过渡 */
    public static final float LABEL_GROW_TAU = 60f;

    // ---- 一次性过渡（ms）----
    /** 翻页：新旧内容交叉淡化 */
    public static final float PAGE_MS = 120f;
    /** 横版悬浮：选中标签放大比例（1 + 0.15 × 强度） */
    public static final float LABEL_GROW = 0.15f;

    public static float easeOutCubic(final float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** 帧率无关的指数平滑 */
    public static float smooth(final float current, final float target, final float tauMs, final float dtMs) {
        return current + (target - current) * (1f - (float) Math.exp(-dtMs / tauMs));
    }

    /**
     * ARGB 的 alpha 通道乘以 mul；按 MC 约定 alpha 位为 0 视为不透明（如 0xFFFFFF 白、0x808080 灰）。
     * 注意：MC 字体渲染还会把 alpha 1~3 也当作不透明（Font.adjustColor 掩码 0xFC000000）——
     * 淡出尾段若出现这些值，文字会突然闪回全亮。因此非零结果统一钳到最小 4（≈1.6%，肉眼不可见）。
     */
    public static int mulAlpha(final int argb, final float mul) {
        int alpha = argb >>> 24;
        if (alpha == 0) {
            alpha = 0xFF;
        }
        int scaled = Mth.clamp((int) (alpha * mul), 0, 255);
        if (scaled > 0 && scaled < 4) {
            scaled = 4;
        }
        return (scaled << 24) | (argb & 0xFFFFFF);
    }

    private WheelAnim() { }
}
