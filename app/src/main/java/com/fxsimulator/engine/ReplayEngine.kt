package com.fxsimulator.engine

import com.fxsimulator.data.market.BarSource
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.util.GoldLabLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * 两根 K 线之间允许的**最小真实间隔**（毫秒）。
 *
 * 这是一条真实的性能下限：低于它就无法再压缩。副作用是**标称倍率会被它截断** ——
 * 详见 [replayIntervalMillis]。
 */
internal const val MIN_BAR_INTERVAL_MILLIS = 16L

/**
 * 推进两根 K 线之间的真实间隔（毫秒）。纯函数，便于确定性单测。
 *
 * 标称倍率只在**基线足够小**时才被 [MIN_BAR_INTERVAL_MILLIS] 截断，
 * 即 `baseBarMillis / speed < 16` 时才会发生。生产环境走默认基线
 * `baseBarMillis = 1000`（`GameViewModel` 构造 [ReplayEngine] 时不传该参数），
 * 四档间隔分别是 1000 / 500 / 200 / 100 ms，**没有一档触及下限，
 * 标称倍率与实际倍率一致**。
 *
 * 下限只在使用小基线的单元测试里才显现（例如 40ms 时 5x 与 10x 都落在 16ms）——
 * 那是**测试基线的特性，不是生产行为**，不要据此认为界面倍速名不副实。
 */
internal fun replayIntervalMillis(baseBarMillis: Long, speed: Int): Long {
    val safeSpeed = if (speed < 1) 1 else speed
    return (baseBarMillis / safeSpeed).coerceAtLeast(MIN_BAR_INTERVAL_MILLIS)
}

/** 回放状态（不可变快照，供 Compose 直接订阅） */
data class ReplayState(
    val loaded: Boolean = false,
    val totalBars: Int = 0,
    val index: Int = -1,
    val candle: Candle? = null,
    val playing: Boolean = false,
    val speed: Int = 1,
    val finished: Boolean = false,
    val sourceName: String = "",
    val isDemoData: Boolean = false,
)

/**
 * 历史行情回放引擎。
 *
 * 用 Kotlin Coroutines 驱动：只有**一个** [Job]。重复调用 [play] 不会启动第二个任务，
 * 因此在屏幕旋转、切后台、重复点击时都不会出现多个回放循环。
 *
 * 1 倍速 = 每秒推进 1 根 M1 K 线；速度可调 1/2/5/10 倍。
 * 暂停后价格与时间**不再推进**（循环被取消，不再有副作用）。
 */
