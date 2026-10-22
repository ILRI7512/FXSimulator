# FX模拟器（GoldLab）项目审计报告

> 审计对象：`D:\fxsim`（本地目录名 `fxsim`，Gradle `rootProject.name = "GoldLab"`，应用名「FX模拟器」）
> 审计日期：2026-10-22
> 审计方式：**逐文件阅读源码**（`app/src/main/java/com/fxsimulator/**` 82 个 `.kt`、`app/src/test/**` 19 个 `.kt`、全部 Gradle 配置、Manifest、ProGuard、`.gitignore`、`docs/`、`recon/`、`tools/`），并**实际执行**构建与测试命令。凡未经实际运行验证的结论，均在文中标注「未验证」。
> 本报告不依据文件名推测功能；每条功能描述都对应已读的具体源码位置。

---

## 0. 结论摘要（先看这段）

| 项 | 实测结论 |
|---|---|
| 项目性质 | 单模块 Android 原生 App（Kotlin + Jetpack Compose），**纯离线**，Manifest **无 INTERNET 权限** |
| 代码规模 | 生产代码 82 个 `.kt` / 17,811 行 / 0.6 MB；测试 19 个 `.kt` / 3,290 行 |
| 构建状态 | `.\gradlew.bat :app:testDebugUnitTest` **BUILD SUCCESSFUL**（实测） |
| 测试状态 | **19 个 suite / 273 个用例 / 0 失败 / 0 错误 / 0 跳过**（用 `--no-build-cache` 强制真实执行后从 `app/build/test-results/testDebugUnitTest/*.xml` 汇总，2026-10-22 21:45:33） |
| 依赖 | 仅 AndroidX + Compose，**无任何第三方运行时依赖**（无网络库、无序列化库、无数据库 ORM、无广告 SDK 实体） |
| 数据资产 | 257 个 `.glb1` 文件 / 87.1 MB 已随包内置；另有原始 CSV 合计约 366 MB 在仓库外 |
| **行情来源与许可** | **仅凭客服口头答复，书面许可与对话记录均未留存 → 不具备公开再分发依据（详见 §7）** |
| 既有 Rust / JNI / NDK | **无**。全项目零 `.rs`、零 `Cargo.toml`、零 `externalNativeBuild`、零 `System.loadLibrary` |
| 既有 Python | 仅 3 个离线脚本（图标生成 ×2、指标对拍 ×1），**不参与 App 运行时** |
| Git 状态 | **`D:\fxsim` 不是 Git 仓库**（`git status` → `fatal: not a git repository`），无 `.git`，无任何历史提交 → §2 的「历史密钥泄露」风险为 **零**（不存在历史） |
| 阻断项 | ①目标 GitHub 仓库地址未知；②行情数据能否公开再分发无法确认 |

**总体判断**：代码质量与工程纪律明显高于一般个人项目（有 273 个真实单测、有 GLB1 自研二进制格式并做过实测收益对照、有失败根因的源码内注释），主要风险不在代码本身，而在**数据许可**与**工程设施缺失**（无 README、无 CI、无 Lint/静态检查、无版本标签）。

---

## 1. 环境与构建（实测）

| 组件 | 版本 / 位置 | 验证方式 |
|---|---|---|
| OS | Windows 11 amd64 | `gradlew --version` 输出 |
| Gradle | 8.11.1（wrapper，distributionUrl 指向 `mirror.nju.edu.cn`） | `.\gradlew.bat --version` exit 0 |
| Gradle 内置 Kotlin | 2.0.20 | 同上 |
| JVM | 21.0.10（`C:\Program Files\Java\jdk-21.0.10`），**`JAVA_HOME` 未设置** | 同上（Launcher JVM 21.0.10） |
| Android Gradle Plugin | 8.7.3 | `build.gradle.kts` |
| Kotlin 插件 | 2.0.21 + `org.jetbrains.kotlin.plugin.compose` 2.0.21 | 同上 |
| Android SDK | `D:\SDK`（`local.properties` → `sdk.dir=D:/SDK`） | 构建成功即证明 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 | `app/build.gradle.kts` |
| buildTools | 35.0.0 | 同上 |

`JAVA_HOME` 未设置本身不阻断构建（AGP 会走 `gradlew` 探测），但**换机器复现时需要手动保证 JDK 21 可用**，这是交接文档必须写明的一条。

### 1.1 构建配置要点

- `settings.gradle.kts`：`rootProject.name = "GoldLab"`、`include(":app")`，仓库**优先华为云镜像**（`mirror`），Google/Maven Central 兜底。
- `gradle.properties`：`-Xmx1536m -XX:MaxMetaspaceSize=512m`、`daemon=true`、`parallel=false`、`caching=true`；`android.useAndroidX=true`；`android.nonTransitiveRClass=true`。
- `android.defaults.buildfeatures.buildconfig=true` → 每次构建打一条 **deprecation WARNING**（AGP 9.0 移除该开关）。**建议迁移为模块级 `android.buildFeatures.buildConfig = true`**，见 §9 问题 P3。
- `app/build.gradle.kts`：`isMinifyEnabled = false`（release 故意不混淆，理由写在 `proguard-rules.pro` 顶部注释：混淆会让线上崩溃堆栈难以定位）。规则文件本身是完备的（保留 `SourceFile,LineNumberTable`、保留 `SQLiteOpenHelper` 子类、保留 enum `values()/valueOf`）。
- 签名：从 `keystore/keystore.properties` 或 `【私密】keystore/keystore.properties` 读取；**两个目录都不存在则跳过签名**，保证无密钥机器仍可 `assembleDebug` / 跑测试。这是正确的设计（构建可复现性优先）。
- 测试任务透传系统属性：`-Dgoldlab.genMarket=1`（把 `market-data/*.csv` 重新编码为 `assets/market/*.glb1`）、`-Dgoldlab.importBinance=<dir>`（Binance CSV 导入）。

