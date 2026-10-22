package com.fxsimulator.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fxsimulator.data.market.BarSource
import com.fxsimulator.data.repository.AdDayState
import com.fxsimulator.data.repository.ReplayProgress
import com.fxsimulator.data.repository.SettingsRepository
import com.fxsimulator.di.AppContainer
import com.fxsimulator.domain.model.Account
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.model.CandleAggregator
import com.fxsimulator.domain.model.CloseReason
import com.fxsimulator.domain.model.SymbolSpec
import com.fxsimulator.domain.model.SymbolSpecs
import com.fxsimulator.domain.model.Timeframe
import com.fxsimulator.domain.model.TradeDirection
import com.fxsimulator.domain.model.TradeRecord
import com.fxsimulator.domain.model.TradeStats
import com.fxsimulator.domain.risk.RiskEngine
import com.fxsimulator.data.market.BundledMarketData
import com.fxsimulator.engine.ReplayEngine
import com.fxsimulator.engine.ReplayResume
import com.fxsimulator.engine.SourceKeys
import com.fxsimulator.engine.StartupMonth
import com.fxsimulator.engine.ReplayState
import com.fxsimulator.engine.TradingEngine
import com.fxsimulator.indicators.IndicatorConfig
import com.fxsimulator.indicators.IndicatorConfigCodec
import com.fxsimulator.indicators.IndicatorInstance
import com.fxsimulator.stats.PerformanceAnalyzer
import com.fxsimulator.ui.chart.IndicatorMapper
import com.fxsimulator.ui.chart.OverlaySeries
import com.fxsimulator.ui.chart.SubPaneSpec
import com.fxsimulator.util.GoldLabLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** 一次性提示消息 */
data class UiMessage(val text: String, val id: Long, val isError: Boolean = false)

/** 完整界面状态 */
data class GameUiState(
    val loading: Boolean = true,
    val account: Account = Account(),
    val spec: SymbolSpec = SymbolSpec(),
    val candle: Candle? = null,
    val replay: ReplayState = ReplayState(),
    val positions: List<TradeRecord> = emptyList(),
    val history: List<TradeRecord> = emptyList(),
    val stats: TradeStats = TradeStats(),
    /** 用户自选的指标配置（有序） */
    val indicatorConfig: IndicatorConfig = IndicatorConfig(),
    /** 由 [indicatorConfig] 算出的主图叠加线 */
    val overlays: List<OverlaySeries> = emptyList(),
    /** 由 [indicatorConfig] 算出的副图窗格 */
    val subPanes: List<SubPaneSpec> = emptyList(),
    /** 参与指标计算的 K 线根数；不足时界面提示「数据不足」而不是显示假数值 */
    val indicatorBarsAvailable: Int = 0,
    /** 当前行情源的存档（没有则为 null） */
    val bookmark: ReplayProgress? = null,
    /** 随包内置的历史行情月份（yyyy-MM） */
    val bundledMonths: List<String> = emptyList(),
    val bars: List<Candle> = emptyList(),
    val sourceName: String = "",
    /** 当前图表周期 */
    val timeframe: Timeframe = Timeframe.M1,
    /** 切换周期后正在重建图表窗口 */
    val barsLoading: Boolean = false,
    val adsAvailable: Boolean = false,
    val adsLocalTest: Boolean = false,
    val adDay: AdDayState = AdDayState("", 0, 0.0),
    val message: UiMessage? = null,
    val stopOut: Boolean = false,
    val marginLevelText: String = "—",
    val defaultLot: Double = 0.10,
    val riskMaxDailyLoss: Double = 0.0,
    val riskMaxConsecutive: Int = 0,
    val dailyRealizedPnl: Double = 0.0,
    val consecutiveLosses: Int = 0,
    val showSlTp: Boolean = true,
    /** 阳线是否画成空心（false = 实心，默认） */
    val hollowUpCandles: Boolean = false,
)

