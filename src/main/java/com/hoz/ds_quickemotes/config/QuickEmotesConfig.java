package com.hoz.ds_quickemotes.config;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.TranslatableEnum;

public final class QuickEmotesConfig {
    public enum LabelStyle implements TranslatableEnum {
        /** 文字沿扇区径向书写（默认） */
        RADIAL,
        /** 横版悬浮：标签水平浮在轮盘外侧（取消扇区仍用径向文字） */
        FLOATING;

        @Override
        public Component getTranslatedName() {
            return Component.translatable("ds_quickemotes.configuration.labelStyle." + name());
        }
    }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue MAX_WHEEL_RADIUS;
    public static final ModConfigSpec.DoubleValue WHEEL_SIZE_MULTIPLIER;
    public static final ModConfigSpec.IntValue PAGE_SIZE;
    public static final ModConfigSpec.EnumValue<LabelStyle> LABEL_STYLE;
    public static final ModConfigSpec.BooleanValue PREVIEW_LOCK;
    /** 组合编辑器预览锁定（与轮盘的 PREVIEW_LOCK 独立） */
    public static final ModConfigSpec.BooleanValue EDITOR_PREVIEW_LOCK;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Wheel layout").translation("ds_quickemotes.configuration.wheel").push("wheel");
        MAX_WHEEL_RADIUS = builder
                .comment("Maximum wheel radius (GUI pixels)")
                .translation("ds_quickemotes.configuration.wheel.maxRadius")
                .defineInRange("maxRadius", 100, 48, 160);
        WHEEL_SIZE_MULTIPLIER = builder
                .comment("Size multiplier: actual radius = min(usable half-screen space × multiplier, max radius)")
                .translation("ds_quickemotes.configuration.wheel.sizeMultiplier")
                .defineInRange("sizeMultiplier", 1.0, 0.5, 1.5);
        PAGE_SIZE = builder
                .comment("Emotes per page (1~9), excluding the fixed \"Cancel all emotes\" sector")
                .translation("ds_quickemotes.configuration.wheel.pageSize")
                .defineInRange("pageSize", 9, 1, 9);
        LABEL_STYLE = builder
                .comment("Label layout: RADIAL = along sectors; FLOATING = horizontal labels outside the wheel")
                .translation("ds_quickemotes.configuration.wheel.labelStyle")
                .defineEnum("labelStyle", LabelStyle.RADIAL);
        PREVIEW_LOCK = builder
                .comment("Preview lock: keep the last previewed emote after the cursor leaves the sector "
                        + "(hover \"Cancel all emotes\" to reset)")
                .translation("ds_quickemotes.configuration.wheel.previewLock")
                .define("previewLock", false);
        builder.pop();

        builder.comment("Combo editor").translation("ds_quickemotes.configuration.editor").push("editor");
        EDITOR_PREVIEW_LOCK = builder
                .comment("Editor preview lock: keep the last preview after the mouse leaves an emote/combo")
                .translation("ds_quickemotes.configuration.editor.previewLock")
                .define("previewLock", false);
        builder.pop();

        SPEC = builder.build();
    }

    private QuickEmotesConfig() { }
}
