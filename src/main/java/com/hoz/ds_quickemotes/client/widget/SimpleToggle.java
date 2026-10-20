package com.hoz.ds_quickemotes.client.widget;

import com.hoz.ds_quickemotes.client.anim.WheelAnim;
import com.hoz.ds_quickemotes.client.wheel.WheelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FastColor;

import java.util.function.Consumer;

/**
 * 开关按钮（勾选项的替代样式）：与径向模式扇区一致——
 * 暗色纯色底；激活后背景更深，且左侧边一条细黄线（指示灯）渐亮；取消即渐灭。
 */
public class SimpleToggle extends FadingWidget {
    private boolean checked;
    private final Consumer<Boolean> onToggle;
    /** 悬停强度 0~1（指数平滑） */
    private float hoverA;
    /** 激活强度 0~1（背景变暗与黄灯渐亮渐灭） */
    private float onA;

    public SimpleToggle(final int x, final int y, final Component label, final boolean initial, final Consumer<Boolean> onToggle) {
        this(x, y, Minecraft.getInstance().font.width(label) + 18, label, initial, onToggle);
    }

    /** 固定宽度版本（编辑器里与输入框拼成一行） */
    public SimpleToggle(final int x, final int y, final int width, final Component label, final boolean initial, final Consumer<Boolean> onToggle) {
        super(x, y, width, 18, label);
        this.checked = initial;
        this.onToggle = onToggle;
    }

    @Override
    public void onClick(final double mouseX, final double mouseY) {
        checked = !checked;
        onToggle.accept(checked);
    }

    @Override
    public void playDownSound(final net.minecraft.client.sounds.SoundManager handler) {
        handler.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    protected void renderFading(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        float dt = frameDelta();
        hoverA = WheelAnim.smooth(hoverA, isHovered() ? 1f : 0f, WheelAnim.BUTTON_TAU, dt);
        onA = WheelAnim.smooth(onA, checked ? 1f : 0f, WheelAnim.BUTTON_TAU, dt);

        // 与径向模式扇区同款纯色底：激活后平滑变暗
        int background = FastColor.ARGB32.lerp(onA, WheelRenderer.COLOR_BACKGROUND, WheelRenderer.COLOR_BACKGROUND_ACTIVE);
        graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), WheelAnim.mulAlpha(background, alpha()));

        // 左侧细指示灯（亮黄），激活时渐亮
        if (onA > 0.004f) {
            graphics.fill(getX(), getY(), getX() + 2, getY() + getHeight(),
                    WheelAnim.mulAlpha(WheelRenderer.COLOR_INDICATOR, alpha() * onA));
        }

        Font font = Minecraft.getInstance().font;
        // 文字色插值用确定性不透明变体（WheelStyle.TEXT 的 alpha 位为 0，直接 lerp 会意外漂移）
        int color = FastColor.ARGB32.lerp(Math.max(hoverA, onA), 0xFFD0D0D0, 0xFFFFFFFF);
        graphics.drawString(font, getMessage(), getX() + 8, getY() + (getHeight() - 8) / 2,
                WheelAnim.mulAlpha(color, alpha()), true);
    }

    @Override
    protected void updateWidgetNarration(final NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
