package com.hoz.ds_quickemotes.client.widget;

import com.hoz.ds_quickemotes.client.anim.WheelAnim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ARGB;

import java.util.List;

/**
 * 程序化列表行（组合列表 / 成员 / 表情库共用）：
 * 悬停底色平滑过渡、选中时左侧亮起蓝色指示条、整体透明度由所在屏幕统一驱动。
 * 供滚动列表使用：不是 renderable widget，由屏幕在 scissor 内手动 render。
 */
public class SimpleRow extends FadingWidget {
    private final Runnable onPress;
    private boolean selected;
    private float hoverA;
    /** 文字颜色覆盖（0 = 默认自适应；失效/冲突项用红色） */
    private int textColorOverride;
    /** 悬停时在行尾显示的驱赶方向箭头（如 "→"），null = 不显示 */
    private String hoverArrow;
    /** 悬停提示行（禁用原因 / 失效说明 / 属性列表），由屏幕在鼠标位置渲染原版硬跟随 tooltip */
    private List<Component> hoverHint;

    public SimpleRow(final int x, final int y, final int width, final int height, final Component message, final Runnable onPress) {
        super(x, y, width, height, message);
        this.onPress = onPress;
    }

    public void setSelected(final boolean selected) {
        this.selected = selected;
    }

    public void setTextColorOverride(final int textColorOverride) {
        this.textColorOverride = textColorOverride;
    }

    public void setHoverArrow(final String hoverArrow) {
        this.hoverArrow = hoverArrow;
    }

    public void setHoverHint(final Component hoverHint) {
        this.hoverHint = hoverHint == null ? null : List.of(hoverHint);
    }

    /** 多行提示：每个元素一行（MC tooltip 不解析字符串里的换行符） */
    public void setHoverLines(final List<Component> hoverLines) {
        this.hoverHint = hoverLines == null || hoverLines.isEmpty() ? null : List.copyOf(hoverLines);
    }

    public List<Component> getHoverHint() {
        return hoverHint;
    }

    @Override
    public void onClick(final MouseButtonEvent event, final boolean doubleClick) {
        onPress.run();
    }

    @Override
    public void playDownSound(final net.minecraft.client.sounds.SoundManager handler) {
        handler.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    protected void renderFading(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
        // 不可用行不高亮（已加入 / 违反规则）
        hoverA = WheelAnim.smooth(hoverA, (active && isHovered()) ? 1f : 0f, WheelAnim.BUTTON_TAU, frameDelta());

        int fill = selected ? WheelStyle.FILL_HOVER : ARGB.srgbLerp(hoverA, WheelStyle.FILL, WheelStyle.FILL_HOVER);
        graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), WheelAnim.mulAlpha(fill, alpha()));

        if (selected) {
            graphics.fill(getX(), getY(), getX() + 2, getY() + getHeight(), WheelAnim.mulAlpha(WheelStyle.ACCENT, alpha()));
        }

        Font font = Minecraft.getInstance().font;
        String text = getMessage().getString();
        if (font.width(text) > getWidth() - 12) {
            text = font.plainSubstrByWidth(text, getWidth() - 16) + "…";
        }
        int color;
        if (textColorOverride != 0) {
            color = textColorOverride;
        } else if (!active) {
            color = 0xFF70757C;
        } else {
            color = ARGB.srgbLerp(Math.max(hoverA, selected ? 1f : 0f), 0xFFD0D0D0, 0xFFFFFFFF);
        }
        graphics.text(font, text, getX() + 8, getY() + (getHeight() - 8) / 2,
                WheelAnim.mulAlpha(color, alpha()), false);

        // 悬停时的右对齐驱赶箭头（中栏 → 回右栏；右栏 ← 进中栏）
        if (active && hoverArrow != null && hoverA > 0.05f) {
            graphics.text(font, hoverArrow, getX() + getWidth() - 14, getY() + (getHeight() - 8) / 2,
                    WheelAnim.mulAlpha(0xFFFFFFFF, alpha() * hoverA), false);
        }
    }

    @Override
    protected void updateWidgetNarration(final NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
