package com.fxsimulator.di

import android.content.Context
import com.fxsimulator.data.ads.AdsRepository
import com.fxsimulator.data.ads.DisabledAdsRepository
import com.fxsimulator.data.ads.LocalTestAdsRepository
import com.fxsimulator.data.db.GoldLabDatabase
import com.fxsimulator.data.repository.AccountRepository
import com.fxsimulator.data.repository.AdStateRepository
import com.fxsimulator.data.repository.GuessStatsRepository
import com.fxsimulator.data.repository.ReplayStateRepository
import com.fxsimulator.data.repository.SettingsRepository


/**
 * 手工依赖注入容器。
 *
 * 数据库与仓库都是**单例**：整个应用只创建一次 [GoldLabDatabase]，
 * 不会在每次 UI 刷新时重复创建（这是常见的性能陷阱）。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val database: GoldLabDatabase by lazy { GoldLabDatabase(appContext) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(database) }
    val accountRepository: AccountRepository by lazy { AccountRepository(database) }
    val replayStateRepository: ReplayStateRepository by lazy { ReplayStateRepository(database) }
    val adStateRepository: AdStateRepository by lazy { AdStateRepository(database) }
    val guessStatsRepository: GuessStatsRepository by lazy { GuessStatsRepository(database) }

    /** 未接入真实广告 SDK 时的默认实现：广告不可用、永不发奖 */
    private val disabledAds = DisabledAdsRepository()

    /** 仅用于本地开发测试的模拟广告通道 */
    private val localTestAds = LocalTestAdsRepository {
        settingsRepository.getBool(SettingsRepository.KEY_ADS_TEST_MODE, false)
    }

    /**
     * 当前生效的广告实现。
     * 只有用户在设置里显式打开「本地测试广告通道」时才会启用模拟实现，
     * 且界面会明确标注它不是真实广告。
     */
    fun ads(): AdsRepository {
        val testOn = settingsRepository.getBool(SettingsRepository.KEY_ADS_TEST_MODE, false)
        return if (testOn) localTestAds else disabledAds
    }
}