/**
 * 游戏主 ViewModel。
 *
 * 职责：把「回放引擎 + 交易引擎 + 持久化 + 统计」编排在一起，
 * 向 Compose 暴露单一的不可变状态流。所有耗时工作都离开主线程。
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer(app)
    private val settings = container.settingsRepository
    private val accounts = container.accountRepository
    private val replayRepo = container.replayStateRepository
    private val adRepo = container.adStateRepository

    /**
     * 当前品种可用的内置月份。
     *
     * **必须跟着品种走**，不能按默认品种算一次就定死：玩家上次停留在外汇时，
     * 一份按黄金算出来的月份列表会拿去加载 `market/XAGUSD/2024-03.glb1` ——
     * 那个月份在外汇目录里根本不存在，启动直接失败。
     *
     * 在 [initialLoad] 里按当前品种重算，切换品种时见 [switchSymbol]。
     */
    private var bundledMonths: List<String> = emptyList()

    private val replayEngine = ReplayEngine(viewModelScope)
    private var tradingEngine: TradingEngine

    private val _ui = MutableStateFlow(GameUiState())
    val ui: StateFlow<GameUiState> = _ui.asStateFlow()

    private var guessController: GuessController? = null

    /**
     * 「猜涨跌」控制器。
     *
     * **懒创建**：从没打开过这个页面就不会去解析内置行情（那要读一个 2 MB 的 CSV）。
     * 它与回放 / 模拟交易那一套完全隔离：独立权益、独立手数、独立存档。
     */
    /** 玩法（事件合约 + 期权）的账本：独立 USDT 权益，与交易账户互不影响。 */
    val games = com.fxsimulator.domain.games.GamesEngine()

    val guess: GuessController
        get() = guessController ?: GuessController(
            app = getApplication(),
            scope = viewModelScope,
            container = container,
            specProvider = { tradingEngine.spec },
        ).also { guessController = it }

    /** 最近用于图表与指标计算的 K 线（滚动窗口，避免每次全量重算） */
    /** 当前周期下用于图表与指标的 K 线（M1 直接取自行情，其他周期由 M1 聚合） */
    private val displayBars = ArrayDeque<Candle>()

    /** 当前图表周期 */
    private var currentTimeframe: Timeframe = Timeframe.M1

    /** 窗口重建任务：切换周期时先取消上一个，避免并发重建互相覆盖 */
    private var rebuildJob: Job? = null

    /** 是否正在重建窗口 */
    private var windowLoading = false
    private val msgId = AtomicLong(0)
    private var barsSincePersist = 0
    private var currentSource: BarSource? = null

    /**
     * 当前行情源的**稳定标识**（file:… / uri:… / demo）。
     * 存档的身份用它，界面显示用 sourceName —— 两者绝不能混用。
     */
    private var currentSourceKey: String = ""

    /** 已落库的存档状态：用来跳过「内容没变」的重复写库 */
    private var persistedKey: String? = null
    private var persistedIndex: Int = -1
    private var persistedSpeed: Int = -1

    /** 当前来源的存档缓存。publishState 每根 K 线都会跑，不能每次都查库。 */
    private var cachedBookmarkKey: String? = null
    private var cachedBookmark: ReplayProgress? = null

    init {
        val spec = settings.loadSymbolSpec()
        tradingEngine = TradingEngine(spec, buildRiskEngine(), settings.loadInitialBalance())
        replayEngine.onBar = { candle -> onNewBar(candle) }
        replayEngine.onFinished = { showMessage("历史行情回放已到末尾") }
        replayEngine.onError = { message ->
            // 回放期间出错不闪退：记录、提示、停下
            GoldLabLog.e(TAG, "回放错误：" + message)
            showMessage(message, isError = true)
            publishState()
        }
        GoldLabLog.i(TAG, "GameViewModel 初始化：spec=" + spec.symbol + " 杠杆=" + spec.leverage)
        restoreChartOptions()
        viewModelScope.launch { withContext(Dispatchers.IO) { initialLoad() } }
    }

    /**
     * 恢复「图表显示」开关。
     *
     * 这些开关此前只写不读：设置里改了、重启后全部回落到默认值，
     * 用户会以为开关没生效。这里在初始化时读回。
     */
    private fun restoreChartOptions() {
        val s = _ui.value
        // 指标配置用「文本 + 容错解码」：未知指标、坏数字、缺参数都不会让整套配置失效
        val cfg = IndicatorConfigCodec.decode(settings.getString(SettingsRepository.KEY_INDICATORS, ""))
        _ui.value = s.copy(
            indicatorConfig = cfg,
            showSlTp = settings.getBool(SettingsRepository.KEY_SHOW_SLTP, s.showSlTp),
            hollowUpCandles = settings.getBool(SettingsRepository.KEY_HOLLOW_UP, s.hollowUpCandles),
        )
    }

    private fun buildRiskEngine(): RiskEngine = RiskEngine(
        minStopDistance = 0.10,
        maxDailyLoss = settings.getDouble(SettingsRepository.KEY_RISK_MAX_DAILY_LOSS, 0.0),
        maxConsecutiveLosses = settings.getInt(SettingsRepository.KEY_RISK_MAX_CONSECUTIVE, 0),
        stopOutLevelPercent = settings.getDouble(SettingsRepository.KEY_RISK_STOP_OUT, 50.0),
    )

    /**
     * 启动时载入哪个月份。
     *
     * 优先上次玩的那个 —— 这才是「存档」该有的样子；它不在内置列表里（例如换了版本）
     * 才退回第一个。刻意不做随机：图表每次启动都换一段行情会让人以为数据出错了。
     */
    private fun pickStartupMonth(): String =
        StartupMonth.pick(settings.getString(SettingsRepository.KEY_LAST_MONTH, ""), bundledMonths)

    // ------------------------------------------------------------ 初始化

    private suspend fun initialLoad() {
        val spec = settings.loadSymbolSpec()
        // 月份列表必须先按**当前品种**重算。构造时那份是按默认品种（黄金）算的，
        // 玩家上次停在外汇时，它会指向外汇目录里不存在的月份，启动直接失败；
        // 早先更糟：那时会静默退回黄金数据当外汇用，钱全算错。
        bundledMonths = BundledMarketData.availableMonths(getApplication(), spec.symbol)
        val persisted = accounts.loadAccount()
        val initialBalance = if (persisted.exists) persisted.initialBalance else settings.loadInitialBalance()

        tradingEngine = TradingEngine(spec, buildRiskEngine(), initialBalance)
        if (persisted.exists) {
            // 关键：已有账户时绝不重置，恢复余额 + 持仓 + 历史
            val open = accounts.loadOpenPositions()
            val hist = if (histRetained()) accounts.loadHistory(2000) else emptyList()
            tradingEngine.restore(persisted.balance, persisted.initialBalance, open, hist, persisted.lastBid)
        } else {
            accounts.saveAccount(tradingEngine.balance, tradingEngine.initialBalance, 0.0)
        }

        // 行情源：只有内置月份这一种。没有导入、没有本地目录、没有 DEMO ——
        // 打开应用就有 33 个月的真实黄金行情可玩，不需要玩家先准备任何文件。
        val month = pickStartupMonth()
        val loadedSource = if (month.isEmpty()) {
            null
        } else {
            BundledMarketData.load(getApplication(), month, spec.symbol)
        }
        if (loadedSource == null || loadedSource.size == 0) {
            // 内置行情是打包资产，正常情况不可能缺失。真缺了要明说，
            // 不能静默拿一份空数据让玩家对着一片空白发呆。
            GoldLabLog.e(TAG, "内置行情不可用：月份=" + month + " 可选=" + bundledMonths)
            withContext(Dispatchers.Main) {
                _ui.value = _ui.value.copy(loading = false)
                showMessage("内置行情不可用（月份 " + month + "），请重新安装应用", isError = true)
            }
            return
        }
        currentSource = loadedSource
        currentSourceKey = SourceKeys.bundled(month)
        settings.putString(SettingsRepository.KEY_LAST_MONTH, month)
        GoldLabLog.i(TAG, "行情源就绪：" + loadedSource.sourceName + " · " + loadedSource.size + " 根")

        // 恢复上次选择的图表周期
        currentTimeframe = Timeframe.fromName(
            settings.getString(SettingsRepository.KEY_TIMEFRAME, Timeframe.M1.name),
        )

        withContext(Dispatchers.Main) {
            // 来源身份就是 "bundled:<月份>"，与 useBundledMonth 写存档时用的键**完全一致**。
            //
            // 之前这里有两个 bug 叠在一起：
            //   1) 初始载入只用 file:/uri: 造键，内置月份压根没有恢复路径 —— 重启必丢；
            //   2) useBundledMonth 无条件 load(src, 0) 后立刻 force 落库，
            //      等于「一进这个月就把它的存档抹成 0」—— 就算能恢复，读到的也是 0。
            val saved = replayRepo.load(currentSourceKey)
            val decision = ReplayResume.decide(
                savedKey = currentSourceKey.takeIf { saved != null },
                savedIndex = saved?.barIndex ?: 0,
                currentKey = currentSourceKey,
                sourceSize = loadedSource.size,
            )

            replayEngine.load(loadedSource, decision.index)
            replayEngine.setSpeed(
                if (saved != null && saved.speed > 0) saved.speed else settings.loadPlaySpeed(),
            )
            rebuildWindow(replayEngine.state.value.index, "初始载入")
            // 立刻落一次基准存档：即使一根没走就退出，下次也知道这个源上次停在哪
            persistReplay(force = true)
            publishState(loading = false)
            // 首次启动没有存档就没什么可说的，不弹；有存档时必须明确告诉用户恢复了
            if (decision.outcome != ReplayResume.Outcome.NO_SAVE) {
                showMessage(resumeMessage(decision, loadedSource))
            }
        }
    }

    /** 载入完成时告诉用户到底恢复了没有 —— 静默恢复和静默失败一样糟 */
    private fun resumeMessage(decision: ReplayResume.Decision, src: BarSource): String {
        val head = "已载入 " + src.sourceName
        return when (decision.outcome) {
            ReplayResume.Outcome.RESUMED ->
                if (decision.index > 0) head + "；已从上次位置继续（第 " + (decision.index + 1) + " 根）" else head
            ReplayResume.Outcome.CLAMPED ->
                head + "；存档位置超出当前数据范围，已停在末根"
            ReplayResume.Outcome.SOURCE_CHANGED ->
                head + "；这是另一个数据源，从头开始"
            ReplayResume.Outcome.NO_SAVE -> head
        }
    }

    private fun histRetained(): Boolean = true

    // ------------------------------------------------------------ 状态发布

    private fun publishState(loading: Boolean = false) {
        val engine = tradingEngine
        val rs = replayEngine.state.value
        val acct = engine.account()
        val closed = engine.closedTrades
        val dayKey = currentDayKey()
        val stats = PerformanceAnalyzer.analyze(closed, engine.initialBalance)
        val recentClosed = closed.sortedByDescending { it.closeTimeMillis ?: it.openTimeMillis }
        val ind = computeIndicatorSeries()

        _ui.value = _ui.value.copy(
            loading = loading,
            account = acct,
            spec = engine.spec,
            candle = rs.candle,
            replay = rs,
            positions = engine.openPositions,
            history = (recentClosed + engine.history.filter { it.status == com.fxsimulator.domain.model.OrderStatus.REJECTED })
                .take(300),
            stats = stats,
            indicatorConfig = _ui.value.indicatorConfig,
            overlays = ind.overlays,
            subPanes = ind.panes,
            indicatorBarsAvailable = ind.bars,
            bookmark = cachedBookmark,
            bundledMonths = bundledMonths,
            bars = displayBars.toList(),
            timeframe = currentTimeframe,
            barsLoading = windowLoading,
            sourceName = rs.sourceName,
            stopOut = buildRiskEngine().isStopOut(acct),
            marginLevelText = buildRiskEngine().marginLevelText(acct),
            dailyRealizedPnl = PerformanceAnalyzer.dailyRealizedPnl(closed, dayKey),
            consecutiveLosses = trailingConsecutiveLosses(closed),
            adDay = adRepo.load(dayKey),
            adsAvailable = container.ads().adsAvailable,
            adsLocalTest = container.ads().isLocalTestImplementation,
        )
    }

    private fun trailingConsecutiveLosses(closed: List<TradeRecord>): Int {
        val sorted = closed.sortedByDescending { it.closeTimeMillis ?: it.openTimeMillis }
        var n = 0
        for (t in sorted) {
            if (t.netProfit < 0.0) n++ else break
        }
        return n
    }

    private fun currentDayKey(): String {
        val t = replayEngine.state.value.candle?.timeMillis
        return if (t != null && t > 0) PerformanceAnalyzer.dayKeyOf(t)
        else PerformanceAnalyzer.dayKeyOf(System.currentTimeMillis())
    }

    /** 指标计算结果：(主图叠加线, 副图窗格, 参与计算的 K 线根数) */
    private data class IndicatorBundle(
        val overlays: List<OverlaySeries>,
        val panes: List<SubPaneSpec>,
        val bars: Int,
    )

    /**
     * 按**用户当前配置**计算指标。
     *
     * 只算启用的那几个：17 个全算是纯浪费，而用户通常只开 2~4 个。
     * 指标周期跟随图表周期（MT5 行为）：M15 上的 EMA(20) 就是 20 根 M15。
     */
    private fun computeIndicatorSeries(): IndicatorBundle {
        val bars = displayBars.toList()
        if (bars.size < 3) return IndicatorBundle(emptyList(), emptyList(), bars.size)
        val cfg = _ui.value.indicatorConfig
        return IndicatorBundle(
            overlays = IndicatorMapper.overlays(cfg, bars),
            panes = IndicatorMapper.subPanes(cfg, bars),
            bars = bars.size,
        )
    }

    /**
     * 重建图表窗口。
     *
     * 数据源始终是 M1（第一版只有 M1 历史行情），其他周期由 M1 现场聚合；
     * 需要回扫的 M1 根数 = 400 × 周期分钟数，因此 D1 这种周期要被上限截断
     * （见 [MAX_M1_SCAN]），否则在手机上读几十万行会明显卡顿。
     */
    private fun rebuildWindow(endIndex: Int, reason: String) {
        val tf = currentTimeframe
        rebuildJob?.cancel()
        windowLoading = true
        _ui.value = _ui.value.copy(barsLoading = true)
        rebuildJob = viewModelScope.launch {
            val built = withContext(Dispatchers.IO) { buildWindow(tf, endIndex) }
            displayBars.clear()
            displayBars.addAll(built)
            windowLoading = false
            GoldLabLog.i(
                TAG,
                "构建 " + tf.label + " 窗口：" + built.size + " 根（" + reason + "）",
            )
            publishState()
        }
    }

    /** 从 M1 源聚合出目标周期的窗口，必须在 IO 线程调用 */
    private fun buildWindow(tf: Timeframe, endIndex: Int): List<Candle> {
        val src = currentSource ?: return emptyList()
        if (src.size == 0) return emptyList()
        val end = endIndex.coerceIn(0, src.size - 1)
        val want = (WINDOW.toLong() * tf.minutes)
            .coerceAtMost(MAX_M1_SCAN.toLong())
            .toInt()
        val from = maxOf(0, end - want + 1)
        val m1 = ArrayList<Candle>(end - from + 1)
        for (i in from..end) {
            src.barAt(i)?.let { m1.add(it) }
        }
        val agg = CandleAggregator.aggregate(m1, tf)
        return if (agg.size > WINDOW) agg.subList(agg.size - WINDOW, agg.size) else agg
    }

    /**
     * 切换图表周期：M1 / M5 / M15 / M30 / H1 / H4 / D1。
     * 回放位置不变，只是换一个视角看同一段行情。
     */
    fun setTimeframe(tf: Timeframe) {
        if (tf == currentTimeframe && displayBars.isNotEmpty()) return
        currentTimeframe = tf
        settings.putString(SettingsRepository.KEY_TIMEFRAME, tf.name)
        rebuildWindow(replayEngine.state.value.index, "切换周期 " + tf.label)
    }

    // ------------------------------------------------------------ 回放控制

    fun play() = replayEngine.play()
    fun pause() {
        replayEngine.pause()
        persistReplay(force = true)
        publishState()
    }
    fun togglePlay() {
        if (replayEngine.state.value.playing) pause() else replayEngine.play()
        publishState()
    }
    fun stepForward() {
        replayEngine.pause()
        replayEngine.stepForward()
        publishState()
    }
    fun resetReplay() {
        replayEngine.reset(0)
        rebuildWindow(0, "重置回放")
        persistReplay(force = true)
        publishState()
    }
    fun setSpeed(speed: Int) {
        replayEngine.setSpeed(speed)
        persistReplay(force = true)
        publishState()
    }
    fun seekToTime(millis: Long) {
        val idx = replayEngine.indexForTime(millis)
        replayEngine.seek(idx)
        rebuildWindow(idx, "按日期跳转")
        persistReplay(force = true)
        publishState()
        showMessage("已跳转到 " + com.fxsimulator.ui.format.Formatters.dateTime(millis))
    }
    fun seekToIndex(index: Int) {
        replayEngine.seek(index)
        rebuildWindow(index, "跳转索引")
        publishState()
    }

    // ------------------------------------------------------------ 回放存档

    private fun resetPersistedMarker() {
        persistedIndex = -1
        persistedSpeed = -1
    }

    /** 从存档位置继续（不换数据源） */
    fun resumeFromBookmark() {
        val src = currentSource ?: return
        val bm = cachedBookmark
        if (bm == null) {
            showMessage("这个数据源还没有存档", isError = true)
            return
        }
        if (bm.barIndex <= 0) {
            showMessage("存档就在开头（第 1 根），无需继续")
            return
        }
        replayEngine.pause()
        replayEngine.load(src, bm.barIndex)
        rebuildWindow(replayEngine.state.value.index, "从存档继续")
        publishState()
        showMessage("已回到存档位置：第 " + (bm.barIndex + 1) + " 根")
    }

    /** 删除当前数据源的存档 */
    fun clearBookmark() {
        val k = currentSourceKey
        if (k.isNotBlank()) replayRepo.clear(k)
        persistedKey = null
        resetPersistedMarker()
        cachedBookmarkKey = k
        cachedBookmark = null
        publishState()
        showMessage("已删除这个数据源的存档")
    }



    /**
     * 载入一个内置月份作为**自由探索**的数据源（无挑战、无负债，随便交易）。
     *
     * 这是「不用导入任何东西就能玩」的关键：玩家打开应用就有 33 个月的行情可看。
     */
    fun useBundledMonth(month: String) {
        val src = BundledMarketData.load(getApplication(), month, tradingEngine.spec.symbol)
        if (src == null || src.size == 0) {
            showMessage("没有 " + month + " 的内置行情", isError = true)
            return
        }

        val key = SourceKeys.bundled(month)
        replayEngine.pause()
        currentSource = src
        currentSourceKey = key
        settings.putString(SettingsRepository.KEY_LAST_MONTH, month)

        // 恢复**这个月自己的**存档。旧代码这里写死了 load(src, 0)，
        // 再紧跟一次 force 落库，于是每次切到这个月都从第 0 根开始、
        // 还顺手把原有的存档覆盖成 0 —— 存档功能等于不存在。
        val saved = replayRepo.load(key)
        val start = (saved?.barIndex ?: 0).coerceIn(0, maxOf(0, src.size - 1))

        resetPersistedMarker()
        tradingEngine.reset(tradingEngine.balance)
        replayEngine.load(src, start)
        if (saved != null && saved.speed > 0) replayEngine.setSpeed(saved.speed)
        rebuildWindow(start, "载入内置 " + month)
        cachedBookmarkKey = key
        cachedBookmark = saved
        persistReplay(force = true)
        publishState()
        showMessage(
            "已载入 " + BundledMarketData.monthLabel(month) + " · " + src.size + " 根 M1" +
                (if (start > 0) "，从上次位置继续（第 " + (start + 1) + " 根）" else ""),
        )
    }


    /** 应用切到后台：暂停回放，且不会重复启动第二个任务 */
    fun onAppBackground() {
        if (replayEngine.state.value.playing) {
            replayEngine.pause()
            publishState()
        }
        // 不管在不在播放都要落库：用户可能停在某一根看行情，然后直接切走
        persistReplay(force = true)
    }

    // ------------------------------------------------------------ 每根 K 线

    private fun onNewBar(candle: Candle) {
        // 把新的 M1 增量并入当前周期的最后一根聚合 K 线
        val merged = CandleAggregator.mergeInto(displayBars.lastOrNull(), candle, currentTimeframe)
        if (displayBars.isEmpty() || displayBars.last().timeMillis != merged.timeMillis) {
            displayBars.addLast(merged)
            while (displayBars.size > WINDOW) displayBars.removeFirst()
        } else {
            displayBars[displayBars.lastIndex] = merged
        }

        val results = tradingEngine.onCandle(candle)

        barsSincePersist++
        if (results.isNotEmpty()) {
            persistTradesAndAccount(results.mapNotNull { it.record })
        }
        // 存档**不能**挂在 else 分支上：原来一旦这根 K 线触发了成交，
        // 回放进度那次落库就被整根跳过，成交频繁时进度几乎存不下去。
        if (barsSincePersist >= PERSIST_EVERY) {
            barsSincePersist = 0
            persistReplay(force = false)
        }
        publishState()
        results.forEach { showMessage(it.message) }
    }


    // ------------------------------------------------------------ 交易

    fun openOrder(
        direction: TradeDirection,
        volume: Double,
        stopLoss: Double?,
        takeProfit: Double?,
    ) {
        val bid = replayEngine.state.value.candle?.close ?: tradingEngine.lastBid
        val time = replayEngine.state.value.candle?.timeMillis ?: System.currentTimeMillis()
        val closed = tradingEngine.closedTrades
        val dayKey = currentDayKey()
        val r = tradingEngine.marketOrder(
            direction = direction,
            volume = volume,
            bid = bid,
            timeMillis = time,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            dailyRealizedPnl = PerformanceAnalyzer.dailyRealizedPnl(closed, dayKey),
            consecutiveLosses = trailingConsecutiveLosses(closed),
        )
        r.record?.let { persistTradeOnly(it) }
        GoldLabLog.i(TAG, "下单 " + direction.label + " " + volume + " 手：" + r.message)
        publishState()
        showMessage(r.message, isError = !r.success)
    }

    fun closePosition(orderId: Long) {
        val bid = replayEngine.state.value.candle?.close ?: tradingEngine.lastBid
        val time = replayEngine.state.value.candle?.timeMillis ?: System.currentTimeMillis()
        val r = tradingEngine.closePosition(orderId, bid, time, CloseReason.MANUAL)
        r.record?.let { persistTradeOnly(it) }
        persistAccount()
        publishState()
        showMessage(r.message, isError = !r.success)
    }

    fun closeAll() {
        val bid = replayEngine.state.value.candle?.close ?: tradingEngine.lastBid
        val time = replayEngine.state.value.candle?.timeMillis ?: System.currentTimeMillis()
        val rs = tradingEngine.closeAll(bid, time, CloseReason.MANUAL)
        rs.forEach { it.record?.let { rec -> persistTradeOnly(rec) } }
        persistAccount()
        publishState()
        showMessage(if (rs.isEmpty()) "当前没有持仓" else "已平掉 " + rs.size + " 笔持仓")
    }

    fun modifyPosition(orderId: Long, stopLoss: Double?, takeProfit: Double?) {
        val r = tradingEngine.modifyPosition(orderId, stopLoss, takeProfit)
        r.record?.let { persistTradeOnly(it) }
        publishState()
        showMessage(r.message, isError = !r.success)
    }

    // ------------------------------------------------------------ 账户

    /**
     * 重置模拟账户。
     * @param clearHistory 是否同时清空交易历史——由用户在确认对话框中明确选择
     */
    fun resetAccount(clearHistory: Boolean) {
        val initial = settings.loadInitialBalance()
        if (clearHistory) {
            tradingEngine.reset(initial)
            accounts.inTransaction { db ->
                db.delete("trades", null, null)
                accounts.saveAccount(tradingEngine.balance, initial, 0.0)
            }
        } else {
            tradingEngine.resetBalanceKeepHistory(initial)
            accounts.inTransaction { db ->
                db.delete("trades", "status = ?", arrayOf(com.fxsimulator.domain.model.OrderStatus.OPEN.name))
                accounts.saveAccount(tradingEngine.balance, initial, 0.0)
            }
        }
        persistAccount()
        GoldLabLog.i(TAG, "账户已重置，清空历史=" + clearHistory + "，初始资金=" + initial)
        publishState()
        showMessage(if (clearHistory) "账户已重置，交易历史已清空" else "账户资金已重置，交易历史保留")
    }

    // ------------------------------------------------------------ 广告

    /**
     * 请求一条激励广告。
     * 未接入真实 SDK 时返回失败且**不发奖**；只有在显式开启本地测试通道时才会走到发奖逻辑。
     */
    fun watchRewardedAd() {
        val ads = container.ads()
        ads.requestRewardedAd(object : com.fxsimulator.data.ads.RewardedAdListener {
            override fun onAdLoaded() { }
            override fun onAdFailed(reason: String) {
                showMessage(reason, isError = true)
            }
            override fun onAdCompleted() {
                grantAdReward()
            }
            override fun onAdDismissedEarly() {
                showMessage("未完整观看广告，不发放奖励", isError = true)
            }
        })
    }

    private fun grantAdReward() {
        val dayKey = currentDayKey()
        val before = adRepo.load(dayKey)
        val after = adRepo.recordCompletedView(dayKey, System.currentTimeMillis())
        val delta = after.granted - before.granted
        if (delta > 0.0) {
            // 奖励只增加余额，**不写入 trades**，因此不计入任何交易统计
            tradingEngine.creditReward(delta)
            persistAccount()
        }
        publishState()
        showMessage(
            if (delta > 0.0) {
                "本地测试广告奖励 +" + String.format("%.0f", delta) +
                    " USD（虚拟资金，不计入交易盈利）"
            } else {
                "已达每日广告奖励上限（" + AdsCap() + " USD），本次不发奖"
            },
            isError = delta <= 0.0,
        )
    }

    private fun AdsCap(): String =
        String.format("%.0f", com.fxsimulator.data.ads.AdsRewardPolicy.DAILY_REWARD_CAP)

    fun setAdsTestMode(enabled: Boolean) {
        settings.putBool(SettingsRepository.KEY_ADS_TEST_MODE, enabled)
        publishState()
        showMessage(if (enabled) "已开启本地测试广告通道（非真实广告）" else "已关闭本地测试广告通道")
    }

    // ------------------------------------------------------------ 设置

    fun updateSpec(spec: SymbolSpec) {
        val lot = tradingEngine.openPositions.isNotEmpty()
        settings.saveSymbolSpec(spec)
        tradingEngine.spec = spec
        tradingEngine.riskEngine = buildRiskEngine()
        publishState()
        if (lot) showMessage("合约规格已更新；已存在的持仓按原开仓价计算，不受影响")
        else showMessage("模拟合约规格已更新")
    }

    /**
     * 切换交易品种。
     *
     * 与 [updateSpec] 的区别：只换 spec 是不够的 —— **价格数据必须一起换**，
     * 否则会出现"USDJPY 的规格 + 黄金的价格"这种算得出来却毫无意义的状态。
     * 这里复用 [useBundledMonth] 走完整的数据重载路径。
     *
     * 限制：有持仓时不允许切换 —— 不同品种的价格不可比，混在一个账户里盈亏没有意义。
     */
    fun switchSymbol(symbol: String) {
        val target = symbol.uppercase()
        if (target == tradingEngine.spec.symbol.uppercase()) return
        if (tradingEngine.openPositions.isNotEmpty()) {
            showMessage("请先平掉所有持仓，再切换品种", isError = true)
            return
        }
        val spec = SymbolSpecs.of(target)
        val months = BundledMarketData.availableMonths(getApplication(), target)
        if (months.isEmpty()) {
            showMessage(SymbolSpecs.displayName(target) + " 没有内置行情，请重新安装应用", isError = true)
            return
        }
        settings.saveSymbolSpec(spec)
        tradingEngine.spec = spec
        tradingEngine.riskEngine = buildRiskEngine()
        // 月份列表随品种变化：数据页直接读它，必须一起换掉
        bundledMonths = months
        // 用该品种最新的一个月起手
        useBundledMonth(months.last())
        publishState()
        showMessage("已切换到 " + SymbolSpecs.displayName(target))
    }

    fun updateInitialBalance(value: Double) {
        settings.putDouble(SettingsRepository.KEY_INITIAL_BALANCE, value)
        publishState()
        showMessage("初始资金已设为 " + String.format("%.0f", value) + " USD（下次重置账户时生效）")
    }

    fun updateDefaultLot(lot: Double) {
        settings.putDouble(SettingsRepository.KEY_DEFAULT_LOT, lot)
        _ui.value = _ui.value.copy(defaultLot = lot)
    }

    fun updateRisk(maxDailyLoss: Double, maxConsecutive: Int, stopOut: Double) {
        settings.putDouble(SettingsRepository.KEY_RISK_MAX_DAILY_LOSS, maxDailyLoss)
        settings.putInt(SettingsRepository.KEY_RISK_MAX_CONSECUTIVE, maxConsecutive)
        settings.putDouble(SettingsRepository.KEY_RISK_STOP_OUT, stopOut)
        tradingEngine.riskEngine = buildRiskEngine()
        publishState()
        showMessage("风控参数已更新")
    }

    /**
     * 更新「图表显示」开关。参数都有默认值（取当前状态），
     * 调用方只传要改的那一项即可，避免 5 个布尔位置参数互串。
     */
    fun updateChartOptions(
        showSlTp: Boolean = _ui.value.showSlTp,
        hollowUpCandles: Boolean = _ui.value.hollowUpCandles,
    ) {
        settings.putBool(SettingsRepository.KEY_SHOW_SLTP, showSlTp)
        settings.putBool(SettingsRepository.KEY_HOLLOW_UP, hollowUpCandles)
        _ui.value = _ui.value.copy(showSlTp = showSlTp, hollowUpCandles = hollowUpCandles)
    }

    // ------------------------------------------------------------ 指标配置

    /**
     * 保存用户自选的指标配置并立刻重算。
     *
     * 写库放到 IO 线程，但**界面状态立即更新** —— 调参数要即时看到效果，
     * 不能等一次磁盘写入。重算走 [publishState]，指标一起刷新。
     */
    fun updateIndicatorConfig(config: IndicatorConfig) {
        val normalized = config.normalized()
        _ui.value = _ui.value.copy(indicatorConfig = normalized)
        settings.putString(SettingsRepository.KEY_INDICATORS, IndicatorConfigCodec.encode(normalized))
        publishState()
    }

    fun addIndicator(defId: String) {
        val def = com.fxsimulator.indicators.IndicatorCatalog.byId(defId) ?: return
        updateIndicatorConfig(
            _ui.value.indicatorConfig.withAdded(IndicatorInstance(def.id, def.defaultParams())),
        )
        showMessage("已添加 " + def.name)
    }

    fun removeIndicator(index: Int) {
        updateIndicatorConfig(_ui.value.indicatorConfig.withRemovedAt(index))
    }

    fun replaceIndicator(index: Int, inst: IndicatorInstance) {
        updateIndicatorConfig(_ui.value.indicatorConfig.withReplacedAt(index, inst))
    }

    fun moveIndicator(index: Int, delta: Int) {
        updateIndicatorConfig(_ui.value.indicatorConfig.withMoved(index, delta))
    }

    /** 恢复成出厂默认（EMA20/50 + RSI14 + ATR14） */
    fun resetIndicators() {
        updateIndicatorConfig(IndicatorConfig(com.fxsimulator.indicators.IndicatorCatalog.defaultInstances()))
        showMessage("指标已恢复默认")
    }

    // ------------------------------------------------------------ 持久化

    private fun persistAccount() {
        val e = tradingEngine
        viewModelScope.launch(Dispatchers.IO) {
            accounts.inTransaction { db ->
                accounts.saveAccount(e.balance, e.initialBalance, e.lastBid)
                for (p in e.openPositions) accounts.upsertTrade(db, p)
            }
        }
    }

    private fun persistTradeOnly(record: TradeRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            accounts.inTransaction { db ->
                accounts.upsertTrade(db, record)
                accounts.saveAccount(tradingEngine.balance, tradingEngine.initialBalance, tradingEngine.lastBid)
            }
        }
    }

    private fun persistTradesAndAccount(records: List<TradeRecord>) {
        val e = tradingEngine
        viewModelScope.launch(Dispatchers.IO) {
            accounts.inTransaction { db ->
                for (r in records) accounts.upsertTrade(db, r)
                accounts.saveAccount(e.balance, e.initialBalance, e.lastBid)
                accounts.deleteStaleOpen(db, e.openPositions.map { it.orderId })
            }
        }
    }

    /**
     * 落一次回放存档。
     *
     * @param force true = 无视「内容没变」直接写（主动暂停、切后台、换源时用）；
     *              false = 与上次落库完全相同就跳过，避免回放期间高频写库
     *
     * 注意这里存的是 [currentSourceKey]（稳定身份），**不是 sourceName**。
     * 之前存显示名、恢复时拿显示名去比对 file:/… 这种持久化 id，
     * 两者永远不相等 —— 那就是「每次重启都回到开头」的根因。
     */
    private fun persistReplay(force: Boolean) {
        val rs = replayEngine.state.value
        val key = currentSourceKey.ifBlank { SourceKeys.UNKNOWN }
        if (!force && key == persistedKey && rs.index == persistedIndex && rs.speed == persistedSpeed) return

        val name = currentSource?.sourceName
        replayRepo.save(key, name, rs.index, rs.speed)
        settings.putInt(SettingsRepository.KEY_PLAY_SPEED, rs.speed)

        persistedKey = key
        persistedIndex = rs.index
        persistedSpeed = rs.speed
        // 同步缓存，省掉 publishState 里的查库
        cachedBookmarkKey = key
        cachedBookmark = ReplayProgress(key, name, rs.index, rs.speed)
    }

    // ------------------------------------------------------------ 消息

    fun showMessage(text: String, isError: Boolean = false) {
        _ui.value = _ui.value.copy(message = UiMessage(text, msgId.incrementAndGet(), isError))
    }

    fun consumeMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    override fun onCleared() {
        super.onCleared()
        replayEngine.pause()
        guessController?.release()
    }

    companion object {
        private const val TAG = "GameViewModel"

        /** 图表与指标保留的 K 线根数（按当前周期计） */
        const val WINDOW = 400

        /** 为构建窗口最多回扫多少根 M1：D1 想要 400 根就得回扫 57.6 万根，必须截断 */
        const val MAX_M1_SCAN = 150_000

        /** 每隔多少根 K 线落一次回放进度（避免高频写库） */
        const val PERSIST_EVERY = 25
    }
}
