package com.fxsimulator.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 语义色板。
 *
 * 取值来自 ui-ux-pro-max 的「Fintech/Crypto · Modern Dark (Cinema Mobile)」配方，
 * 三处**刻意偏离**配方的地方已标注，都是因为配方与它自己的反模式表冲突：
 *
 * 1. **不用紫色强调色 `#8B5CF6`** —— 配方自身的反模式写着「AI purple/pink gradients」，
 *    同一份建议里给出紫色 CTA 是自相矛盾的；金融产品的主 CTA 继续用金色。
 * 2. **不用 Orbitron 标题字体** —— 它是装饰性显示体，数字密集页面会降低可读性；
 *    价格/金额本来就该用等宽（本项目用 JetBrains Mono 风格的系统等宽）。
 * 3. **背景不用纯黑** —— 配方也明确"avoid pure #000000"，这里用深藏青 #0F172A，
 *    OLED 上不出现拖影，同时给卡片留出层次。
 *
 * 对比度（正文 4.5:1 / 次要 3:1）已实算，见 docs/UI-REFACTOR.md。
 */
object GoldLabColors {
    // ── 底层 ──
    val Background = Color(0xFF0F172A)
    val Surface = Color(0xFF222735)
    val SurfaceVariant = Color(0xFF272F42)
    val Outline = Color(0xFF334155)

    // ── 品牌 ──
    val Gold = Color(0xFFF59E0B)
    val GoldDim = Color(0xFF3B2A08)

    // ── 行情 ──
    val Up = Color(0xFF22C55E)
    val Down = Color(0xFFEF4444)

    // ── 文本 ──
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextDisabled = Color(0xFF64748B)

    // ── 状态 ──
    val Info = Color(0xFF3B82F6)
    val Warning = Color(0xFFFBBF24)
}