### 1.2 构建与测试实测记录

```
.\gradlew.bat :app:testDebugUnitTest
→ BUILD SUCCESSFUL (exit 0)

（首次两次为 UP-TO-DATE / FROM-CACHE，属 Gradle build cache 命中，不构成验证）
删除 app\build\test-results\testDebugUnitTest 后加 --no-build-cache 重跑：
> Task :app:testDebugUnitTest   ← 真实执行，未命中缓存
BUILD SUCCESSFUL in 7s
结果汇总：suites 19 / tests 273 / failures 0 / errors 0 / skipped 0
```

19 个 suite（均位于 `app/src/test/java/com/fxsimulator/`）：

`AccountAndPnlTest`(96)、`AdsRewardTest`(67)、`BinanceAssetTest`(72)、`BinanceCsvImporter`(123)、`CandleAggregatorTest`(122)、`ChartMathTest`(451)、`CsvAndStatsTest`(169)、`FixedOddsTest`(139)、`GamesDomainTest`(332)、`GamesEngineTest`(121)、`GamesFeedTest`(111)、`Glb1RoundTripTest`(152)、`GuessGameTest`(499)、`IndicatorCatalogTest`(328)、`IndicatorsTest`(78)、`ReplayEngineTest`(166)、`ReplayResumeTest`(160)、`TickSynthesizerTest`(76)、`TradingEngineTest`(182)。

> 说明：`testOptions.unitTests.isReturnDefaultValues = true`，即 Android 框架桩方法返回默认值。测试全部是 **JVM 单元测试**，**没有任何 instrumented（androidTest）测试**，UI 层（Compose）**零自动化覆盖**。

---

## 2. 安全检查（Git 历史 / 密钥 / 敏感数据）

### 2.1 Git 历史

`D:\fxsim` **没有 `.git` 目录**，`git status` / `git log` / `git remote -v` 全部返回 `fatal: not a git repository`。

→ **结论：不存在任何历史提交，因此不存在「历史提交中夹带密钥」的风险，无需 rewrite history，无需更换凭证。** 这一点比多数项目幸运：首次入库即干净的 `.gitignore` 就能彻底生效。

### 2.2 本机确实存在真实签名密钥（绝不可入库）

