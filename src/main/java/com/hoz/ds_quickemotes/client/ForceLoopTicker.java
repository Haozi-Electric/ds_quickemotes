package com.hoz.ds_quickemotes.client;

import com.hoz.ds_quickemotes.bridge.DSEmoteBridge;
import com.hoz.ds_quickemotes.combo.ForceLoop;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * 强制循环的驱动与生命周期：
 * - 每客户端 tick 检查一次续播；
 * - 死亡 / 不是龙（登出换人）/ 维度切换（世界卸载）→ 清空强制状态。
 */
@EventBusSubscriber(Dist.CLIENT)
public final class ForceLoopTicker {
    private ForceLoopTicker() { }

    @SubscribeEvent
    public static void onClientTick(final ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;

        if (player == null || minecraft.level == null || !DSEmoteBridge.isDragon(player) || player.isDeadOrDying()) {
            ForceLoop.clear();
            return;
        }

        ForceLoop.tick(player);
    }

    @SubscribeEvent
    public static void onLevelUnload(final LevelEvent.Unload event) {
        ForceLoop.clear();
    }
}
