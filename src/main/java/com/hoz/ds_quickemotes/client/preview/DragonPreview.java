package com.hoz.ds_quickemotes.client.preview;

import by.dragonsurvivalteam.dragonsurvival.client.DragonSurvivalClient;
import by.dragonsurvivalteam.dragonsurvival.client.render.ClientDragonRenderer;
import by.dragonsurvivalteam.dragonsurvival.client.util.FakeClientPlayer;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateHandler;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.common.entity.DragonEntity;
import by.dragonsurvivalteam.dragonsurvival.registry.DSEntities;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.body.emotes.DragonEmote;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 右侧龙模型预览。
 * 隔离方案（与 DS 皮肤界面一致）：
 * - 自建 FakeClientPlayer（onGround 覆写为 true → 基础姿态走 idle 而非坠落），
 *   持真玩家的 DragonStateHandler；移动数据为假玩家默认值 → 不受摄像机/姿态污染；
 * - useVisualScale + scale=1.0 → 固定基础体型；
 * - overrideUUIDWithLocalPlayerForTextureFetch=true → 贴图解析走真玩家（保留自定义皮肤）；
 * - 预览专用 main 控制器强制播放模型 idle（有非 blend 表情时让位），站桩不占表情槽；
 *   其余表情控制器照常运行（getPlayer() 返回假玩家）；悬停目标以静音副本应用。
 * 交互：鼠标在模型上左键拖拽旋转、右键拖拽平移，可拖到屏幕任意位置；
 * 关闭轮盘/编辑器时恢复默认朝向与位置（滚轮留给翻页）。
 */
public final class DragonPreview {
    private static final int FAKE_PLAYER_NUMBER = 20;
    /** 初始朝向常量；单位与拖拽一致，1 单位 = 10° */
    private static final float DEFAULT_YAW = 4.4701557f;
    private static final float DEFAULT_PITCH = -2.737381f;

    /**
     * 预览专用 main 控制器的站桩动画（与 DS 龙编辑器「Idle 展示」同款原理：按名字强制播放模型的标准 idle）。
     * 附属模型的标准控制器栈会因姿态拟合等判定在部分模型上选到别的动画，不能作为预览的站桩来源；
     * 这里替换 main 控制器，不占 DS 的 4 个表情槽，4 成员组合可以完整应用。
     */
    private static final RawAnimation IDLE_RAW = RawAnimation.begin().thenLoop("idle");

    /** DS DragonEntity.MAX_EMOTES = 4（私有常量）；表情控制器名为 "emote_" + slot */
    private static final int EMOTE_SLOTS = 4;

    private static FakeClientPlayer fakePlayer;
    private static DragonEntity dragon;
    private static Player boundPlayer;
    private static List<DragonEmote> currentTarget = List.of();
    /** 上次渲染解析到的动画资源（换龙种/换 body 会变）；静态跨开屏保留，用于检测模型切换 */
    private static ResourceLocation lastAnimationResource;

    /** 视角（拖拽角度，1 单位 = 10°）与平移（屏幕像素） */
    private static float yaw = DEFAULT_YAW;
    private static float pitch = DEFAULT_PITCH;
    private static double panX;
    private static double panY;

    /**
     * 实时动画时钟：与 DS 假龙（DragonEntity.globalTickCount）同源——逐帧累加真实经过时间。
     * 世界里的龙 = 整数 tickCount + 真实 partialTick；假龙 = 实时时钟；
     * 两者都逐帧连续，因此皮肤界面的假龙和世界龙同样丝滑。
     */
    private static double realtimeAnimationClock;

    private DragonPreview() { }

    /** 左键拖拽：旋转 */
    public static void addRotation(final float deltaYaw, final float deltaPitch) {
        yaw += deltaYaw;
        pitch += deltaPitch;
    }

    /** 右键拖拽：平移（不限制边界，允许拖到屏幕任意位置） */
    public static void addPan(final double deltaX, final double deltaY) {
        panX += deltaX;
        panY += deltaY;
    }

    /** 关闭界面时恢复默认视角/位置；也用于避免模型被拖到死区后无法找回 */
    public static void resetView() {
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        panX = 0;
        panY = 0;
    }

    /**
     * 模型在屏幕上的粗略命中区：以脚底锚点为基准，按当前缩放给龙的躯干/翅膀留出包围盒。
     * 拖拽起点用它判定；其他 UI 元素应在调用前优先消费点击。
     */
    public static boolean isMouseOverModel(final int anchorX, final int anchorY, final float scale,
                                           final double mouseX, final double mouseY) {
        double centerX = anchorX + panX;
        double feetY = anchorY + panY;
        double halfWidth = Math.max(12.0, scale * 2.6);
        double top = feetY - scale * 4.5;
        double bottom = feetY + scale * 0.5;
        return mouseX >= centerX - halfWidth && mouseX <= centerX + halfWidth
                && mouseY >= top && mouseY <= bottom;
    }

