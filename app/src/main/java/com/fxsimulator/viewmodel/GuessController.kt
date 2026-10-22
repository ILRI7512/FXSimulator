package com.fxsimulator.viewmodel

import android.app.Application
import com.fxsimulator.data.market.BundledMarketData
import com.fxsimulator.data.repository.GUESS_INITIAL_EQUITY
import com.fxsimulator.data.repository.SettingsRepository
import com.fxsimulator.di.AppContainer
import com.fxsimulator.domain.guess.GuessDealer
import com.fxsimulator.domain.guess.GuessDirection
import com.fxsimulator.domain.guess.GuessQuestion
import com.fxsimulator.domain.guess.GuessOutcome
import com.fxsimulator.domain.guess.GuessResult
import com.fxsimulator.domain.guess.CloseKind
import com.fxsimulator.domain.guess.ExitKind
import com.fxsimulator.domain.guess.GuessGrade
import com.fxsimulator.domain.guess.GuessRule
import com.fxsimulator.domain.guess.GuessRunSummary
import com.fxsimulator.domain.guess.GuessStops
import com.fxsimulator.domain.guess.StopSettle
import com.fxsimulator.domain.guess.GuessScore
import com.fxsimulator.domain.guess.GuessStats
import com.fxsimulator.domain.money.MarginCalculator
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.model.SymbolSpec
import com.fxsimulator.indicators.IndicatorConfig
import com.fxsimulator.indicators.IndicatorConfigCodec
import com.fxsimulator.ui.chart.IndicatorMapper
import com.fxsimulator.ui.chart.OverlaySeries
import com.fxsimulator.ui.chart.SubPaneSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * 猜涨跌的界面状态。
 *
 * 注意 [chartBars] 是**已经揭晓的东西**，答案（还没揭晓的未来）只存在于
 * 控制器内部，从不进入这个状态对象 —— 因此界面层不可能提前看到结果。
 */
data class GuessUiState(
    /** 正在加载行情 / 出题 */
    val loading: Boolean = true,
    /** 当前这道题（null = 还没出好） */
    val question: GuessQuestion? = null,
    /** 图表内容 = 题面（[GuessQuestion.revealed]）+ 已经揭晓的未来 */
    val chartBars: List<Candle> = emptyList(),
    /** 已经揭晓了几根未来 K 线 */
    val futureShown: Int = 0,
    /** 玩家猜的方向；null = 还没作答 */
    val direction: GuessDirection? = null,
    val entryPrice: Double = 0.0,
    /** 持仓中的浮动盈亏（美元） */
    val floating: Double = 0.0,
    /** 揭晓进行中 */
    val running: Boolean = false,
    /** 本局结算结果；null = 还没结算 */
    val result: GuessResult? = null,
    val stats: GuessStats = GuessStats(),
    /** 本局手数 */
    val lot: Double = 1.0,
    /** 每根 K 线的揭晓间隔（毫秒） */
    val intervalMs: Long = TICK_DEFAULT,
    /** 当前这段行情来自哪个月（yyyy-MM）。只在结算后展示，避免提前泄露。 */
    val month: String = "",

    /** 价格小数位，取自合约规格，避免为了一个数字去订阅整个主界面状态 */
    val digits: Int = 3,
    /** 同一段行情已经出了几题 */
    val servedInMarket: Int = 0,

    // ------------------------------------------------ 本轮（30 轮生存跑）
    /** 本轮已完成几轮 */
    val runRound: Int = 0,
    /** 本轮上限 */
    val runMax: Int = GuessGrade.MAX_ROUNDS,
    /** 本轮当前虚拟权益（美元） */
    val runEquity: Double = 0.0,
    /** 本轮开局权益，用来算收益率 */
    val runStartEquity: Double = 0.0,
    /** 本轮已爆仓 */
    val bust: Boolean = false,
    /** 本轮结束后的评价；null = 还在打 */
    val runSummary: GuessRunSummary? = null,
    /** 止损距离（点）；0 = 不设 */
    val slPoints: Int = 0,
    /** 止盈距离（点）；0 = 不设 */
    val tpPoints: Int = 0,
    /** 这一轮是怎么结束的（止损/止盈/时间到/手动）；null = 还没结算 */
    val exitKind: ExitKind? = null,
    /** 瞬时提示（如权益不足无法开仓），不持久化 */
    val notice: String? = null,

    val error: String? = null,
) {
    /** 还能不能继续揭晓（有时间跑完整个窗口） */
    val maxBars: Int get() = GuessDealer.HIDDEN_BARS

    companion object {
        const val TICK_DEFAULT = 140L

        /** 揭晓速度：每根 K 线停多久 */
        val TICK_OPTIONS = listOf(80L, 140L, 240L)
        val TICK_LABELS = mapOf(80L to "快", 140L to "正常", 240L to "慢")

        /** 本局手数选项。1 手 = 100 盎司，价格动 1 美元 = 100 美元。 */
        val LOT_OPTIONS = listOf(0.5, 1.0, 2.0, 5.0)
    }
}

