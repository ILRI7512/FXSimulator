package com.fxsimulator.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌。
 *
 * ## 为什么要有这一层
 * 之前各页面直接写 `14.dp`、`10.sp`、`RoundedCornerShape(14.dp)` —— 同一个"卡片圆角"
 * 在不同文件里出现三种数值，改一次要翻十个文件。令牌把这类决定收敛到一处。
 *
 * ## 取值依据（我自己的判断，不是设计库输出）
 * - **间距用 4 的倍数**（2/4/6/8/12/16/24）：安卓密度下 4dp 是能被 1x/1.5x/2x 整除的最小步长；
 * - **卡片圆角 12dp**：14 偏"消费级卡片"，12 更像行情终端的区块；
 * - **标题 12.5sp + 0.4sp 字距**：小字加字距在深色底上更清晰，不靠加粗硬顶；
 * - **数字一律等宽**：金额/价格跳动时不会左右抖。
 *
 * 只放"结构性"令牌（尺寸/字重/字距）。颜色仍走 [GoldLabColors]，
 * 因为它已经是一套命名良好的语义色板，再包一层只会多一次跳转。
 */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 6.dp
    val md = 8.dp
    val lg = 12.dp
    val xl = 16.dp
    val xxl = 24.dp
}

object Radius {
    /** 面板/卡片 */
    val card = 12.dp
    /** 按钮与标签块 */
    val chip = 8.dp
    /** 小徽章 */
    val pill = 6.dp
    /** 提示条 */
    val notice = 10.dp
}

object TypeScale {
    val sectionTitle = 12.5.sp
    val sectionTitleSpacing = 0.4.sp
    val body = 12.sp
    val caption = 10.sp
    val micro = 9.sp
    val number = 16.sp
    val numberLarge = 20.sp
}

object Size {
    /** 分区标题前的强调竖条 */
    val accentBarWidth = 3.dp
    val accentBarHeight = 13.dp
    /** 卡片内容内边距 */
    val cardPadding = 14.dp
    /** 列表行垂直内边距 */
    val rowPadding = 3.dp
}
