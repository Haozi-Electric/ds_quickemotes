package com.hoz.ds_quickemotes.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public final class QuickEmoteKeybinds {
    public static final String CATEGORY = "key.categories.ds_quickemotes";

    /** 按住 = 快捷轮盘；按住修饰键 + 此键 = 完整轮盘 */
    public static final KeyMapping QUICK_WHEEL = new KeyMapping(
            "key.ds_quickemotes.quick_wheel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            CATEGORY
    );

    /** 独立的完整轮盘键 */
    public static final KeyMapping FULL_WHEEL = new KeyMapping(
            "key.ds_quickemotes.full_wheel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            CATEGORY
    );

    /** 轮盘修饰键：按住它再按快捷轮盘键 = 完整轮盘 */
    public static final KeyMapping MODIFIER = new KeyMapping(
            "key.ds_quickemotes.modifier",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_CONTROL,
            CATEGORY
    );

    private QuickEmoteKeybinds() { }

    public static void register(final RegisterKeyMappingsEvent event) {
        event.register(QUICK_WHEEL);
        event.register(FULL_WHEEL);
        event.register(MODIFIER);
    }
}
