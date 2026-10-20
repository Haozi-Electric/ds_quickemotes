package com.hoz.ds_quickemotes.client;

import com.hoz.ds_quickemotes.bridge.DSEmoteBridge;
import com.hoz.ds_quickemotes.client.screen.EmoteWheelScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(Dist.CLIENT)
public final class WheelInputHandler {
    private WheelInputHandler() { }

    @SubscribeEvent
    public static void onKey(final InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS || !canOpen()) {
            return;
        }
        if (QuickEmoteKeybinds.QUICK_WHEEL.matches(event.getKey(), event.getScanCode())) {
            openByModifier(false);
        } else if (QuickEmoteKeybinds.FULL_WHEEL.matches(event.getKey(), event.getScanCode())) {
            openByModifier(true);
        }
    }

    @SubscribeEvent
    public static void onMouse(final InputEvent.MouseButton.Pre event) {
        if (event.getAction() != GLFW.GLFW_PRESS || !canOpen()) {
            return;
        }
        if (QuickEmoteKeybinds.QUICK_WHEEL.matchesMouse(event.getButton())) {
            openByModifier(false);
        } else if (QuickEmoteKeybinds.FULL_WHEEL.matchesMouse(event.getButton())) {
            openByModifier(true);
        }
    }

    /** 修饰键 + 快捷轮盘键 = 完整轮盘；否则按该键自身的轮盘类型 */
    private static void openByModifier(final boolean fullWheelKey) {
        open(fullWheelKey || modifierHeld() ? WheelMode.FULL : WheelMode.QUICK);
    }

    /** 直接查询物理按键状态（不用 KeyMapping.isDown，避免受冲突上下文影响） */
    private static boolean modifierHeld() {
        InputConstants.Key key = QuickEmoteKeybinds.MODIFIER.getKey();
        long window = Minecraft.getInstance().getWindow().getWindow();

        return switch (key.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(window, key.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
            default -> false;
        };
    }

    private static boolean canOpen() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        return minecraft.screen == null
                && minecraft.level != null
                && player != null
                && !player.isSpectator()
                && DSEmoteBridge.isDragon(player);
    }

    private static void open(final WheelMode mode) {
        Minecraft.getInstance().setScreen(new EmoteWheelScreen(mode));
    }
}