    /** 悬停目标变化时调用（emotes 为空 = 站桩）；内部按内容去重 */
    public static void setTarget(final Player player, final List<DragonEmote> realEmotes) {
        ensure(player);

        if (dragon == null) {
            return;
        }

        // 站桩由预览专用 main 控制器保证（见 ensure），不占用表情槽；这里只应用目标成员，
        // 顺序与组合成员（轮盘实际应用顺序）一致，4 个成员可完整占满 4 槽。
        List<DragonEmote> desired = new ArrayList<>(realEmotes.size());
        for (DragonEmote emote : realEmotes) {
            desired.add(silentCopy(emote));
        }

        if (!desired.equals(currentTarget)) {
            for (DragonEmote playing : currentTarget) {
                dragon.stopEmote(playing);
            }

            currentTarget = desired;
            for (DragonEmote emote : currentTarget) {
                dragon.beginPlayingEmote(emote);
            }
            armEmoteControllers();
        }
    }

    /** 渲染预览龙：负 z 后移 → 完全位于 UI 背后（DS 编辑器同款约定） */
    public static void render(final GuiGraphics graphics, final Player player, final float partialTick,
                              final int centerX, final int bottomY, final float scale) {
        ensure(player);

        if (dragon == null) {
            return;
        }

        // 实时动画时钟推进（与 DS 假龙 globalTickCount 同源）
        realtimeAnimationClock += Minecraft.getInstance().getTimer().getRealtimeDeltaTicks();

        ClientDragonRenderer.partialTick = partialTick;

        Quaternionf rotation = Axis.ZP.rotationDegrees(180);
        rotation.mul(Axis.XP.rotationDegrees(pitch * 10));
        rotation.rotateY((float) Math.toRadians(180 - yaw * 10));

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, 0, -200); // 位于 UI 背后（DS 编辑器同款约定）
        // 帧率解限：与 InventoryScreen.renderEntityInInventory 相同的摆位，但传入屏幕渲染的真实 partialTick。
        // 原版 GUI 路径在内部把 partialTick 写死为 1.0F，导致 GeckoLib 的帧间插值失效
        // （动画退化为每 tick 跳变的 ~20 次/秒；世界里的龙用的是逐帧真实 partialTick，所以更顺滑）。
        pose.translate(centerX + panX, bottomY + panY, 50.0);
        pose.scale(scale, scale, -scale);
        pose.mulPose(rotation);

        // 换龙种 / 换 body 会更换动画资源：GeckoLib 的 setAnimation 对同名动画有去重（相等即跳过），
        // 控制器里旧模型的播放记录会让"重新应用 idle"被静默吞掉、继续用旧动画对象套新骨架（错误拼接）。
        // 检测资源变化并在渲染前强制重应用 + 解锁去重（本帧 predicate 即生效）
        ResourceLocation animationResource = DragonSurvivalClient.DRAGON_MODEL.getAnimationResource(dragon);
        if (lastAnimationResource != null && !lastAnimationResource.equals(animationResource)) {
            if (!currentTarget.isEmpty()) {
                reapplyTarget();
            } else if (dragon.mainAnimationController != null) {
                dragon.mainAnimationController.forceAnimationReset();
            }
        }
        lastAnimationResource = animationResource;

