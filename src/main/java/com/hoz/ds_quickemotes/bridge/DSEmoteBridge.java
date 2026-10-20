package com.hoz.ds_quickemotes.bridge;

import by.dragonsurvivalteam.dragonsurvival.client.DragonSurvivalClient;
import by.dragonsurvivalteam.dragonsurvival.client.emotes.EmoteKeybinds;
import by.dragonsurvivalteam.dragonsurvival.client.render.ClientDragonRenderer;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateHandler;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.common.entity.DragonEntity;
import by.dragonsurvivalteam.dragonsurvival.network.emotes.StopAllEmotes;
import by.dragonsurvivalteam.dragonsurvival.network.emotes.SyncEmote;
import by.dragonsurvivalteam.dragonsurvival.registry.DSEmoteKeybindings;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.body.emotes.DragonEmote;
import by.dragonsurvivalteam.dragonsurvival.util.AnimationUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 唯一直接接触 Dragon Survival 内部类的薄层，兼容 DS 1.21.1 v2.0.54+。
 * 只使用自 2.0 起结构稳定的 API（DragonEntity 实例方法 + SyncEmote/StopAllEmotes 包体），
 * 不使用 DragonSurvival.PROXY（2.0.9 起签名由 int playerId 改为 Player）。
 */
public final class DSEmoteBridge {
    private DSEmoteBridge() { }

    public static boolean isDragon(final Player player) {
        return DragonStateProvider.isDragon(player);
    }

    /** 玩家当前身体（body）对应的表情列表；挂在数据包注册表上，客户端已同步 */
    public static List<DragonEmote> emotesOf(final Player player) {
        // 非龙状态下 body() 为 null
        if (!isDragon(player)) {
            return List.of();
        }
        DragonStateHandler handler = DragonStateProvider.getData(player);
        return handler.body().value().emotes().value().emotes();
    }

    /** 当前身体的注册表 ID（用于按身体区分页码记忆等）；不在注册表里时为 null */
    public static @Nullable ResourceLocation bodyIdOf(final Player player) {
        return DragonStateProvider.getData(player).body().unwrapKey().map(ResourceKey::location).orElse(null);
    }

    /** 解析表情动画的实际时长（tick；GeckoLib 加载时已把秒 ×20）；动画不存在或龙实体缺失时返回 0 */
    public static double animationLengthTicks(final Player player, final DragonEmote emote) {
        DragonEntity dragon = dragonOf(player);
        if (dragon == null) {
            return 0;
        }
        return AnimationUtils.animationDuration(DragonSurvivalClient.DRAGON_MODEL, dragon, emote.animationKey());
    }

    /** 被挂起暂存的 DS 全局表情快捷键表（轮盘/编辑器期间） */
    private static @Nullable EmoteKeybinds suspendedKeybinds;

    /**
     * 挂起 DS 的全局表情快捷键：把整张 {@link DSEmoteKeybindings#EMOTE_KEYBINDS} 换成空表（原表暂存）。
     * 原因：DS 的全局输入监听（DragonEmoteScreen.onKey）只排除了它自己的界面——
     * 在任意其他界面（包括我们的轮盘）按下绑定的键/鼠标都会触发对应表情；
     * 且其 beginPlayingEmote 的同实例 toggle 语义会与我们的点击逻辑相互抵消
     * （例如把表情绑在鼠标左键上时，每次点击都会误触发并吞掉对同表情的点击）。
     */
    public static void suspendEmoteKeybinds() {
        if (suspendedKeybinds == null) {
            suspendedKeybinds = DSEmoteKeybindings.EMOTE_KEYBINDS;
            DSEmoteKeybindings.EMOTE_KEYBINDS = new EmoteKeybinds();
        }
    }

    public static void restoreEmoteKeybinds() {
        if (suspendedKeybinds != null) {
            DSEmoteKeybindings.EMOTE_KEYBINDS = suspendedKeybinds;
            suspendedKeybinds = null;
        }
    }

    /** 当前龙种 ID（组合数据按龙种分文件存储） */
    public static @Nullable ResourceLocation speciesIdOf(final Player player) {
        ResourceKey<?> key = DragonStateProvider.getData(player).speciesKey();
        return key != null ? key.location() : null;
    }

    /** 按 key 解析表情实例（优先 key()，回退 animationKey），用于组合 JSON 恢复 */
    public static @Nullable DragonEmote findEmote(final List<DragonEmote> emotes, final String key) {
        for (DragonEmote emote : emotes) {
            if (emote.key().equals(key) || emote.animationKey().equals(key)) {
                return emote;
            }
        }
        return null;
    }

    /** 渲染用龙实体（与 DS 本体共用同一实例）；玩家尚未被渲染过时为 null */
    public static @Nullable DragonEntity dragonOf(final Player player) {
        return ClientDragonRenderer.getDragon(player);
    }

    public static boolean isPlaying(final Player player, final DragonEmote emote) {
        DragonEntity dragon = dragonOf(player);
        return dragon != null && dragon.isPlayingEmote(emote);
    }

    /** 当前正在播放的表情（按 DS 的 4 个槽位顺序，跳过空槽） */
    public static List<DragonEmote> activeEmotes(final Player player) {
        DragonEntity dragon = dragonOf(player);
        if (dragon == null) {
            return List.of();
        }

        DragonEmote[] slots = dragon.getCurrentlyPlayingEmotes();
        List<DragonEmote> active = new ArrayList<>(slots.length);
        for (DragonEmote emote : slots) {
            if (emote != null) {
                active.add(emote);
            }
        }
        return active;
    }

    /** 启动组内未在播的表情（已在播的保持不变）。本地生效 + 广播包 */
    public static void startGroup(final Player player, final List<DragonEmote> emotes) {
        DragonEntity dragon = dragonOf(player);
        if (dragon == null) {
            return;
        }

        for (DragonEmote emote : emotes) {
            if (!dragon.isPlayingEmote(emote)) {
                dragon.beginPlayingEmote(emote);
                PacketDistributor.sendToServer(new SyncEmote(player.getId(), emote, false));
            }
        }
    }

    /** 停止组内在播的表情。本地生效 + 广播包 */
    public static void stopGroup(final Player player, final List<DragonEmote> emotes) {
        DragonEntity dragon = dragonOf(player);
        if (dragon == null) {
            return;
        }

        for (DragonEmote emote : emotes) {
            if (dragon.isPlayingEmote(emote)) {
                dragon.stopEmote(emote);
                PacketDistributor.sendToServer(new SyncEmote(player.getId(), emote, true));
            }
        }
    }

    /**
     * 组切换（单表情就是长度为 1 的组）：
     * 组内全部在播 → 全部停止；否则 → 补齐未播的（已在播的保持不变）。
     */
    public static void toggleGroup(final Player player, final List<DragonEmote> emotes) {
        DragonEntity dragon = dragonOf(player);
        if (dragon == null || emotes.isEmpty()) {
            return;
        }

        boolean allPlaying = true;
        for (DragonEmote emote : emotes) {
            if (!dragon.isPlayingEmote(emote)) {
                allPlaying = false;
                break;
            }
        }

        if (allPlaying) {
            stopGroup(player, emotes);
        } else {
            startGroup(player, emotes);
        }
    }

    /** 停止全部表情（含广播包；DS 原版界面里的重置按钮漏发此包） */
    public static void stopAll(final Player player) {
        DragonEntity dragon = dragonOf(player);
        if (dragon == null) {
            return;
        }
        dragon.stopAllEmotes();
        PacketDistributor.sendToServer(new StopAllEmotes(player.getId()));
    }
}
