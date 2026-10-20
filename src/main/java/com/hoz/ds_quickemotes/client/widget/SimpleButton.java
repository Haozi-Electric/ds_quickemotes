package com.hoz.ds_quickemotes.client.widget;

import com.hoz.ds_quickemotes.client.anim.WheelAnim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FastColor;

/**
 * 程序生成的简约样式按钮（替代原版按钮贴图）：暗色半透明底 + 细描边，悬停高亮（平滑过渡）。
 * 点击音效与原版按钮一致（UI_BUTTON_CLICK）。
 */
public class SimpleButton extends FadingWidget {
    private final Runnable onPress;
    /** 悬停强度 0~1（指数平滑） */
    private float hoverA;

    public SimpleButton(final int x, final int y, final int width, final int height, final Component message, final Runnable onPress) {
        super(x, y, width, height, message);
        this.onPress = onPress;
    }

    public SimpleButton(final Component message, final Runnable onPress) {
        this(0, 0, Minecraft.getInstance().font.width(message) + 24, 16, message, onPress);
    }

    @Override
    public void onClick(final double mouseX, final double mouseY) {
        onPress.run();
    }

    @Override
    public void playDownSound(final net.minecraft.client.sounds.SoundManager handler) {
        handler.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    protected void renderFading(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        // 不用 isHoveredOrFocused：点击后焦点残留会让高亮不消退（isHovered 只随鼠标）
        hoverA = WheelAnim.smooth(hoverA, isHovered() ? 1f : 0f, WheelAnim.BUTTON_TAU, frameDelta());

        // 无边框纯色底（与径向模式扇区同款暗色几何），悬停色平滑过渡
        int fill = active ? FastColor.ARGB32.lerp(hoverA, WheelStyle.FILL, WheelStyle.FILL_HOVER) : WheelStyle.FILL;
        graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), WheelAnim.mulAlpha(fill, alpha()));

        Font font = Minecraft.getInstance().font;
        // 文字色插值用确定性不透明变体（WheelStyle.TEXT 的 alpha 位为 0，直接 lerp 会意外漂移）
        int color = active ? FastColor.ARGB32.lerp(hoverA, 0xFFD0D0D0, 0xFFFFFFFF) : 0x808080;

        // 原版超框滚动：文字超宽时来回滚动展示
        renderScrollingString(graphics, font, 4, WheelAnim.mulAlpha(color, alpha()));
    }

    @Override
    protected void updateWidgetNarration(final NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