        // 所在屏幕（编辑器）可能已经先画了半透明底板/面板，其深度写入会挡住 z=-200 的龙；
        // 渲染前清一次深度，保证预览龙能覆盖在这些底色之上；渲染后再清一次保护后续 UI。
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, false);

        Lighting.setupForEntityInInventory();
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        dispatcher.setRenderShadow(false);
        RenderSystem.runAsFancy(() -> dispatcher.render(dragon, 0.0, 0.0, 0.0, 0.0F, partialTick, graphics.pose(), graphics.bufferSource(), 15728880));
        graphics.flush();
        dispatcher.setRenderShadow(true);
        pose.popPose();
        Lighting.setupFor3DItems();

        // 外部守卫（DS 编辑器假龙同款兜底）：槽位有目标表情但控制器没在播 → 解锁同名去重，下一帧重新生效
        guardStuckEmoteControllers();

        // 图层保证：清空深度缓冲 → 之后绘制的所有 UI（含文字与半透明几何）必然叠在龙模型上层，
        // 大体型/大缩放模型的任何部位都不会穿到 UI 前面（颜色混合不受影响）。
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, false);
    }

    private static void ensure(final Player player) {
        if (player == null || player.level() == null) {
            return;
        }

        if (dragon != null && boundPlayer == player && dragon.level() == player.level()) {
            return;
        }

        DragonStateHandler handler = DragonStateProvider.getData(player);

        fakePlayer = new FakeClientPlayer(FAKE_PLAYER_NUMBER) {
            @Override
            public boolean onGround() {
                // 让基础姿态落到 idle 分支（否则假玩家 onGround=false → 坠落动画）
                return true;
            }
        };
        fakePlayer.handler = handler;
        // 固定基础体型（皮肤界面同款：不随玩家成长缩放）
        fakePlayer.useVisualScale = true;
        fakePlayer.scale = 1.0D;

        dragon = new DragonEntity(DSEntities.DRAGON.get(), fakePlayer.level()) {
            @Override
            public Player getPlayer() {
                return fakePlayer;
            }

            @Override
            public double getTick(final Object obj) {
                // 与 DS 假龙（皮肤界面/编辑器）同款的实时动画时钟：逐帧连续推进，GUI 内动画丝滑；
                // 默认实现用玩家整数 tickCount，只有配合渲染 partialTick 才连续（易受写死的 partialTick 影响）。
                // +200 与 DS 一致，避免负 tick 导致 T-pose。
                return realtimeAnimationClock + 200;
            }

            @Override
            public void registerControllers(final AnimatableManager.ControllerRegistrar controllers) {
                super.registerControllers(controllers);
                // 替换标准 main：预览始终以模型的 idle 站桩；有非 blend 表情在播时让位（与 DS 主谓词一致）。
                // 这样站桩不占 4 个表情槽，4 成员组合可以按顺序完整应用；也不再受附属模型姿态拟合影响。
                controllers.remove("main");
                AnimationController<DragonEntity> idleMain = new AnimationController<>(this, "main", 2, state -> {
                    for (DragonEmote emote : getCurrentlyPlayingEmotes()) {
                        if (emote != null && !emote.blend()) {
                            return PlayState.STOP;
                        }
                    }
                    return state.setAndContinue(IDLE_RAW);
                });
                mainAnimationController = idleMain;
                controllers.controllers().add(0, idleMain);
            }
        };
        // 必须设置：标准 predicate 会做 DRAGONS_JUMPING.getOrDefault(playerId, ...)，
        // ConcurrentHashMap 不接受 null 键（DS 自家假龙因覆盖控制器而不受影响）。
        dragon.playerId = player.getId();
        dragon.isInInventory = true;
        // 贴图解析改用真玩家（保留自定义皮肤；姿势仍用假玩家的隔离数据）
        dragon.overrideUUIDWithLocalPlayerForTextureFetch = true;

        boundPlayer = player;
        currentTarget = List.of();
        lastAnimationResource = null;
        panX = 0;
        panY = 0;
    }

    /**
     * 静音副本：预览不播放表情音效。
     * loops 强制为 true：预览希望一次性动画也持续循环；交给 DS 的 thenLoop 原生循环，
     * 不要靠"播完重播 + 强制重置控制器"（那会让模型每个循环都跳回初始状态）。
     */
    private static DragonEmote silentCopy(final DragonEmote emote) {
        return new DragonEmote(emote.animationKey(), emote.translationOverride(), emote.speed(), emote.duration(),
                true, emote.blend(), emote.locksHead(), emote.locksTail(), emote.thirdPerson(),
                emote.canMove(), Optional.empty());
    }

    /** 模型重建后重新应用当前目标（停止 + 重新开始，再解除控制器去重） */
    private static void reapplyTarget() {
        for (DragonEmote emote : currentTarget) {
            dragon.stopEmote(emote);
        }
        for (DragonEmote emote : currentTarget) {
            dragon.beginPlayingEmote(emote);
        }
        if (dragon.mainAnimationController != null) {
            dragon.mainAnimationController.forceAnimationReset();
        }
        armEmoteControllers();
    }

    /**
     * 解除表情控制器的「同名动画去重」：GeckoLib 的 setAnimation 对与控制器已存记录相等的原始动画
     * 会直接跳过（no change = no update），模型重建后残留的记录会让重新应用静默失效。
     * forceAnimationReset 只置位 needsAnimationReload，使下一次 set 强制生效——与 DS 自家假龙工具同款兜底。
     */
    private static void armEmoteControllers() {
        AnimatableManager<DragonEntity> manager = dragon.getAnimatableInstanceCache().getManagerForId(dragon.getId());
        if (manager == null) {
            return;
        }
        for (int slot = 0; slot < EMOTE_SLOTS; slot++) {
            AnimationController<DragonEntity> controller = manager.getAnimationControllers().get("emote_" + slot);
            if (controller != null) {
                controller.forceAnimationReset();
            }
        }
    }

    /** 槽位里有目标表情、但控制器没在播（被同名去重卡住）→ 解锁，让下一帧的 predicate 重新生效 */
    private static void guardStuckEmoteControllers() {
        DragonEmote[] slots = dragon.getCurrentlyPlayingEmotes();
        AnimatableManager<DragonEntity> manager = dragon.getAnimatableInstanceCache().getManagerForId(dragon.getId());
        if (manager == null) {
            return;
        }
        for (int slot = 0; slot < EMOTE_SLOTS; slot++) {
            if (slots[slot] == null) {
                continue;
            }
            AnimationController<DragonEntity> controller = manager.getAnimationControllers().get("emote_" + slot);
            if (controller != null && controller.getCurrentAnimation() == null) {
                controller.forceAnimationReset();
            }
        }
    }
}
