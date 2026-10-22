package com.fxsimulator.data.ads

/**
 * 广告奖励规则（**本地测试规则**）。
 *
 * ⚠️ 重要声明：
 * 第一版**没有接入任何真实广告 SDK**。这些规则仅用于本地开发与测试，
 * 不能等同于真实广告平台的回调。接入真实 SDK 后，必须改为依据
 * 广告平台返回的「完整观看」回调发放奖励，并按平台政策执行。
 *
 * 广告奖励只增加模拟账户余额，**不计入交易盈利**，也不进入任何交易统计。
 */
object AdsRewardPolicy {
    /** 每条完整观看的激励广告奖励（虚拟 USD） */
    const val REWARD_PER_AD = 200.0
    /** 每日虚拟资金广告奖励上限 */
    const val DAILY_REWARD_CAP = 4000.0
    /** 当日观看达到该条数解锁「免插屏广告」权益 */
    const val INTERSTITIAL_FREE_THRESHOLD = 10
    /** 当日观看达到该条数获得额外游戏奖励 */
    const val BONUS_THRESHOLD = 20
    /** 额外奖励金额（虚拟 USD） */
    const val BONUS_AMOUNT = 1000.0

    /**
     * 计算「已看 [watchedToday] 条后再完整观看一条」实际可发放的奖励。
     * 达到每日上限时返回 0，即**不能发奖**。
     */
    fun nextReward(watchedToday: Int, grantedToday: Double): Double {
        if (watchedToday < 0) return 0.0
        // 只有「逐条观看奖励」受每日上限约束；
        // 观看满 20 条的里程碑额外奖励属于独立奖励，不计入该上限，
        // 否则 20 × 200 = 4000 会让额外奖励永远拿不到。
        val remaining = DAILY_REWARD_CAP - grantedToday
        val viewReward = if (remaining > 0.0) minOf(REWARD_PER_AD, remaining) else 0.0
        val bonus = if (watchedToday + 1 == BONUS_THRESHOLD) BONUS_AMOUNT else 0.0
        return viewReward + bonus
    }

    /** 当日是否已解锁免插屏广告 */
    fun isInterstitialFree(watchedToday: Int): Boolean = watchedToday >= INTERSTITIAL_FREE_THRESHOLD
}

/** 激励广告回调 */
interface RewardedAdListener {
    /** 广告成功加载并可展示 */
    fun onAdLoaded()
    /** 广告加载/展示失败——**失败不发奖** */
    fun onAdFailed(reason: String)
    /** 用户完整观看结束（真实 SDK 中由平台回调确认） */
    fun onAdCompleted()
    /** 用户中途关闭，未完整观看——**不发奖** */
    fun onAdDismissedEarly()
}

/**
 * 广告仓库接口。第一版只有两个实现：
 * - [DisabledAdsRepository]：默认实现，广告不可用、永不发奖（可安全发布）
 * - [LocalTestAdsRepository]：仅供本地开发测试，必须在界面上明确标注「本地测试，非真实广告」
 */
interface AdsRepository {
    /** 广告能力是否可用（真实 SDK 未接入时为 false） */
    val adsAvailable: Boolean
    /** 当前是否有可展示的激励广告 */
    fun canShowRewarded(): Boolean
    /** 请求一条激励广告 */
    fun requestRewardedAd(listener: RewardedAdListener)
    /** 是否为只能在开发环境使用的本地模拟实现 */
    val isLocalTestImplementation: Boolean
}

/** 默认实现：未接入广告 SDK，不展示、不发奖。 */
class DisabledAdsRepository : AdsRepository {
    override val adsAvailable: Boolean = false
    override fun canShowRewarded(): Boolean = false
    override fun requestRewardedAd(listener: RewardedAdListener) {
        listener.onAdFailed("尚未接入广告 SDK，当前版本不提供广告奖励")
    }
    override val isLocalTestImplementation: Boolean = false
}

/**
 * 本地测试实现。
 *
 * 它**不会**伪造「真实广告观看成功」：调用方必须在界面上明确告知用户
 * 这是本地测试通道，并且奖励只在测试模式开启时发放。
 * 一旦接入真实 SDK，应替换为基于平台回调的实现。
 */
class LocalTestAdsRepository(
    /** 由调用方注入：判断测试模式是否被用户显式开启 */
    private val testModeEnabled: () -> Boolean,
) : AdsRepository {
    override val adsAvailable: Boolean = true
    override fun canShowRewarded(): Boolean = testModeEnabled()
    override fun requestRewardedAd(listener: RewardedAdListener) {
        if (!testModeEnabled()) {
            listener.onAdFailed("本地测试广告通道未开启")
            return
        }
        listener.onAdLoaded()
        listener.onAdCompleted()
    }
    override val isLocalTestImplementation: Boolean = true
}
