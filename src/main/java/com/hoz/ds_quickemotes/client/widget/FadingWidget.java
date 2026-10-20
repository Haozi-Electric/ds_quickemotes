package com.hoz.ds_quickemotes.client.widget;

import com.hoz.ds_quickemotes.client.anim.WheelAnim;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** 支持整体透明度淡入淡出的控件基类（开屏/关屏动画期间由屏幕统一驱动）。 */
public abstract class FadingWidget extends AbstractWidget {
    private float alpha = 1f;
    private long lastFrameMs;

    protected FadingWidget(final int x, final int y, final int width, final int height, final Component message) {
        super(x, y, width, height, message);
    }

    public void setAlpha(final float alpha) {
        this.alpha = Mth.clamp(alpha, 0f, 1f);
    }

    protected final float alpha() {
        return alpha;
    }

    /** 本帧间隔（ms，clamped）——供控件内部 hover 平滑使用，每个 renderFading 里只调用一次 */
    protected final float frameDelta() {
        long now = Util.getMillis();
        float dt = lastFrameMs == 0L ? 0f : Mth.clamp(now - lastFrameMs, 0L, (long) WheelAnim.MAX_FRAME_MS);
        lastFrameMs = now;
        return dt;
    }

    @Override
    protected void renderWidget(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        if (alpha <= 0.004f) {
            return;
        }
        renderFading(graphics, mouseX, mouseY, partialTick);
    }

    protected abstract void renderFading(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);
}
