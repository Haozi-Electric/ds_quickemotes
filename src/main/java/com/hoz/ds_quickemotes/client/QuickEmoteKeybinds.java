package com.hoz.ds_quickemotes.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public final class QuickEmoteKeybinds {
    public static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath("ds_quickemotes", "main"));

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
        event.registerCategory(CATEGORY);
        event.register(QUICK_WHEEL);
        event.register(FULL_WHEEL);
        event.register(MODIFIER);
    }

    /** 鼠标按键是否绑定到该映射（输入事件里只有 MouseButtonInfo，没有完整坐标） */
    public static boolean matchesMouseButton(final KeyMapping mapping, final int button) {
        return mapping.matchesMouse(new MouseButtonEvent(0, 0,
                new net.minecraft.client.input.MouseButtonInfo(button, 0)));
    }
}
