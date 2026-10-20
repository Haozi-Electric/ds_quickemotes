package com.hoz.ds_quickemotes.combo;

import by.dragonsurvivalteam.dragonsurvival.common.entity.DragonEntity;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.body.emotes.DragonEmote;
import com.hoz.ds_quickemotes.bridge.DSEmoteBridge;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 强制循环（右键扇区）：
 * - 成员一次性播完（自然结束）→ 自动补齐/续上；
 * - 冲突判定沿用 DS 原生互斥规则（非 blend 才会踢人）：
 *   目标含非 blend 且出现不属于目标的非 blend 在播 → 视为被冲突踢出 → 直接取消强制（让步方），不动播放状态；
 * - loops=true 的表情可标记（红灯），但成员永远在播、tick 不会触发续播；
 * - 强制状态下左右键点击该目标 = 与普通一样直接停止并清除（由屏幕层处理）；
 * - 死亡 / 切换维度 / 取消全部 → clear()。
 */
public final class ForceLoop {
    /** DS 的表情槽上限（DragonEntity.MAX_EMOTES） */
    private static final int MAX_EMOTES = 4;

    private static final Map<String, List<DragonEmote>> TARGETS = new HashMap<>();

    private ForceLoop() { }

    /**
     * 登记/清除强制目标。
     * 登记时按 DS 原版规则做双重校验，不通过则拒绝（按不动）：
     * - 本目标：成员 ≤ 4（DS 仅 4 个表情槽）且非 blend（互斥层）至多 1 个；
     * - 全局预算：与现有全部强制目标的成员合计 ≤ 4（防止"逐个强制单表情"把总量叠加超限）。
     *
     * @return 是否被接受
     */
    public static boolean setForced(final List<DragonEmote> emotes, final boolean forced) {
        if (emotes.isEmpty()) {
            return false;
        }

        if (!forced) {
            TARGETS.remove(keyOf(emotes));
            return true;
        }

        String key = keyOf(emotes);

        if (!isForceable(emotes)) {
            return false;
        }

        int otherMembers = 0;
        for (Map.Entry<String, List<DragonEmote>> entry : TARGETS.entrySet()) {
            if (!entry.getKey().equals(key)) {
                otherMembers += entry.getValue().size();
            }
        }

        if (otherMembers + emotes.size() > MAX_EMOTES) {
            return false; // 超出全局槽位预算：按不动
        }

        TARGETS.put(key, List.copyOf(emotes));
        return true;
    }

    private static boolean isForceable(final List<DragonEmote> emotes) {
        if (emotes.size() > MAX_EMOTES) {
            return false;
        }

        int nonBlend = 0;
        for (DragonEmote emote : emotes) {
            if (!emote.blend() && ++nonBlend > 1) {
                return false;
            }
        }

        return true;
    }

    public static boolean isForced(final List<DragonEmote> emotes) {
        return !emotes.isEmpty() && TARGETS.containsKey(keyOf(emotes));
    }

    /** 手动停掉了某个成员（比如从完整轮盘关闭）→ 放弃所有包含它的强制目标 */
    public static void removeContaining(final DragonEmote emote) {
        TARGETS.values().removeIf(emotes -> emotes.contains(emote));
    }

    public static void clear() {
        TARGETS.clear();
    }

    /** 客户端每 tick 检查（由 ForceLoopTicker 调用） */
    public static void tick(final Player player) {
        if (TARGETS.isEmpty()) {
            return;
        }

        DragonEntity dragon = DSEmoteBridge.dragonOf(player);
        if (dragon == null) {
            return;
        }

        Set<DragonEmote> playing = new HashSet<>();
        for (DragonEmote emote : dragon.getCurrentlyPlayingEmotes()) {
            if (emote != null) {
                playing.add(emote);
            }
        }

        Iterator<Map.Entry<String, List<DragonEmote>>> iterator = TARGETS.entrySet().iterator();
        while (iterator.hasNext()) {
            List<DragonEmote> emotes = iterator.next().getValue();

            if (playing.containsAll(emotes)) {
                continue; // 全部在播（含 loops 表情）→ 无需处理
            }

            boolean foreignNonBlend = playing.stream().anyMatch(playingEmote -> !emotes.contains(playingEmote) && !playingEmote.blend());
            boolean targetNonBlend = emotes.stream().anyMatch(emote -> !emote.blend());

            if (foreignNonBlend && targetNonBlend) {
                iterator.remove(); // 冲突 → 让步取消强制（保留当前播放状态）
                continue;
            }

            // 无冲突：成员自然结束/部分结束 → 补齐并续上（startGroup 只启动未在播的）
            DSEmoteBridge.startGroup(player, emotes);
        }
    }

    private static String keyOf(final List<DragonEmote> emotes) {
        return emotes.stream().map(DragonEmote::key).sorted().distinct().collect(Collectors.joining("\u0000"));
    }
}
