package com.hoz.ds_quickemotes.combo;

import java.util.HashSet;
import java.util.Set;

/**
 * 会话级的「组合激活」追踪。
 * 指示灯只反映「由轮盘启动过的组合」，而不是「成员碰巧都在播」——
 * 因此启动组合 A（呼噜+闭眼）不会让含相同表情的组合 B（呼噜+坐）也亮起。
 * 身份 = 组内表情 key 集合（排序拼接），改名/换序不影响。
 */
public final class ComboActivation {
    private static final Set<String> ACTIVE = new HashSet<>();

    private ComboActivation() { }

    /** 由组合的原始表情 key 计算内容指纹 */
    public static String keyOf(final EmoteCombo combo) {
        if (combo.emoteKeys().isEmpty()) {
            return "";
        }
        return String.join("\u0000", combo.emoteKeys().stream().sorted().distinct().toList());
    }

    public static boolean isActive(final String key) {
        return !key.isEmpty() && ACTIVE.contains(key);
    }

    public static void activate(final String key) {
        if (!key.isEmpty()) {
            ACTIVE.add(key);
        }
    }

    public static void deactivate(final String key) {
        ACTIVE.remove(key);
    }

    public static void clear() {
        ACTIVE.clear();
    }
}
