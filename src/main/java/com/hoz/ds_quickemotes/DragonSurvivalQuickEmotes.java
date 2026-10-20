package com.hoz.ds_quickemotes;

import org.slf4j.Logger;

import com.hoz.ds_quickemotes.client.QuickEmoteKeybinds;
import com.hoz.ds_quickemotes.config.QuickEmotesConfig;
import com.mojang.logging.LogUtils;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = DragonSurvivalQuickEmotes.MODID, dist = Dist.CLIENT)
public class DragonSurvivalQuickEmotes {
    public static final String MODID = "ds_quickemotes";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DragonSurvivalQuickEmotes(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(QuickEmoteKeybinds::register);
        modContainer.registerConfig(ModConfig.Type.CLIENT, QuickEmotesConfig.SPEC);
        // 不注册它的话，模组列表里的「配置」按钮是灰色的（ModListScreen 检查此扩展点）
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
