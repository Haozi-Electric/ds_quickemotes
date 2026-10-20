package com.hoz.ds_quickemotes.combo;

import java.util.List;
import java.util.Objects;

/**
 * 一个自定义表情组合：名称 + 组内表情 key 列表。
 * key 引用 DragonEmote.key()（translationOverride 优先、回退 animationKey），
 * 使用时对照当前身体的表情集解析，解析不到的项会被忽略。
 */
public record EmoteCombo(String name, List<String> emotes) {
    public EmoteCombo {
        name = name == null ? "" : name;
        // 损坏的 JSON 可能含 null 成员（List.copyOf 会抛 NPE）
        emotes = emotes == null ? List.of() : emotes.stream().filter(Objects::nonNull).toList();
    }

    public List<String> emoteKeys() {
        return emotes;
    }
}
