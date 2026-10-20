package com.hoz.ds_quickemotes.client.util;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.body.emotes.DragonEmote;
import com.hoz.ds_quickemotes.bridge.DSEmoteBridge;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 单表情属性行（轮盘属性面板与编辑器悬停提示共用；不含「使用中 / 强制循环中」状态行）。
 */
public final class EmoteAttributeLines {
    public static List<Component> of(final Player player, final DragonEmote emote) {
        List<Component> lines = new ArrayList<>();

        lines.add(attr("loops", yesNo(emote.loops())));

        // 时长：表情未显式配置时按动画实际长度解析（tick → 秒）。
        // 静态姿势类动画（无 animation_length 且关键帧只在 0 点）会被 GeckoLib 标成哨兵值 → 显示「持续」
        double durationTicks = emote.duration();
        if (durationTicks == DragonEmote.NO_DURATION && player != null) {
            durationTicks = DSEmoteBridge.animationLengthTicks(player, emote);
        }
        Component duration;
        if (durationTicks > 1_000_000) {
            duration = Component.translatable("ds_quickemotes.attr.duration_endless");
        } else if (durationTicks > 0) {
            duration = Component.translatable("ds_quickemotes.attr.seconds",
                    Component.literal(String.format("%.1f", durationTicks / 20.0)));
        } else {
            duration = Component.translatable("ds_quickemotes.attr.duration_animation");
        }
        lines.add(attr("duration", duration));

        if (emote.speed() != DragonEmote.DEFAULT_SPEED) {
            lines.add(attr("speed", Component.literal("×" + emote.speed())));
        }

        lines.add(attr("blend", yesNo(emote.blend())));
        lines.add(attr("can_move", yesNo(emote.canMove())));
        lines.add(attr("sound", yesNo(emote.sound().isPresent())));

        if (emote.locksHead()) {
            lines.add(Component.translatable("ds_quickemotes.attr.locks_head"));
        }
        if (emote.locksTail()) {
            lines.add(Component.translatable("ds_quickemotes.attr.locks_tail"));
        }
        if (emote.thirdPerson()) {
            lines.add(Component.translatable("ds_quickemotes.attr.third_person"));
        }

        return lines;
    }

    private static Component attr(final String key, final Component value) {
        return Component.translatable("ds_quickemotes.attr." + key, value);
    }

    private static Component yesNo(final boolean value) {
        return Component.translatable(value ? "ds_quickemotes.attr.yes" : "ds_quickemotes.attr.no");
    }

    private EmoteAttributeLines() { }
}
