package com.hoz.ds_quickemotes.client.wheel;

import java.util.HashMap;
import java.util.Map;

/** 会话内记忆每个轮盘（按 轮盘类型+身体 区分）上次关闭时的页码 */
public final class WheelMemory {
    private static final Map<String, Integer> LAST_PAGE = new HashMap<>();

    private WheelMemory() { }

    public static int get(final String key) {
        return LAST_PAGE.getOrDefault(key, 0);
    }

    public static void set(final String key, final int page) {
        LAST_PAGE.put(key, page);
    }
}