/**
 * 猜涨跌的控制器。
 *
 * 由 [GameViewModel] 持有，与「回放 / 模拟交易」那一套完全隔离：
 * 自己有独立的权益、独立的手数、独立的存档，不会污染模拟账户，
 * 也不会被回放引擎的状态干扰。
 *
 * ## 一次只加载一段行情
 * 内置行情是按月存的，一个月约 2.9 万根 M1。这个量级足够出几百道不重复的题，
 * 所以**进来随机挑一个月，就在这一个月里连续出题**，而不是每出一题就换一个月 ——
 * 那样每局都要重新解析一个 2MB 的 CSV，出题会卡到没法玩。
 * 想换行情时点「换一段行情」即可。
 */
class GuessController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val container: AppContainer,
    private val specProvider: () -> SymbolSpec,
) {

    private val repo = container.guessStatsRepository
    private val settings = container.settingsRepository

    private val _state = MutableStateFlow(GuessUiState())
    val state: StateFlow<GuessUiState> = _state.asStateFlow()

    /**
     * 当前品种的内置月份。
     *
     * 以前是构造时按默认品种（黄金）算一次的 val —— 玩家切到外汇后，猜涨跌仍按黄金的
     * 月份池出题，而 2024-01~2024-09 这些月份在外汇目录里并不存在，整题会直接失败。
     * 现在每次取用都按当前品种重新算。
     */
    private fun months(): List<String> =
        BundledMarketData.availableMonths(app, specProvider().symbol)

    /**
     * 猜涨跌图上要画的主图叠加指标。
     *
     * 关键在于「算在哪些 K 线上」：调用方只会传 [GuessUiState.chartBars]，
     * 也就是**已经揭晓的历史** —— 答案那几根不在里面。指标与玩家看到的是同一段行情，
     * 这样"看指标做判断"才是真的分析；否则指标就等于替玩家看到了未来。
     */
    fun indicatorOverlays(bars: List<Candle>): List<OverlaySeries> =
        if (bars.size < 3) emptyList()
        else IndicatorMapper.overlays(currentIndicatorConfig(), bars)

    /** 副图指标窗格，口径与 [indicatorOverlays] 完全一致 */
    fun indicatorPanes(bars: List<Candle>): List<SubPaneSpec> =
        if (bars.size < 3) emptyList()
        else IndicatorMapper.subPanes(currentIndicatorConfig(), bars)

    /** 用户当前的指标配置：与交易页共用同一份，在设置页改完立刻生效 */
    private fun currentIndicatorConfig(): IndicatorConfig =
        IndicatorConfigCodec.decode(settings.getString(SettingsRepository.KEY_INDICATORS, ""))
    private val rng = Random(System.currentTimeMillis())

    /** 当前这一段行情的全部 K 线。只保留一段，内存约 2~3 MB。 */
    private var bars: List<Candle> = emptyList()

    /** 当前这题的答案（被遮住的未来）。只在控制器内部流转，不进 UI 状态。 */
    private var hidden: List<Candle> = emptyList()

    /** 已经在这个月里出了几题 */
    private var served = 0

    private var tickJob: Job? = null

    init {
        val lot = settings.getDouble(SettingsRepository.KEY_GUESS_LOT, 1.0)
            .takeIf { it > 0.0 } ?: 1.0
        val interval = settings.getInt(
            SettingsRepository.KEY_GUESS_TICK,
            GuessUiState.TICK_DEFAULT.toInt(),
        ).toLong().takeIf { it > 0L } ?: GuessUiState.TICK_DEFAULT
        _state.value = GuessUiState(
            loading = true,
            stats = repo.load(GUESS_INITIAL_EQUITY),
            lot = lot,
            intervalMs = interval,
            // 一开局就是「满权益」状态，否则第一手会被权益检查直接拒掉
            runEquity = GUESS_INITIAL_EQUITY,
            runStartEquity = GUESS_INITIAL_EQUITY,
            runMax = GuessGrade.MAX_ROUNDS,
        )
        pickMarketAndDeal()
    }

    // ------------------------------------------------------------ 出题

    /** 随机挑一段行情（尽量避开当前这段），然后出第一题。 */
    fun pickMarketAndDeal() {
        if (months().isEmpty()) {
            _state.value = _state.value.copy(
                loading = false,
                error = "内置行情不可用：APK 里没有找到任何月份的数据",
            )
            return
        }
        tickJob?.cancel()
        served = 0
        val keep = _state.value
        _state.value = keep.copy(
            loading = true,
            question = null,
            chartBars = emptyList(),
            futureShown = 0,
            direction = null,
            floating = 0.0,
            running = false,
            result = null,
            error = null,
            servedInMarket = 0,
        )
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { loadAnotherMarket() }
            if (loaded == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "内置行情不可用：所有月份都无法解析",
                )
                return@launch
            }
            bars = loaded.bars
            // 月份必须在出题**之前**写回状态：deal() 会拿它给题目打标签，
            // 顺序反了的话每道题都会带着上一段行情的月份（曾经就是这么错的）
            _state.value = _state.value.copy(month = loaded.month)
            deal()
        }
    }

    /** 在当前这段行情里出下一题 */
    fun next() {
        tickJob?.cancel()
        if (bars.size < GuessDealer.MIN_BARS) {
            pickMarketAndDeal()
            return
        }
        deal()
    }

    private fun deal() {
        val s = _state.value
        val spec = specProvider()
        val q = GuessDealer.question(bars, s.month, served + 1, rng, spec.spread, spec.symbol, spec)
        if (q == null) {
            _state.value = s.copy(loading = false, error = "这段行情太短，出不了题")
            return
        }
        hidden = GuessDealer.hidden(bars, q)
        served += 1
        _state.value = s.copy(
            loading = false,
            digits = spec.digits,
            question = q,
            chartBars = q.revealed,
            futureShown = 0,
            direction = null,
            entryPrice = 0.0,
            floating = 0.0,
            running = false,
            result = null,
            exitKind = null,
            notice = null,
            // 止损止盈的默认值由这一题的 ATR 推出来：一个「点」在不同品种上的
            // 经济含义差很远（黄金 0.001 vs 欧元 0.00001），从 0 开始填不现实。
            slPoints = Math.round(1.5 * q.unit / q.spec.point).toInt().coerceIn(1, 100_000),
            tpPoints = Math.round(3.0 * q.unit / q.spec.point).toInt().coerceIn(1, 100_000),
            servedInMarket = served,
            error = null,
        )
    }

    /** 挑一段与当前不同的行情并解析。在 IO 线程调用。 */
    private fun loadAnotherMarket(): Loaded? {
        val all = months()
        if (all.isEmpty()) return null
        val current = _state.value.month
        // 尽量换一个不是当前这个月；月份只有一个时就用它
        val pool = all.filter { it != current }.ifEmpty { all }
        // 打乱顺序后逐个尝试：某个月的数据坏掉不该让整个玩法不可用
        val order = pool.shuffled(rng)
        for (m in order) {
            val b = readMonth(m)
            if (b.size >= GuessDealer.MIN_BARS) return Loaded(m, b)
        }
        return null
    }

    private fun readMonth(month: String): List<Candle> {
        val src = BundledMarketData.load(app, month, specProvider().symbol) ?: return emptyList()
        val out = ArrayList<Candle>(src.size)
        for (i in 0 until src.size) src.barAt(i)?.let { out.add(it) }
        return out
    }

    private data class Loaded(val month: String, val bars: List<Candle>)

    // ------------------------------------------------------------ 作答与揭晓

    /** 本轮已经算好、等着揭晓动画走完的结局 */
    private var pending: StopSettle? = null

    /** 本轮计数（只在内存里，不进数据库） */
    private var runWins = 0
    private var runLosses = 0
    private var runSlRounds = 0
    private var runSlHit = 0

    /**
     * 猜一个方向，按当前价立即成交，然后开始逐根揭晓。
     *
     * 入场前过两道闸：轮次是否已满、**权益够不够这一手的保证金**。
     * 后者是这次改造的重点 —— 以前手数是给好的几个选项，再多也只是数字；
     * 现在手数由玩家输入，就必须真的因为「赔不起」而被拦住。
     */
    fun answer(direction: GuessDirection) {
        val s = _state.value
        val q = s.question ?: return
        if (s.running || s.result != null) return
        if (s.runSummary != null || s.bust) return
        if (s.runRound >= s.runMax) return

        val lot = s.lot
        if (lot <= 0.0) {
            _state.value = s.copy(notice = "手数必须大于 0")
            return
        }
        val entry = GuessRule.entryPrice(direction, q.anchor, q.spread)
        val needMargin = MarginCalculator.requiredMargin(q.spec, lot, entry)
        if (needMargin > s.runEquity) {
            _state.value = s.copy(
                notice = "权益不足：这一手需要保证金 " + Math.round(needMargin) +
                    " USD，当前权益只有 " + Math.round(s.runEquity) + " USD。请减小手数。",
            )
            return
        }

        // 先把这一轮怎么结束算出来（含止损止盈在哪一根被打掉），
        // 揭晓动画只是把它演出来 —— 否则动画和结算会变成两套逻辑。
        val outcome = GuessStops.settle(
            direction = direction,
            anchor = q.anchor,
            hidden = hidden,
            spread = q.spread,
            spec = q.spec,
            volume = lot,
            slPoints = s.slPoints,
            tpPoints = s.tpPoints,
        ) ?: return
        pending = outcome

        _state.value = s.copy(
            direction = direction,
            entryPrice = outcome.entryPrice,
            running = true,
            floating = 0.0,
            notice = null,
        )
        startTicking(direction, outcome.entryPrice, q, outcome.barsHeld)
    }

    /** 揭晓到 [until] 根为止 —— 那正是止损或止盈被打掉的位置 */
    private fun startTicking(
        direction: GuessDirection,
        entry: Double,
        q: GuessQuestion,
        until: Int,
    ) {
        tickJob?.cancel()
        tickJob = scope.launch {
            val end = until.coerceIn(1, hidden.size)
            for (i in 0 until end) {
                delay(_state.value.intervalMs)
                if (!isActive) return@launch
                val bar = hidden[i]
                val cur = _state.value
                // 用题目自带的规格：揭晓途中切了品种，也不能改变这一局的合约
                val f = GuessRule.floating(
                    direction, entry, bar.close, q.spread, q.spec, cur.lot,
                )
                _state.value = cur.copy(
                    futureShown = i + 1,
                    chartBars = cur.chartBars + bar,
                    floating = f,
                )
            }
            settle(closedByHand = false)
        }
    }

    /** 玩家自己决定平仓。止损止盈之外，仍然保留手动这一步。 */
    fun close() {
        val s = _state.value
        if (!s.running) return
        tickJob?.cancel()
        settle(closedByHand = true)
    }

    private fun settle(closedByHand: Boolean) {
        val s = _state.value
        val q = s.question ?: return
        val dir = s.direction ?: return
        if (s.result != null) return

        val p = pending
        val kind = if (closedByHand) ExitKind.MANUAL else (p?.kind ?: ExitKind.TIMEOUT)
        val held = if (closedByHand) maxOf(1, s.futureShown) else (p?.barsHeld ?: hidden.size)
        val exitPrice = if (closedByHand) {
            // 手动平仓：按最后一根已揭晓的 BID 走 FillModel，和旧版口径一致
            GuessRule.exitPrice(dir, s.chartBars.lastOrNull()?.close ?: q.anchor, q.spread)
        } else {
            p?.exitPrice ?: return
        }
        val profit = GuessRule.pnl(dir, s.entryPrice, exitPrice, q.spec, s.lot)

        // 持有期间的最大浮盈/浮亏：只统计到离场那一刻，口径与旧版一致
        var maxFav = 0.0
        var maxAdv = 0.0
        for (i in 0 until held.coerceIn(0, hidden.size)) {
            val c = hidden[i]
            val hi = (c.high - s.entryPrice) * dir.trade.sign
            val lo = (c.low - s.entryPrice) * dir.trade.sign
            if (hi > maxFav) maxFav = hi
            if (lo < maxAdv) maxAdv = lo
        }

        val r = GuessResult(
            direction = dir,
            entryPrice = s.entryPrice,
            exitPrice = exitPrice,
            outcome = when {
                profit > 0.0 -> GuessOutcome.WIN
                profit < 0.0 -> GuessOutcome.LOSS
                else -> GuessOutcome.FLAT
            },
            closeKind = if (closedByHand) CloseKind.MANUAL else CloseKind.TIMEOUT,
            pct = if (s.entryPrice > 0.0) {
                (exitPrice - s.entryPrice) * dir.trade.sign / s.entryPrice
            } else 0.0,
            profit = profit,
            barsHeld = held,
            maxFavorable = maxFav,
            maxAdverse = maxAdv,
            referenceStopAt = -1,
            referenceStopPrice = 0.0,
            referenceStopProfit = 0.0,
        )

        // 两本账：累计战绩跨轮持久化；本轮权益只活在内存里
        val stats = GuessScore.apply(s.stats, r)
        repo.save(stats)

        if (s.slPoints > 0) runSlRounds += 1
        if (kind == ExitKind.STOP_LOSS) runSlHit += 1
        if (profit > 0.0) runWins += 1 else if (profit < 0.0) runLosses += 1

        val equity = s.runEquity + profit
        val rounds = s.runRound + 1
        val busted = equity <= 0.0
        val finished = busted || rounds >= s.runMax
        val summary = if (finished) {
            GuessGrade.evaluate(
                startEquity = s.runStartEquity,
                endEquity = equity.coerceAtLeast(0.0),
                busted = busted,
                rounds = rounds,
                wins = runWins,
                losses = runLosses,
                stopLossRounds = runSlRounds,
                stopLossHit = runSlHit,
            )
        } else null

        pending = null
        _state.value = s.copy(
            running = false,
            floating = profit,
            result = r,
            stats = stats,
            exitKind = kind,
            runRound = rounds,
            runEquity = equity,
            bust = busted,
            runSummary = summary,
            // 结算后把剩下的窗口一次性揭开：让玩家看见「我走了之后，它去了哪里」。
            // 这是整个玩法最扎心的一刻，也是「拿不住」这件事唯一能被看见的方式。
            chartBars = q.revealed + hidden,
            futureShown = hidden.size,
        )
    }

    /** 开始新的一轮：重置权益与计数，重新挑一段行情 */
    fun startRun() {
        tickJob?.cancel()
        pending = null
        runWins = 0
        runLosses = 0
        runSlRounds = 0
        runSlHit = 0
        _state.value = _state.value.copy(
            runRound = 0,
            runMax = GuessGrade.MAX_ROUNDS,
            runEquity = GUESS_INITIAL_EQUITY,
            runStartEquity = GUESS_INITIAL_EQUITY,
            bust = false,
            runSummary = null,
            result = null,
            exitKind = null,
            notice = null,
        )
        pickMarketAndDeal()
    }

    // ------------------------------------------------------------ 设置

    fun setLot(lot: Double) {
        if (lot <= 0.0) return
        settings.putDouble(SettingsRepository.KEY_GUESS_LOT, lot)
        _state.value = _state.value.copy(lot = lot)
    }

    /** 止损距离（点）；0 = 不设 */
    fun setStopLoss(points: Int) {
        _state.value = _state.value.copy(slPoints = points.coerceIn(0, 100_000))
    }

    /** 止盈距离（点）；0 = 不设 */
    fun setTakeProfit(points: Int) {
        _state.value = _state.value.copy(tpPoints = points.coerceIn(0, 100_000))
    }

    fun setInterval(ms: Long) {
        if (ms <= 0L) return
        settings.putInt(SettingsRepository.KEY_GUESS_TICK, ms.toInt())
        _state.value = _state.value.copy(intervalMs = ms)
    }

    /** 清空战绩，从初始权益重新开始 */
    fun resetStats() {
        repo.clear()
        _state.value = _state.value.copy(stats = GuessScore.initial(GUESS_INITIAL_EQUITY))
    }

    /** ViewModel 销毁时调用，避免揭晓协程在无人观看时继续跑 */
    fun release() {
        tickJob?.cancel()
    }
}
