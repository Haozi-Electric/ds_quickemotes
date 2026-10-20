package com.hoz.ds_quickemotes.client.widget;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 轮盘 UI 的程序化简约样式（属性面板 / 按钮 / 勾选项共用同一套配色与描边规则）。
 */
public final class WheelStyle {
    /** 面板与控件的基础填充（半透明暗色） */
    public static final int FILL = 0x98101010;
    /** 悬停填充（略亮） */
    public static final int FILL_HOVER = 0xA01A222C;

    public static final int BORDER = 0x30FFFFFF;

    public static final int TEXT = 0xFFFFFF;
    public static final int TEXT_DIM = 0xD0D0D0;
    public static final int ACCENT = 0xFF00B2FF;

    private WheelStyle() { }

    /** 简约面板：单色填充 + 1px 描边 */
    public static void panel(final GuiGraphicsExtractor graphics, final int left, final int top, final int right, final int bottom,
                             final int fill, final int border) {
        graphics.fill(left, top, right, bottom, fill);
        graphics.fill(left, top, right, top + 1, border);
        graphics.fill(left, bottom - 1, right, bottom, border);
        graphics.fill(left, top + 1, left + 1, bottom - 1, border);
        graphics.fill(right - 1, top + 1, right, bottom - 1, border);
    }
}