`D:\fxsim\【私密】keystore\` **实际包含 3 个文件**（用 `Get-ChildItem -Force` 才列出，普通列举会被中文方括号影响）：

```
keystore.properties   ← 含 storePassword / keyPassword 明文
release.jks           ← 发布签名密钥库
README.txt
```

现有 `.gitignore` 已包含 `【私密】keystore/`、`keystore/`、`*.jks`、`keystore.properties` —— **已覆盖，保持**。审计中**未读取也未复制** `keystore.properties` 的内容，本报告不含任何密码。

> ⚠️ **操作红线**：初始化 Git 后必须先用 `git status --ignored` 确认 `【私密】keystore/` 处于 ignored 状态，再执行第一次 `git add`。

### 2.3 其它敏感面

| 检查项 | 结果 |
|---|---|
| `local.properties` | 存在，仅含 `sdk.dir=D:/SDK`（无密钥）。已在 `.gitignore` 中 → 不入库 |
| 硬编码 API Key / Token | 全库 grep 未发现（无网络库、无 SDK Key） |
| 广告 SDK | `AdsRepository` 是**自建接口 + 本地桩实现**（`DisabledAdsRepository`、`LocalTestAdsRepository`），未接入任何真实广告 SDK，因此无 SDK Key |
| 个人数据 | 根目录 4 张 `Screenshot_2026-10-17-*.jpg` 为真机截图（含 App 界面，无个人信息）；14 个 `.tmp_*.xml` 是 uiautomator 层级 dump；8 个 `.tmp_*.png` 是调试截图 → **全部属于调试产物，应删除** |
| 签名指纹是否已公开 | `构建说明.md` 与交付报告中已写入 release 证书 SHA-256 / SHA-1。**证书指纹本身不是秘密**（公开 APK 可算出），保留无妨 |

---

## 3. 项目结构（真实，含体积）

```
D:\fxsim\                                    约 700 MB（其中约 660 MB 为行情数据 + APK 产物）
├── .gitignore                               318 B
├── build.gradle.kts / settings.gradle.kts   363 B / 553 B
├── gradle.properties                        791 B
├── gradlew / gradlew.bat / gradle/wrapper/  wrapper 8.11.1
├── local.properties                         16 B（不入库）
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/fxsimulator/        82 个 .kt，17,811 行，0.6 MB
│       │   ├── res/                         23 个文件，0.8 MB（含 3 档密度 + 自适应图标）
│       │   └── assets/market/               257 个 .glb1，87.1 MB
│       └── test/java/com/fxsimulator/       19 个 .kt，3,290 行，0.1 MB
├── docs/UI-REFACTOR.md                      2.9 KB（设计令牌接入记录）
├── market-data/                             33 个 CSV，67.8 MB（XAUUSD 2024-01~2026-09）
├── 历史数据/                                 6 个 CSV，253.1 MB（6 个外汇/白银品种原始 MT5 导出）
├── tools/                                   5 个文件，43.1 MB（3 个 .py + 1 个 45 MB CSV 重复副本）
├── recon/                                   2 个 CSV，0.2 MB（指标对拍黄金样本）
├── APk/                                     3 个文件，55.5 MB（历史发布 APK + 要求.md）
└── 【私密】keystore/                         3 个文件（不入库）
```

**结构问题**：
- 没有 `README.md`（根目录零引导文件）。新人拿到仓库不知道这是什么、怎么构建、数据从哪来 —— 这是入库前必须先补的。
- 中文目录名（`历史数据`、`【私密】keystore`、`APk`）在 Windows 下正常，但在 Linux CI 与部分 Git 工具链上会带来编码/大小写问题；`APk` 的大小写尤其容易在不同文件系统上出岔子。
- 交付文档散落在根目录（`构建说明.md`、`FX模拟器-交付报告.md`、`CHANGELOG-V-0.0.8.md`、`STORE-LISTING-*.md`、`TAPTAP-*.txt`、隐私政策/用户协议的 docx+html），未按用途归档。
- `tools/USDCADm_M1_...csv`（45 MB）与 `历史数据/USDCADm_M1_...csv` 是**同一份数据的重复副本**。

---

## 4. 代码模块清单（逐文件读过，附职责）

### 4.1 数据层 `data/`

| 文件 | 行数 | 职责（源码确认） |
|---|---|---|
| `data/market/BarSource.kt` | 32 | `interface BarSource`：`size / sourceName / symbol / isDemoData / startTimeMillis / endTimeMillis / barAt(index): Candle?`。`InMemoryBarSource` 为内存实现。**这是全项目行情输入的唯一抽象点** —— 未来接合成引擎应从这一层切入 |
| `data/market/BundledMarketData.kt` | 194 | 从 `assets/market` 读内置月份；扩展名优先 `.glb1`，回退 `.csv` / `.csv.gz`；`dirsFor(symbol)` 只对 XAUUSD 允许退回平铺目录（**防止把黄金价格当外汇价格导致盈亏放大到百万级**，源码注释明确写了这个事故风险） |
| `data/market/glb1/Glb1Format.kt` | 181 | 自研列式二进制格式常量与**全项目唯一的价格换算入口** `toTicks / toPrice / scale`；列枚举 `Glb1Column{TIME_DELTA,OPEN_DELTA,HIGH_DELTA,LOW_DELTA,CLOSE_DELTA,VOLUME,SPREAD_DELTA}`、单位枚举 `Glb1Unit`；内部 varint 编解码 |
| `data/market/glb1/Glb1Reader.kt` | 123 | GLB1 → `List<Candle>`，含魔数/版本/列校验 |
| `data/market/glb1/Glb1Writer.kt` | 77 | `List<Candle>` → GLB1（测试与数据管线复用） |
| `data/csv/CsvCandleParser.kt` | 224 | MT5 导出 CSV 解析，处理 BOM、分隔符、表头映射、时间解析 |
| `data/csv/CsvSchema.kt` | 33 | 列名/表头识别规则 |
| `data/db/GoldLabDatabase.kt` | 165 | 原生 `SQLiteOpenHelper`（**非 Room**），`DB_NAME="goldlab.db"`、`DB_VERSION=3`；8 张表：`account`、`trades`、`replay_state`、`settings`、`ad_state`、`daily_results`、`guess_stats`、`replay_bookmarks` |
| `data/repository/AccountRepository.kt` | 157 | 账户读写 |
| `data/repository/SettingsRepository.kt` | 110 | 键值设置（含 `KEY_ADS_TEST_MODE`） |
| `data/repository/ReplayStateRepository.kt` | 86 | 回放进度存档 |
| `data/repository/GuessStatsRepository.kt` | 72 | 猜涨跌统计 |
| `data/repository/AdStateRepository.kt` | 58 | 广告奖励次数/额度 |
| `data/ads/AdsRepository.kt` | 97 | `interface AdsRepository` + `DisabledAdsRepository` + `LocalTestAdsRepository`；`AdsRewardPolicy` 常量：`REWARD_PER_AD=200.0`、`DAILY_REWARD_CAP=4000.0`、`INTERSTITIAL_FREE_THRESHOLD=10`、`BONUS_THRESHOLD=20`、`BONUS_AMOUNT=1000.0`。**无真实 SDK** |

### 4.2 领域层 `domain/`

| 文件 | 行数 | 职责 |
|---|---|---|
| `domain/model/Candle.kt` | 33 | `data class Candle(timeMillis, open, high, low, close, volume=0L, spread=0.0)` + `isValid()`（校验时间戳、有限性、`high>=low`、`high>=open/close`、`low<=open/close`）。源码注释明写：**项目只有 OHLC，没有真实 tick**，盘中路径不可知 |
| `domain/model/Timeframe.kt` | 40 | `M1/M5/M15/M30/H1/H4/D1` + `bucketStart()`（M1 恒等、D1 按 `86_400_000` 对齐、其余取模）+ `fromName()` |
| `domain/model/CandleAggregator.kt` | 66 | 纯函数聚合：`aggregate(m1, tf)` / `mergeInto(last, m1, tf)`，按时间桶合并 OHLC+volume，**不插值** |
| `domain/model/SymbolSpec.kt` | 82 | 品种规格：`normalize(price)`、`normalizeLot(lot)`、`isValidLot()`、`usdPerBaseUnit(price)`、`usdPerQuoteUnit(price)`（货币换算） |
| `domain/model/SymbolSpecs.kt` | 103 | **9 个品种**：`XAUUSD`(100 oz, 3 位, 0.20)、`XAGUSD`(5000 oz, 3 位, 0.030)、`EURUSD/GBPUSD/USDCAD/USDCHF`(100k, 5 位)、`USDJPY`(100k, 3 位)、`BTCUSDT/ETHUSDT`(1 枚, 2 位, 杠杆 100)。未知品种兜底到黄金。**这是 `if symbol == ...` 式的硬编码品种表** —— 第二阶段通用引擎必须消灭这种写法（见 §10） |
| `domain/model/Account.kt` / `Order.kt` / `TradeStats.kt` | 19 / 56 / 35 | 账户、订单、统计 DTO |
| `domain/market/TickSynthesizer.kt` | 115 | `TICKS_PER_BAR=12`、`TICK_MILLIS=5_000L`；用 **bar.timeMillis 播种的 xorshift** 做 Brownian-bridge 形状插值，价格夹在 `[low, high]`，首 tick=open、末 tick=close。**确定性是为了报价可复现**，源码明确写「不声称还原真实路径」 |
| `domain/guess/GuessGame.kt` | 433 | 猜涨跌玩法主逻辑 |
| `domain/guess/GuessStops.kt` | 181 | 猜涨跌的止损/风控 |
| `domain/event/EventMarket.kt` | 151 | 事件合约市场/轮次模型 |
| `domain/event/EventPricing.kt` | 195 | `p = Φ((μ − 目标价)/σ)` 份额定价、TWAP 结算 |
| `domain/event/FixedOdds.kt` | 139 | 固定赔率（80% 支付率） |
| `domain/options/Options.kt` | 258 | 欧式期权 Black-Scholes 定价 + 希腊字母 + 波动率估计 |
| `domain/games/GamesEngine.kt` | 299 | 三个玩法的统一引擎（独立 USDT 权益） |
| `domain/games/GamesFeed.kt` | 101 | 玩法行情源（tick 驱动） |
| `domain/risk/RiskEngine.kt` | 124 | `checkOrder()`（保证金 + 止损距离校验）、`isStopOut()`（按 margin level）、`marginLevelText()` |
| `domain/money/PnlCalculator.kt` | 71 | `grossProfit`（经 `usdPerBaseUnit` 换算到 USD）、`commission`、`swap`、`netProfit`、`floating`、`formatMoney` |
| `domain/money/MarginCalculator.kt` | 51 | `requiredMargin`（`contractSize × volume × usdPerBaseUnit` / 杠杆）、`totalMargin`、`canOpen` |

### 4.3 引擎层 `engine/`（**注意：这里的 "engine" 是交易撮合/回放，不是行情生成**）

| 文件 | 行数 | 职责 |
|---|---|---|
| `engine/ReplayEngine.kt` | 220 | 回放时钟。`MIN_BAR_INTERVAL_MILLIS = 16L`、`replayIntervalMillis(baseBarMillis, speed)` 下限 16 ms；`data class ReplayState(loaded,totalBars,index,candle,playing,speed,finished,sourceName,isDemoData)`；单个 `Job` + `CoroutineStart.LAZY`（源码注释解释了 `immediate` 调度器导致「行情不会动」的根因）；`load/setSpeed(1,2,5,10)/play/pause/toggle/stepForward/reset/seek/indexForTime/isRunning`；回调 `onBar/onFinished/onError`。1 倍速 = 每秒 1 根 M1 |
| `engine/TradingEngine.kt` | 356 | 下单/平仓/批量平掉/修改持仓；`marketOrder` 走 `FillModel.entryPrice` + `RiskEngine.checkOrder` + 事务；`settle` 结算并写 `TradeRecord`；`OrderResult` 返回成功/拒单原因 |
| `engine/FillModel.kt` | 95 | `ask(bid, spread)`、`entryPrice/exitPrice`（多空加/减点差）、`evaluateBar()` 按 K 线判 SL/TP 触发（`ExitKind`、`BarExit`） |
| `engine/ReplayResume.kt` | 102 | 回放断点恢复 |

> **关键空白点**：全项目**不存在任何「行情生成」模块**。所有行情都来自预置 `.glb1` / `.csv` 回放；`TickSynthesizer` 只是在**已有 OHLC 内部插值**，不是生成新行情。`SymbolSpecs` 的硬编码品种表、`BundledMarketData` 的资产目录约定，是第二阶段要替换/抽象的目标。

### 4.4 指标 `indicators/`

| 文件 | 行数 | 职责 |
|---|---|---|
| `indicators/Indicators.kt` | 515 | 纯函数指标实现（`NA = Double.NaN` 表达未定义）：`ema`、`rsi`、`trueRange`、`atr`、`obv`、`lastValid`、`tailValid` 等 |
| `indicators/IndicatorCatalog.kt` | 232 | **17 个指标定义**：`sma`、`ema`、`wma`、`boll`、`donchian`、`sar`、`vol`、`macd`、`rsi`、`kdj`、`atr`、`adx`、`cci`、`willr`、`mom`、`roc`、`obv`；含 `IndicatorPane{MAIN,SUB}`、`OutputStyle{LINE,HISTOGRAM,POINTS}`、参数 clamp 与默认实例 |
| `indicators/IndicatorConfig.kt` | 145 | 指标实例配置的序列化/校验 |

### 4.5 统计 `stats/`

`stats/PerformanceAnalyzer.kt`（113 行）：`analyze(closedTrades, initialBalance)` 产出 equity curve、最大回撤、胜率、盈亏比、profit factor；`dailyRealizedPnl(trades, dayKey)`、`dayKeyOf(millis)`（**UTC 跨日**）。

### 4.6 UI 层 `ui/`（Compose，共 ~1,700 行）

- 图表：`chart/CandleChart.kt`(543)、`chart/ChartMath.kt`(326)、`chart/SubPane.kt`(159)、`chart/IndicatorMapper.kt`(116)、`chart/EquityCurve.kt`(61)、`chart/ChartSeries.kt`(53)、`chart/ChartText.kt`(26)。
- 页面：`screens/TradeTabScreen.kt`(901)、`screens/GuessScreen.kt`(843)、`screens/OptionGameScreen.kt`(390)、`screens/SettingsScreen.kt`(387)、`screens/EventGameScreen.kt`(349)、`screens/FixedOddsGameScreen.kt`(317)、`screens/IndicatorScreen.kt`(247)、`screens/LogScreen.kt`(224)、`screens/AboutScreen.kt`(213)、`screens/HomeScreen.kt`(207)、`screens/GamesHubScreen.kt`(175)、`screens/DataScreen.kt`(164)、`screens/HistoryScreen.kt`(115)、`screens/StatsSection.kt`(111)、`screens/GamesClock.kt`(79)、`screens/GamesMarketPicker.kt`(74)、`screens/GamesChart.kt`(61)、`screens/GamesTick.kt`(52)、`screens/GamesMarket.kt`(38)。
- 主题：`theme/Tokens.kt`(56，间距/圆角/字号令牌)、`Color.kt`(37)、`Theme.kt`(36)、`Type.kt`(34)。
- 其它：`ui/GoldLabApp.kt`(163，导航图)、`ui/FeatureFlags.kt`(22，`GUESS_ENABLED = true`)、`components/Common.kt`(192)、`format/Formatters.kt`(49)。

### 4.7 应用/视图层

- `MainActivity.kt`(35)、`GoldLabApplication.kt`(14)、`di/AppContainer.kt`(41，手写依赖容器，无 Hilt/Koin)。
- `viewmodel/GameViewModel.kt`(853 行 / 39.4 KB)：`GameUiState` 有 ~35 个字段（account、spec、candle、replay、positions、history、stats、indicatorConfig、overlays、subPanes、bookmark、bundledMonths、bars、timeframe、message、stopOut、marginLevelText…）；持 `GamesEngine` 与 `GuessController`；内含 `rebuildWindow`/`buildWindow(tf, endIndex)` **按周期现场聚合**。源码注释确认：行情源只有内置月份，「没有导入、没有本地目录、没有 DEMO」。
- `viewmodel/GuessController.kt`(514 行)：猜涨跌控制器，`private val rng = Random(System.currentTimeMillis())`。
- `util/GoldLabLog.kt`(156 行)：自建日志（写文件 + 供 FileProvider 分享）。

---

## 5. 已有功能清单（源码 + 文档双向确认）

### 5.1 交易模拟（核心）
- 历史回放交易：1 倍速 = 每秒 1 根 M1，支持 1/2/5/10 倍速、暂停/继续/单步/跳转/断点恢复。
- 9 个品种（黄金、白银、4 个外汇直盘、USDJPY、BTC/ETH），逐品种合约规格与点位精度。
- 市价单、点差（从行情逐日点差读取，表内值仅兜底）、佣金、隔夜利息（`swap` 参数存在但按 0 计）、保证金、强平（margin level）、SL/TP 按 K 线判定。
- 7 个周期 M1–D1，全部由 M1 现场聚合。
- 17 个技术指标 + 主图叠加 + 副图 + 自选与调参；权益曲线；绩效统计（胜率/盈亏比/Profit Factor/最大回撤）。
- 原生 SQLite 持久化账户、持仓、成交、回放进度、书签、设置、每日结果、猜涨跌统计。

### 5.2 三个玩法（V-0.0.8 新增，独立 USDT 权益，初始 10,000）
1. **事件合约（份额制 / Gate 口径）**：5m/15m/1h/4h；份额价 0.01–0.99 = 隐含概率；结算价 = 周期内全部 1m K 线等权均价（TWAP）；`p = Φ((μ − 目标价)/σ)`，μ 为时间加权均值、σ 随窗口填满收敛；手续费 0.1%/边；最小 6 张 / 5 USDT。
2. **事件合约（固定赔率 / Binance 口径）**：支付率 80%（盈亏平衡胜率 55.56%）；10m/30m/1h/4h/1d；持平退本金；单笔 5–250 USDT 且受权益 5% 约束。
3. **欧式期权**：只能买方，最大亏损 = 权利金；1h/4h/1d/3d/7d/30d；行权价平值 ±8 档（间距按期限取现价 0.25%/0.75%/1.5%/3%）；Black-Scholes + 实测年化波动率；展示 delta/theta/vega/IV；结算价 = 到期前 min(30, 期限) 根 1m 均价；行权费 = `min(结算价值 × 0.015%, 实值额 × 10%)`；期限按剩余可回放长度过滤（避免等不到结算）。
4. 通用护栏：单笔 ≤ 权益 5%（下限 5 USDT）、日亏 ≤ 权益 20%（UTC 跨日重置）。

### 5.3 其它
- 模拟 Tick（默认开）：1 分钟 = 12 个 5 秒 tick，现实 1 秒 = 行情 5 秒；现价/报价/未收完 K 线都按 tick 更新。
- 广告奖励**策略层**（`AdsRewardPolicy`：每条 200、日上限 4000、看满 10 条免插屏、第 20 条奖 1000）与**桩实现**；真实 SDK 未接。
- 关于页、日志页（可分享）、数据页、设置页、历史页。
- 设计令牌层 + Modern Dark 色板（8 组 WCAG 对比度实算达标，见 `docs/UI-REFACTOR.md`）。

---

## 6. 数据格式（已读实现，非推测）

### 6.1 输入 CSV（MT5 导出）
由 `data/csv/CsvCandleParser.kt` + `CsvSchema.kt` 解析：处理 BOM、分隔符探测、表头列名映射、时间列解析。列至少含 `time/open/high/low/close`，点差与成交量可选。文件名约定携带品种与时间范围，例如 `EURUSDm_M1_202410180000_202610162058.csv`。

### 6.2 内置格式 GLB1（自研，列式 + varint + 增量编码）
- Magic `"GLB1"`，`VERSION = 1`，`HEADER_FIXED = 30` 字节，`COLUMN_TABLE_BYTES = 28`。
- 列：`TIME_DELTA`(ms 增量)、`OPEN_DELTA`、`HIGH_DELTA`、`LOW_DELTA`、`CLOSE_DELTA`（价格 tick 增量）、`VOLUME`(ticks traded)、`SPREAD_DELTA`。
- 单位枚举：`MILLISECONDS` / `PRICE_TICKS` / `TICKS_TRADED`；`MIN_DIGITS=0`、`MAX_DIGITS=8`；`SPREAD_SANITY_DENOMINATOR=100`（点差合理性上限）。
- 价格全程走 `toTicks/toPrice`（`scale(digits)` 缩放），**全项目唯一换算入口**，这是历史上「点差单位错致入场价抬高 200 美元」事故后的修复产物。
- 源码注释记录的实测收益：**CSV+deflate 18.83 MB → GLB1 约 6.6 MB；单月加载 161 ms → 2 ms**（此为项目自述数值，本次审计**未重新测量**，标注为未验证）。
- 目录约定：`assets/market/<SYMBOL>/<yyyy-MM>.glb1`；**XAUUSD 是历史遗留的平铺目录** `assets/market/<yyyy-MM>.glb1`，`dirsFor()` 只对它允许回退。

### 6.3 数据资产实况

| 位置 | 内容 | 体积 |
|---|---|---|
| `app/src/main/assets/market/` | XAUUSD 33 个月（2024-01~2026-09）；BTCUSDT/ETHUSDT 各 37 个月（2023-10~2026-10）；EURUSD/GBPUSD/USDCAD/USDCHF/USDJPY/XAGUSD 各 25 个月 | **257 个文件 / 87.1 MB** |
| `market-data/` | XAUUSD 原始 CSV 33 个（GLB1 的源） | 67.8 MB |
| `历史数据/` | 6 个品种原始 MT5 导出 CSV | 253.1 MB |
| `tools/USDCADm_M1_...csv` | 与 `历史数据/` 中同名文件**重复** | 45.1 MB |
| `recon/` | `golden-kotlin-2024-01.csv`(7 KB)、`indicator-golden.csv`(251 KB)，指标对拍用 | 0.2 MB |

**纯数据合计约 453 MB，代码+资源仅约 1.6 MB。** 数据量与代码量之比约 280:1 —— 这是入库策略必须区别对待的根本原因。

---

## 7. 数据来源与许可（**本次审计的最高风险项**）

### 7.1 项目自述

`BundledMarketData.kt` 的文档段与交付报告均声明：数据由项目所有者从**自己的 Exness MT5 模拟账户**导出；Exness 客服曾**口头/在线答复**「可以导出、可以分发给第三方」。

### 7.2 审计结论

| 问题 | 结论 |
|---|---|
| 是否有书面许可？ | **没有**。项目内无任何授权文件、邮件、工单截图或条款摘录 |
| 客服对话记录是否留存？ | **没有**。项目内不存在该对话的任何副本 |
| 是否可核对 Exness 现行条款？ | 本次审计**未**核对外部条款（离线审计，且条款随时可变） |
| 能否据此公开再分发？ | **不能。** 口头答复既无法举证，也不构成有效授权；即便当时有效，也无法证明覆盖「公开 GitHub 仓库无限再分发」这一场景 |
| 是否会影响 App 上架？ | 另属问题：应用商店对行情数据来源有自己的合规要求，需单独确认（本次不展开） |

### 7.3 因此的处置（硬约束）

> **原始 CSV 与由其派生的 GLB1 一律不进入公开仓库。**
> 仓库只保留：**数据格式定义、解析器、导入工具、生成脚本、小体量 golden 样本（`recon/` 0.2 MB）、以及 `datasets/README.md` 中的来源/格式/获取方式说明**。
> 使用者若要跑完整数据，需自行从自己的经纪商账户导出 CSV，再执行既有生成流程（见 §8.3）。这条路径**不损失任何可复现性**：转换代码全部在仓库里。

该处置对应总指令 §47「不能确认可以公开的数据不要擅自上传，应保留数据导入接口并在文档中说明获取方式」。

---

## 8. 依赖、构建入口与数据管线

### 8.1 运行时依赖（全部）

```
compose-bom 2024.10.01
androidx.core:core-ktx 1.13.1
androidx.activity:activity-compose 1.9.3
androidx.lifecycle:lifecycle-runtime-ktx 2.8.7
androidx.lifecycle:lifecycle-viewmodel-compose 2.8.7
androidx.compose.ui / ui-graphics / ui-tooling-preview / material3
androidx.navigation:navigation-compose 2.8.4
（测试）junit 4.13.2、org.jetbrains.kotlinx:kotlinx-coroutines-test 1.9.0
```

**零第三方运行时依赖**——没有 Retrofit/OkHttp、没有 kotlinx-serialization、没有 Room、没有 Hilt、没有广告/统计 SDK。AndroidX 版本集中在 `app/build.gradle.kts` 内硬编码，未抽 `libs.versions.toml`（版本目录）。

### 8.2 无网络

`AndroidManifest.xml` 无 `INTERNET` 权限，仅 `MainActivity` + `FileProvider`。**这不是遗漏，而是产品定位（纯离线模拟器）**。第二阶段若引入需要下载数据的引擎，必须显式增加权限并说明——目前不应擅自加。

### 8.3 数据管线（已存在，可复用）

- 生成 GLB1：`.\gradlew.bat testDebugUnitTest -Dgoldlab.genMarket=1 --tests com.fxsimulator.Glb1RoundTripTest`（读 `market-data/*.csv` → 写 `assets/market/*.glb1`）
- 导入 Binance：`-Dgoldlab.importBinance=<dir>`
- 图标：`python tools/icon_fx.py app/src/main/res`、`python tools/make_icons.py`
- 指标对拍：`python tools/verify_python_indicators.py`（纯标准库，无 PIL/numpy）

这套管线目前耦合在 **Gradle 测试任务**里（用系统属性触发），而不是独立的 `tools/data_pipeline`。第二阶段按目标结构抽成独立 Python 入口时，须保留「转换逻辑单一实现」，避免出现两套编码器。

---

## 9. 已知问题与风险（按严重度排序）

| # | 严重度 | 问题 | 依据 |
|---|---|---|---|
| P0 | **阻断** | 行情数据无书面许可 → 不能公开上传 | §7 |
| P0 | **阻断** | 目标 GitHub 仓库地址未知 | 本地无 `.git`、无 remote；需用户指定 |
| P1 | 高 | 无 `README.md`：仓库无法自解释（怎么构建、数据怎么来、有哪些限制） | 根目录列举 |
| P1 | 高 | 无 CI（GitHub Actions），无 Lint/Detekt/ktlint 配置；`273` 个测试只能手动跑 | 无 `.github/`、无相关插件 |
| P1 | 高 | 无版本标签/分支模型；版本号在文档与构建间已不一致（`构建说明.md` 写 V-0.04/versionCode 6，构建实际是 `V-0.0.8`/`versionCode 8`） | 文档 vs `app/build.gradle.kts` |
| P1 | 高 | 87.1 MB 资产 + 453 MB 原始数据混在项目目录，仓库边界不清 | §6.3 |
| P2 | 中 | `SymbolSpecs.kt` 用 `when(symbol)` 硬编码 9 个品种 —— **第二阶段通用引擎必须消灭的耦合点** | §4.2 |
| P2 | 中 | `GameViewModel` 853 行 / ~35 字段单状态类，UI 与业务边界偏厚 | §4.7 |
| P2 | 中 | 无 `libs.versions.toml`，依赖版本散落 | §8.1 |
| P2 | 中 | `android.defaults.buildfeatures.buildconfig=true` 已弃用（AGP 9 移除），每次构建告警 | `gradle.properties` |
| P2 | 中 | 中文/大小写混用的目录名（`历史数据`、`APk`、`【私密】keystore`）跨平台风险 | §3 |
| P2 | 中 | UI 层零自动化测试；无 `androidTest` | §1.2 |
| P2 | 中 | release 未混淆（有意为之，但意味着 APK 内代码可被直接反编译阅读） | `proguard-rules.pro` |
| P3 | 低 | `tools/USDCADm_M1_...csv` 45 MB 重复副本 | §6.3 |
| P3 | 低 | 根目录 8 个 `.tmp_*.png` + 14 个 `.tmp_*.xml` + 4 张截图 = 调试垃圾（约 3.4 MB） | 根目录列举 |
| P3 | 低 | `swap` 参数存在但恒为 0（隔夜利息未真正实现） | `TradingEngine.settle` |
| P3 | 低 | `AdsRepository` 只有桩实现，`FeatureFlags.GUESS_ENABLED` 硬编码 | §4.1 / §4.6 |
| P3 | 低 | 交付文档散落根目录、含 `.docx`/`.html`/`.txt` 多份重复内容 | §3 |

**未验证项（明确标注，不臆测）**：
- GLB1 的体积/加载速度收益（18.83 MB → 6.6 MB、161 ms → 2 ms）来自项目自述，本次未重测。
- release APK 体积 13.38 MB、签名指纹来自 `构建说明.md`，本次未重建 release（无签名需求，且不应触碰 `【私密】keystore`）。
- 三个玩法的数值口径（80% 支付率、0.1%/边手续费、行权费公式）与源码常量一致，但**未与 Gate/Binance 的现行规则逐条比对**（外部条款，离线审计不可达）。

---

## 10. 对第二阶段（通用引擎）的直接启示

1. **唯一干净的接入点**：`data/market/BarSource.kt` 的 `interface BarSource`。合成引擎只需实现该接口（或在其上再抽一层通用 `BarSource`），即可在不改 UI 的前提下替换行情来源。这是「旧逻辑先保留、验证通过再替换」的最低风险路径。
2. **必须消灭的耦合**：`SymbolSpecs.of()` 的 `when(symbol)` 硬编码、`BundledMarketData.dirsFor()` 的 XAUUSD 特例。目标结构要求「可扩展的品种配置（`symbol/asset_class/price_tick_size/price_precision/base_timeframe/trading_calendar/session_timezone/gap_model/volatility_profile/data_source/model_profile`）」，正好对应这两处的病灶。
3. **精确定价基础已具备**：GLB1 的 `toTicks/toPrice` 证明项目已经理解「整数 tick 表达价格、避免浮点破坏 OHLC 约束」。Rust 核心应直接采用整数 tick（对应总指令 §148）。
4. **可复现性要求已有先例**：`TickSynthesizer` 用 `bar.timeMillis` 播种 xorshift 换取「报价可复现」。通用引擎的 seed 语义应比它更严格（显式 seed + 快照含 RNG 状态）。
5. **无 tick 数据是硬事实**：项目只有 OHLC。合成引擎的「盘中路径」只能是模型生成，**不得声称还原真实 tick**。这必须在 `docs/MODEL_VALIDATION.md` 与用户可见文案中保持一致。
6. **零第三方依赖的传统**：现有工程能做到零运行时第三方依赖，通用引擎的 Android 适配层也应尽量维持（JNI 或 UniFFI 择一，不叠加多套桥接）。

---

## 11. 入库清单（本次审计产出，供第二阶段核对）

### 11.1 应入库
`app/**`（源码、res、build.gradle.kts、proguard、Manifest）、`gradle/**`、`gradlew`、`gradlew.bat`、`settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`.gitignore`、`docs/**`、`recon/**`（0.2 MB golden 样本）、`tools/*.py`（3 个脚本）、`README.md`（待新建）、`.gitattributes`（待新建，处理中文/换行）。

### 11.2 不得入库
`【私密】keystore/**`（真实密钥）、`local.properties`、`build/**`、`.gradle/**`、`.kotlin/**`、`*.apk`、`market-data/**`、`历史数据/**`、`app/src/main/assets/market/**`（**许可未定，87.1 MB**）、`tools/USDCADm*.csv`、`APk/**`、所有 `.tmp_*`、4 张 `Screenshot_*.jpg`。

### 11.3 待用户决定
1. 目标 GitHub 仓库（owner/name、public/private、是否允许我创建）。
2. `app/src/main/assets/market/**` 是否可随仓库公开（默认**否**，见 §7.3）。
3. 是否保留历史发布 APK 与商店文案于仓库（默认移到 `docs/release/` 或剔除）。

---

*本报告的所有「实测」结论均可复现：构建/测试命令见 §1.2，目录与体积统计命令基于 `Get-ChildItem -Recurse | Measure-Object Length -Sum`。*
