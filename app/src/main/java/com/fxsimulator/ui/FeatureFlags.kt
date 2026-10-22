package com.fxsimulator.ui

/**
 * 功能开关。
 *
 * 开关**只控制入口是否出现** —— 对应的实现、数据表、设置项全部原样保留。
 * 想重新上线时把常量改回 true 即可，不需要翻回去改任何实现代码。
 */
object FeatureFlags {

    /**
     * 「猜涨跌」是否可见。
     *
     * 玩法正在重做（仓位与收益在跨品种时的口径还没定），先把**入口一起**收起来：
     * 底栏标签与导航路由。只藏一个标签页是不够的 —— 已存在的路由仍能把人带进去。
     *
     * 注意：首页第一屏的「猜涨跌」入口卡已按需求**永久移除**（不再受本开关控制），
     * 现在本开关只管底栏标签与 `guess` 路由这两处入口。
     *
     * 保留的东西：`GuessController` / `GuessScreen` / `GuessRule` / `Dealer`、
     * `guess_stats` 表与历史战绩、`KEY_GUESS_LOT` 等设置项。
     */
    const val GUESS_ENABLED = true
}