class ReplayEngine(
    private val scope: CoroutineScope,
    /** 1 倍速下每根 K 线占用的真实毫秒数 */
    private val baseBarMillis: Long = 1000L,
) {

    private val _state = MutableStateFlow(ReplayState())
    val state: StateFlow<ReplayState> = _state.asStateFlow()

    private var job: Job? = null
    private var source: BarSource? = null

    /** 每根新 K 线到达时的回调（在协程中执行，调用方负责切换线程） */
    var onBar: ((Candle) -> Unit)? = null

    /** 回放结束回调 */
    var onFinished: (() -> Unit)? = null

    /** 回放过程中出现异常时的回调（例如行情文件句柄失效） */
    var onError: ((String) -> Unit)? = null

    fun load(newSource: BarSource, startIndex: Int = 0) {
        pause()
        source = newSource
        val idx = startIndex.coerceIn(0, maxOf(0, newSource.size - 1))
        val c = if (newSource.size > 0) newSource.barAt(idx) else null
        _state.value = ReplayState(
            loaded = newSource.size > 0,
            totalBars = newSource.size,
            index = idx,
            candle = c,
            playing = false,
            speed = _state.value.speed,
            finished = false,
            sourceName = newSource.sourceName,
            isDemoData = newSource.isDemoData,
        )
    }

    fun setSpeed(speed: Int) {
        val s = when {
            speed <= 1 -> 1
            speed <= 2 -> 2
            speed <= 5 -> 5
            else -> 10
        }
        _state.value = _state.value.copy(speed = s)
        // 速度改变无需重启任务：循环每轮都会读取最新速度
    }

    /**
     * 开始（或继续）回放。
     * @return true 表示本次确实启动了回放循环；false 表示未加载 / 已到末尾 / 已在运行
     *
     * 这里必须用 CoroutineStart.LAZY 启动：viewModelScope 用的是
     * Dispatchers.Main.immediate，而 immediate 调度器在「已在主线程」时
     * **不会派发**，协程体会在 launch() 返回之前就同步执行。
     * 若在协程体内部读 job 字段，那一刻它还是 null，循环条件立刻为假、
     * 循环直接退出，界面却停在「回放中」——这正是「行情不会动」的根因。
     */
    fun play(): Boolean {
        val s = _state.value
        if (!s.loaded) return false
        if (s.finished) return false
        // 关键：已在运行则不重复启动
        if (job?.isActive == true) return false
        _state.value = _state.value.copy(playing = true)
        val started = scope.launch(start = CoroutineStart.LAZY) { runLoop() }
        job = started
        started.start()
        return true
    }

    fun pause() {
        job?.cancel()
        job = null
        if (_state.value.playing) {
            _state.value = _state.value.copy(playing = false)
        }
    }

    fun toggle() {
        if (_state.value.playing) pause() else play()
    }

    /** 单步前进一根 */
    fun stepForward() {
        pause()
        advance()
    }

    /** 回到起点（或指定索引） */
    fun reset(startIndex: Int = 0) {
        val src = source ?: return
        pause()
        val idx = startIndex.coerceIn(0, maxOf(0, src.size - 1))
        _state.value = _state.value.copy(
            index = idx,
            candle = if (src.size > 0) src.barAt(idx) else null,
            playing = false,
            finished = false,
        )
    }

    /** 跳转到指定索引 */
    fun seek(index: Int) {
        val src = source ?: return
        if (src.size == 0) return
        val idx = index.coerceIn(0, src.size - 1)
        _state.value = _state.value.copy(
            index = idx,
            candle = src.barAt(idx),
            finished = false,
        )
    }

    /** 按时间查找最接近的索引（用于「从指定日期开始回放」） */
    fun indexForTime(millis: Long): Int {
        val src = source ?: return 0
        if (src.size == 0) return 0
        var lo = 0
        var hi = src.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            val t = src.barAt(mid)?.timeMillis ?: break
            if (t < millis) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private suspend fun runLoop() {
        // 用协程自身的 context 判断存活，而不是读可变的 job 字段
        while (coroutineContext.isActive) {
            val s = _state.value
            val src = source ?: break
            if (!s.loaded) break
            if (s.index >= src.size - 1) {
                _state.value = s.copy(playing = false, finished = true)
                onFinished?.invoke()
                break
            }
            val interval = replayIntervalMillis(baseBarMillis, s.speed)
            delay(interval)
            if (!coroutineContext.isActive) break
            try {
                advance()
            } catch (e: Exception) {
                // 回放循环里的任何异常都不能让应用闪退：
                // 记录后暂停，并把原因交给界面显示。
                GoldLabLog.e(TAG, "回放推进失败，已自动暂停", e)
                onError?.invoke("回放推进失败，已自动暂停：" + (e.message ?: e.javaClass.simpleName))
                break
            }
        }
        // 防御：任何非暂停原因的退出，都不应让界面停在「回放中」
        if (_state.value.playing) {
            _state.value = _state.value.copy(playing = false)
        }
    }

    private fun advance() {
        val src = source ?: return
        val s = _state.value
        if (s.index >= src.size - 1) {
            _state.value = s.copy(finished = true, playing = false)
            return
        }
        val next = s.index + 1
        val candle = src.barAt(next) ?: return
        _state.value = s.copy(index = next, candle = candle)
        onBar?.invoke(candle)
    }

    /** 是否正在运行（用于生命周期判断） */
    fun isRunning(): Boolean = job?.isActive == true

    private companion object {
        const val TAG = "ReplayEngine"
    }
}
