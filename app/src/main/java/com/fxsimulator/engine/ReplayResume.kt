package com.fxsimulator.engine

/**
 * 存档的来源标识。
 *
 * 关键教训：**存档的键必须是稳定且可比对的字符串**。
 * 之前存的是 BarSource 的 sourceName（给用户看的名字），
 * 恢复时却拿它去和 settings 里的 file:/… / uri:… 比对 —— 两者永远不相等，
 * 于是每次重启都从第 0 根开始，用户看到的就是「回放位置丢了」。
 *
 * 显示名与身份必须分开：显示名给界面，身份给比对。
 */
object SourceKeys {

    /** 内置 DEMO 数据的固定键 */
    const val DEMO = "demo"

    /** 拿不到来源时的兜底键（不会被当成任何已存档来源） */
    const val UNKNOWN = "unknown"

    /** 内置月份键的前缀 */
    private const val BUNDLED_PREFIX = "bundled:"

    /**
     * 内置月份的来源键。
     *
     * **必须只有这一个生成点。** 之前的 bug 正是两个地方各写各的：
     * initialLoad 用 `SourceKeys.of(lastId, isDemo)` 造键（只会得到 unknown），
     * useBundledMonth 用 `"bundled:" + month` 存 —— 两边对不上，
     * 于是内置月份的存档永远读不出来，重启必然回到开头。
     */
    fun bundled(month: String): String = BUNDLED_PREFIX + month.trim()

    /** @return 是内置月份键时返回 yyyy-MM，否则 null */
    fun bundledMonth(key: String?): String? {
        val k = key?.trim().orEmpty()
        if (!k.startsWith(BUNDLED_PREFIX)) return null
        return k.removePrefix(BUNDLED_PREFIX).trim().ifEmpty { null }
    }

    /**
     * 生成来源键。
     *
     * @param persistId 导入时写进 settings 的持久化 id，形如 file:/path/x.csv 或 uri:content://…
     */
    fun of(persistId: String?, isDemo: Boolean): String {
        if (isDemo) return DEMO
        val id = persistId?.trim().orEmpty()
        return if (id.isEmpty()) UNKNOWN else id
    }
}

/**
 * 启动时该载入哪个月份。
 *
 * 抽成纯函数同样是因为它极易静默失效：写错了不会报错，
 * 只是「每次打开都回到第一个月」—— 而用户只会觉得「存档坏了」。
 */
object StartupMonth {

    /**
     * @param saved 上次玩的月份（来自 settings），可能为 null / 空 / 已失效
     * @param available 当前包里实际可用的月份，升序
     * @return 该载入的月份；一个都没有时返回空串
     */
    fun pick(saved: String?, available: List<String>): String {
        val s = saved?.trim().orEmpty()
        if (s.isNotEmpty() && available.contains(s)) return s
        // 存的那个月已经不存在了（换了版本、数据被裁掉）：退回第一个。
        // 用 lastOrNull 会让人以为「怎么每次都跳到最后一个月」，first 更可预期。
        return available.firstOrNull().orEmpty()
    }
}

/**
 * 重启后「从第几根继续」的判定。
 *
 * 抽成纯函数是有必要的：这段逻辑原本埋在 initialLoad 里，
 * 一个 != 写反了就静默失效（不报错，只是位置永远回到开头），
 * 而它恰恰是最容易出错、又最该被测试盯住的地方。
 */
object ReplayResume {

    enum class Outcome {
        /** 按存档继续 */
        RESUMED,
        /** 存档里的来源与当前来源不同 —— 从头开始 */
        SOURCE_CHANGED,
        /** 没有存档 */
        NO_SAVE,
        /** 存档位置超出当前数据范围（换过更短的文件），夹到末根 */
        CLAMPED,
    }

    data class Decision(val index: Int, val outcome: Outcome) {
        val resumed: Boolean get() = outcome == Outcome.RESUMED || outcome == Outcome.CLAMPED
    }

    fun decide(
        savedKey: String?,
        savedIndex: Int,
        currentKey: String,
        sourceSize: Int,
    ): Decision {
        if (sourceSize <= 0) return Decision(0, Outcome.NO_SAVE)
        val key = savedKey?.trim().orEmpty()
        if (key.isEmpty()) return Decision(0, Outcome.NO_SAVE)
        if (key != currentKey) return Decision(0, Outcome.SOURCE_CHANGED)
        if (savedIndex <= 0) return Decision(0, Outcome.RESUMED)
        val last = sourceSize - 1
        // 已经回放到末尾、或文件变短了：停在末根，不能越界
        if (savedIndex >= last) return Decision(last, Outcome.CLAMPED)
        return Decision(savedIndex, Outcome.RESUMED)
    }
}
