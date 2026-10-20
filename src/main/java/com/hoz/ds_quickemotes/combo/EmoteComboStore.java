package com.hoz.ds_quickemotes.combo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hoz.ds_quickemotes.DragonSurvivalQuickEmotes;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义组合的本地存储：按龙种（speciesId）分文件，
 * 位于 &lt;gamedir&gt;/ds_quickemotes/emote_combos/&lt;namespace&gt;/&lt;path&gt;.json。
 * 读取失败或文件缺失时回退为空列表。
 */
public final class EmoteComboStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String ROOT = "ds_quickemotes/emote_combos";
    private static final Map<ResourceLocation, List<EmoteCombo>> CACHE = new HashMap<>();

    private EmoteComboStore() { }

    /** 当前缓存的组合列表（首次访问从磁盘加载）；返回的列表可变，改完后调用 {@link #save} */
    public static List<EmoteCombo> getCombos(final ResourceLocation speciesId) {
        return CACHE.computeIfAbsent(speciesId, EmoteComboStore::load);
    }

    /** 替换缓存，随后调用 {@link #save} 落盘 */
    public static void setCombos(final ResourceLocation speciesId, final List<EmoteCombo> combos) {
        CACHE.put(speciesId, new ArrayList<>(combos));
    }

    public static void save(final ResourceLocation speciesId) {
        List<EmoteCombo> combos = CACHE.get(speciesId);
        if (combos == null) {
            return;
        }

        Path file = fileFor(speciesId);
        // 先写临时文件再原子替换，避免写到一半崩溃留下截断的 JSON
        Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temp)) {
                GSON.toJson(new ComboFile(combos), writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            DragonSurvivalQuickEmotes.LOGGER.error("无法写入表情组合文件 [{}]", file, exception);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响主流程
            }
        }
    }

    private static List<EmoteCombo> load(final ResourceLocation speciesId) {
        Path file = fileFor(speciesId);

        if (!Files.exists(file)) {
            return new ArrayList<>();
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            ComboFile parsed = GSON.fromJson(reader, ComboFile.class);
            if (parsed == null || parsed.combos == null) {
                return new ArrayList<>();
            }
            List<EmoteCombo> combos = new ArrayList<>(parsed.combos.size());
            for (EmoteCombo combo : parsed.combos) {
                if (combo != null) {
                    combos.add(combo);
                }
            }
            return combos;
        } catch (IOException | RuntimeException exception) {
            DragonSurvivalQuickEmotes.LOGGER.warn("表情组合文件读取失败 [{}]，按空列表处理", file, exception);
            return new ArrayList<>();
        }
    }

    private static Path fileFor(final ResourceLocation speciesId) {
        Path base = FMLPaths.GAMEDIR.get().resolve(ROOT).normalize();
        Path file = base.resolve(speciesId.getNamespace())
                .resolve(speciesId.getPath() + ".json").normalize();
        if (file.startsWith(base)) {
            return file;
        }

        // ResourceLocation 的 path 允许 '/' 与 '.'，含 ".." 时会越出组合目录；越界时回退为扁平文件名
        StringBuilder safe = new StringBuilder();
        for (char c : speciesId.getPath().toCharArray()) {
            safe.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }
        return base.resolve(speciesId.getNamespace()).resolve(safe + ".json");
    }

    private record ComboFile(List<EmoteCombo> combos) { }
}
