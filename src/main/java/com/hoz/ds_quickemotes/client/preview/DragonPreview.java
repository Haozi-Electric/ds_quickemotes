package com.hoz.ds_quickemotes.client.preview;

import by.dragonsurvivalteam.dragonsurvival.client.DragonSurvivalClient;
import by.dragonsurvivalteam.dragonsurvival.client.render.ClientDragonRenderer;
import by.dragonsurvivalteam.dragonsurvival.client.render.entity.dragon.DragonRenderer;
import by.dragonsurvivalteam.dragonsurvival.client.util.FakeClientPlayer;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateHandler;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.common.entity.DragonEntity;
import by.dragonsurvivalteam.dragonsurvival.registry.DSEntities;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.body.emotes.DragonEmote;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.animation.object.PlayState;
import com.geckolib.renderer.base.GeoRenderState;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 右侧龙模型预览。
 * 隔离方案（与 DS 皮肤界面一致）：
 * - 自建 FakeClientPlayer（onGround 覆写为 true → 基础姿态走 idle 而非坠落），
 *   持真玩家的 DragonStateHandler；移动数据为假玩家默认值 → 不受摄像机/姿态污染；
 * - useVisualScale + scale=1.0 → 固定基础体型；
 * - 渲染状态按 UI 模式生成并传入真玩家作为贴图来源（保留自定义皮肤）；
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
    private static Identifier lastAnimationResource;

    /** 视角（拖拽角度，1 单位 = 10°）与平移（屏幕像素） */
    private static float yaw = DEFAULT_YAW;
    private static float pitch = DEFAULT_PITCH;
    private static double panX;
    private static double panY;

    /**
     * PIP 纹理矩形：以预览面板为中心向四周扩内边距，并保证最小尺寸。
     * 26.1 的实体预览会被硬裁剪到该矩形内，必须留足旋转/缩放后的余量。
     */
    private static final int PREVIEW_PADDING = 96;
    private static final int PREVIEW_MIN_WIDTH = 320;
    private static final int PREVIEW_MIN_HEIGHT = 360;
    /** 预览内动画过渡（tick）：切目标/切换表情时平滑，而不是瞬间换姿势 */
    private static final int PREVIEW_TRANSITION_TICKS = 3;

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

    /** 悬停目标变化时调用（emotes 为空 = 站桩）；内部按 key 去重并复用正在播放的实例 */
    public static void setTarget(final Player player, final List<DragonEmote> realEmotes) {
        ensure(player);

        if (dragon == null) {
            return;
        }

        // 站桩由预览专用 main 控制器保证（见 ensure），不占用表情槽；这里只应用目标成员，
        // 顺序与组合成员（轮盘实际应用顺序）一致，4 个成员可完整占满 4 槽。
        // 复用当前实例：切换目标时，两个目标共有的表情保持原槽位/原播放进度，避免“停掉再重开”的跳变。
        List<DragonEmote> desired = new ArrayList<>(realEmotes.size());
        for (DragonEmote emote : realEmotes) {
            DragonEmote existing = findCurrent(emote.key());
            desired.add(existing != null ? existing : silentCopy(emote));
        }

        if (desired.equals(currentTarget)) {
            return;
        }

        List<DragonEmote> previous = currentTarget;
        currentTarget = desired;

        // 先释放不再需要的槽位，再补齐新增成员；保留的成员完全不打断
        for (DragonEmote playing : previous) {
            if (!containsKey(desired, playing.key())) {
                dragon.stopEmote(playing);
            }
        }
        for (DragonEmote emote : desired) {
            if (!containsKey(previous, emote.key())) {
                dragon.beginPlayingEmote(emote);
            }
        }
    }

    private static @org.jetbrains.annotations.Nullable DragonEmote findCurrent(final String key) {
        for (DragonEmote playing : currentTarget) {
            if (playing.key().equals(key)) {
                return playing;
            }
        }
        return null;
    }

    private static boolean containsKey(final List<DragonEmote> emotes, final String key) {
        for (DragonEmote emote : emotes) {
            if (emote.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 渲染预览龙。26.1 起实体进入 GUI 只能以 EntityRenderState + 离屏 PIP 的形式提交；
     * 这里自行创建我们这只龙的渲染状态（而不是走 DS 的 createUIRenderState——它会把实体替换成
     * 只会播放单个动画的 UI 占位龙，丢掉完整的表情控制器栈）。
     */
    public static void render(final GuiGraphicsExtractor graphics, final Player player, final float partialTick,
                              final int panelLeft, final int panelTop, final int panelRight, final int panelBottom,
                              final int anchorY, final float scale) {
        ensure(player);

        if (dragon == null) {
            return;
        }

        ClientDragonRenderer.partialTick = partialTick;

        // 与 DS 的 UI 假龙一致：body 刷新标记由预览自己消费；否则 emotePredicate 会逐帧 reset，
        // 表现就是动画反复闪回、无法过渡
        if (fakePlayer.handler.refreshBody) {
            reapplyTarget();
        }

        Quaternionf rotation = Axis.ZP.rotationDegrees(180);
        rotation.mul(Axis.XP.rotationDegrees(pitch * 10));
        rotation.rotateY((float) Math.toRadians(180 - yaw * 10));

        // 换龙种 / 换 body 会更换动画资源：GeckoLib 的 setAnimation 对同名动画有去重（相等即跳过），
        // 控制器里旧模型的播放记录会让"重新应用 idle"被静默吞掉、继续用旧动画对象套新骨架（错误拼接）。
        // 检测资源变化并在渲染前强制重应用 + 解锁去重（本帧 predicate 即生效）
        Identifier animationResource = DragonSurvivalClient.DRAGON_MODEL.getAnimationResource(dragon);
        if (lastAnimationResource != null && !lastAnimationResource.equals(animationResource)) {
            if (!currentTarget.isEmpty()) {
                reapplyTarget();
            } else if (dragon.mainAnimationController != null) {
                dragon.mainAnimationController.reset();
            }
        }
        lastAnimationResource = animationResource;

        // 本次 setAndContinue 之前决定过渡时长：只有存在真正上一帧姿势时才插过渡
        updatePreviewTransitionTicks();

        EntityRenderState renderState = createRenderState(player, partialTick);
        if (renderState == null) {
            return;
        }

        // PIP 矩形跟随模型当前位置移动：纹理尺寸保持稳定，但模型被拖到屏幕任何位置
        // 都不会超出矩形而被裁掉。矩形本身以预览面板尺寸为基准留出旋转/缩放余量。
        int centerX = (panelLeft + panelRight) / 2;
        int centerY = (panelTop + panelBottom) / 2;
        int rectWidth = Math.max(panelRight - panelLeft + PREVIEW_PADDING * 2, PREVIEW_MIN_WIDTH);
        int rectHeight = Math.max(panelBottom - panelTop + PREVIEW_PADDING * 2, PREVIEW_MIN_HEIGHT);
        // 编辑器的滚轮缩放会放大 scale：矩形也跟着放大，避免放大后再次被裁切
        int scaledFloor = (int) (scale * 8.0f);
        rectWidth = Math.max(rectWidth, scaledFloor);
        rectHeight = Math.max(rectHeight, scaledFloor);

        // 尾巴/翅膀通常在模型右侧伸得更远：只把右边界右移（允许出屏）。
        int rightExtend = Math.max(96, (int) (scale * 4.0f));

        int roundedPanX = (int) Math.round(panX);
        int roundedPanY = (int) Math.round(panY);
        int x0 = centerX - rectWidth / 2 + roundedPanX;
        int x1 = centerX + rectWidth / 2 + roundedPanX + rightExtend;
        int y0 = centerY - rectHeight / 2 + roundedPanY;
        int y1 = centerY + rectHeight / 2 + roundedPanY;

        // 实体原点固定在 (面板中心 + pan, anchorY + pan)：矩形随 pan 移动，
        // translation 只负责抵消矩形中心偏移（含取整余量与右侧扩展）。
        float offsetScale = Math.max(scale, 1.0f);
        Vector3f translation = new Vector3f(
                ((float) panX - roundedPanX - rightExtend / 2.0f) / offsetScale,
                (anchorY - centerY + (float) panY - roundedPanY) / offsetScale,
                0.0f);

        // 平移与缩放只影响摆位；旋转沿用旧版四元数（Z 轴 180° + 俯仰 + 自旋）
        graphics.entity(renderState, scale, translation, rotation, null, x0, y0, x1, y1);
        // 预览必须在同一帧后续 UI 之下（PIP 是 GUI blit，分层由 stratum 决定而非调用顺序）
        graphics.nextStratum();
    }

    /**
     * 为预览龙创建 UI 渲染状态：先按普通实体路径建立状态（GeckoLib 会在这里推进控制器），
     * 再把 DragonRenderData 切换为 UI 模式（固定朝向/不追踪移动、贴图走真玩家）。
     */
    private static @org.jetbrains.annotations.Nullable EntityRenderState createRenderState(final Player player, final float partialTick) {
        EntityRenderer<? super DragonEntity, ?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(dragon);
        EntityRenderState renderState = renderer.createRenderState(dragon, partialTick);
        renderState.shadowPieces.clear();
        renderState.outlineColor = 0;

        if (renderState instanceof GeoRenderState geoRenderState) {
            DragonRenderer.DragonRenderData renderData = geoRenderState.getGeckolibData(DragonRenderer.DRAGON_RENDER_DATA);
            if (renderData != null) {
                // 不读 inUI()：DS 2.1.0/2.1.1 里这个访问器叫 inInventory()，2.1.3 才改名。
                // 我们每次都是新建 render state 且不设置 UI_RENDER_CONTEXT，初始数据必定不是 UI 模式，
                // 直接 patch 即可，从而兼容 2.1.0+。
                renderData = renderData.forUIRender(0, 0, 0, player, null, null);
                geoRenderState.addGeckolibData(DragonRenderer.DRAGON_RENDER_DATA, renderData);
            }
            DragonRenderer.overrideMolangQueryValues(geoRenderState);
        }

        return renderState;
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
            public void registerControllers(final AnimatableManager.ControllerRegistrar controllers) {
                super.registerControllers(controllers);
                // 替换标准 main：预览始终以模型的 idle 站桩；有非 blend 表情在播时让位（与 DS 主谓词一致）。
                // 这样站桩不占 4 个表情槽，4 成员组合可以按顺序完整应用；也不再受附属模型姿态拟合影响。
                controllers.remove("main");
                AnimationController<DragonEntity> idleMain = new AnimationController<>("main", 2, state -> {
                    for (DragonEmote emote : getCurrentlyPlayingEmotes()) {
                        if (emote != null && !emote.blend()) {
                            // 必须清掉 idle 的播放记录：否则表情结束时 setAndContinue(IDLE_RAW)
                            // 会被同名去重吞掉，控制器停在旧时间点，出现“先闪回再到位”
                            state.controller().reset();
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

        boundPlayer = player;
        currentTarget = List.of();
        lastAnimationResource = null;
        panX = 0;
        panY = 0;
        updatePreviewTransitionTicks();
    }

    /**
     * GeckoLib 5 的过渡与 4 不同：{@code transitionTicks > 0} 会在新动画前插一个过渡段。
     * 如果控制器没有上一段动画（刚 reset / 第一次播放 / 槽位刚清空），这个过渡段会
     * “从新动画首帧插值回站桩”，然后跳回新动画——表现为闪回。
     * DS 自家 UI 假龙用 {@code wasReset ? 0 : 2} 规避；这里按控制器是否真有上一帧姿势动态选择。
     */
    private static void updatePreviewTransitionTicks() {
        if (dragon == null) {
            return;
        }
        AnimatableManager<DragonEntity> manager = dragon.getAnimatableInstanceCache().getManagerForId(dragon.getId());
        if (manager == null) {
            return;
        }
        for (int slot = 0; slot < EMOTE_SLOTS; slot++) {
            AnimationController<DragonEntity> controller = manager.getAnimationControllers().get("emote_" + slot);
            if (controller != null) {
                controller.setTransitionTicks(hasPreviousPose(controller) ? PREVIEW_TRANSITION_TICKS : 0);
            }
        }
        if (dragon.mainAnimationController != null) {
            dragon.mainAnimationController.setTransitionTicks(
                    hasPreviousPose(dragon.mainAnimationController) ? PREVIEW_TRANSITION_TICKS : 0);
        }
    }

    private static boolean hasPreviousPose(final AnimationController<DragonEntity> controller) {
        return controller.getPlayState() != PlayState.STOP
                && controller.getCurrentRawAnimation() != null
                && controller.getCurrentAnimationPoint() != null;
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
            dragon.mainAnimationController.reset();
        }
        armEmoteControllers();
        // 这次预览已经自己重建过控制器；清掉标记，避免 DS 的 emotePredicate 每帧 reset
        fakePlayer.handler.refreshBody = false;
    }

    /**
     * 解除表情控制器的「同名动画去重」：GeckoLib 的 setAnimation 对与控制器已存记录相等的原始动画
     * 会直接跳过（no change = no update），模型重建后残留的记录会让重新应用静默失效。
     * reset() 清空控制器记录，使下一次 set 强制生效——与 DS 自家假龙工具同款兜底。
     */
    private static void armEmoteControllers() {
        AnimatableManager<DragonEntity> manager = dragon.getAnimatableInstanceCache().getManagerForId(dragon.getId());
        if (manager == null) {
            return;
        }
        for (int slot = 0; slot < EMOTE_SLOTS; slot++) {
            AnimationController<DragonEntity> controller = manager.getAnimationControllers().get("emote_" + slot);
            if (controller != null) {
                controller.reset();
            }
        }
        // reset 后没有可用的上一帧姿势，下一帧的过渡时长会由 updatePreviewTransitionTicks 置 0
    }
}
