# FX模拟器 / GoldLab

一个**纯离线**的金融市场历史回放模拟器（Android）。用内置的历史 K 线按时间顺序"重放"行情，在回放出来的行情上做保证金交易、猜涨跌、事件合约与期权，全部为虚拟记账，不涉及真实资金。

- 包名：`com.fxsimulator.app` ｜ 应用名：FX模拟器 ｜ 工程名（Gradle）：`GoldLab`
- 当前版本：`versionCode 8` / `versionName V-0.0.8`
- 技术栈：Kotlin · Jetpack Compose · 原生 SQLite · 自研列式二进制行情格式（GLB1）
- **无第三方运行时依赖**，**无 INTERNET 权限**（App 内不联网）

---

## 一、这个仓库里有什么

| 目录 | 内容 |
|---|---|
| `app/` | Android 应用（Kotlin + Compose），全部生产代码与 JVM 单元测试 |
| `docs/` | 审计报告、构建说明、更新日志、交付报告、UI 设计令牌记录 |
| `docs/legal/` | 隐私政策、用户协议（docx + html） |
| `docs/store/` | 应用商店文案、TapTap 文案、商店图标 |
| `datasets/` | **行情数据的来源、格式与导入说明**（数据本体不入库，见下） |
| `tools/` | 图标生成、指标对拍脚本（Python，纯标准库） |
| `engine/` | **预留**：通用金融价格模拟引擎 `MarketSim Core`（第二阶段，尚未实现） |
| `tests/` | **预留**：引擎的跨语言测试与验收用例 |
| `recon/` | 指标实现的黄金对拍样本（小体量，可入库） |

---

## 二、行情数据：为什么仓库里没有

内置行情来自 MT5 导出的历史 CSV（`EURUSD` / `GBPUSD` / `USDCAD` / `USDCHF` / `USDJPY` / `XAGUSD` / `XAUUSD`，以及 Binance 的 `BTCUSDT` / `ETHUSDT`）。**这些数据能否公开再分发无法确认**（详见 `docs/PROJECT_AUDIT.md` §7），因此：

- **原始 CSV 与派生的 `.glb1` 一律不入库**（`.gitignore` 已排除，共约 450 MB）。
- 仓库保留**格式定义、解析器、编码器与转换入口**，所以完整可复现。

自己跑起来只需三步，详见 [`datasets/README.md`](datasets/README.md)：

```bash
# 1) 把 MT5 导出的 CSV 放进 market-data/（XAUUSD）或 历史数据/（其它品种）
# 2) 生成内置 GLB1 资产
./gradlew testDebugUnitTest -Dgoldlab.genMarket=1 --tests com.fxsimulator.Glb1RoundTripTest
# 3) 构建
./gradlew assembleDebug
```

没有数据时 App 仍能构建、启动，只是行情列表为空。

---

## 三、构建

环境要求：**JDK 21**、Android SDK（compileSdk 35 / buildTools 35.0.0）。`local.properties` 里写 `sdk.dir=<你的 SDK 路径>`，该文件不入库。

```bash
./gradlew assembleDebug          # 调试包
./gradlew :app:testDebugUnitTest # JVM 单元测试
```

- 项目自带 Gradle Wrapper 8.11.1，无需预装 Gradle。
- **无签名密钥也能构建**：`app/build.gradle.kts` 找不到 `keystore/keystore.properties` 或 `【私密】keystore/keystore.properties` 时会跳过签名，因此 release 之外的构建在任何机器上都可复现。
- 更细的环境说明与历史注意事项见 [`docs/BUILD.md`](docs/BUILD.md)。

### 已验证的构建/测试状态

| 项目 | 结果 | 环境 |
|---|---|---|
| `:app:testDebugUnitTest` | **273 个用例 / 19 个 suite / 0 失败 / 0 错误 / 0 跳过** | Gradle 8.11.1 · JDK 21.0.10 · Windows 11 |
| `assembleDebug` | 成功（导入源码时的仓库基线） | 同上 |

> 测试全部是 JVM 单元测试（`app/src/test`），覆盖数据格式、聚合、回放、撮合、风控、指标、玩法定价与统计。**UI 层没有自动化测试。**

---

## 四、代码地图（想改哪儿看哪儿）

```
app/src/main/java/com/fxsimulator/
├── data/          CSV 解析、GLB1 编解码、SQLite、内置行情装载、广告桩
├── domain/        领域模型（Candle/Timeframe/聚合/品种规格）、风控、盈亏与保证金、玩法（猜涨跌/事件合约/期权）
├── engine/        ReplayEngine（回放时钟）、TradingEngine（撮合）、FillModel（点差与 SL/TP）、断点恢复
├── indicators/    17 个技术指标的纯函数实现 + 指标目录与参数
├── stats/         绩效分析（胜率/盈亏比/Profit Factor/最大回撤/权益曲线）
├── ui/            Compose 图表与页面、设计令牌、格式化
├── viewmodel/     GameViewModel（853 行）、GuessController
└── util/          自建日志
```

关键接口：**`data/market/BarSource.kt`** 的 `interface BarSource` 是全项目唯一的行情输入抽象点 —— 未来的合成行情引擎接在这一层，不必动 UI。

---

## 五、已知限制（如实记录）

1. **行情是历史回放，不是实时行情**；无网络能力。
2. **盘中路径是模型插值，不是真实 tick**。项目只有 OHLC，没有逐笔数据，`TickSynthesizer` 生成的是"形状可信、路径不可考"的确定性插值。
3. 玩法权益与持仓**仅保存在本次运行内**，重启 App 重置。
4. 事件合约**不支持提前平仓**。
5. 隔夜利息（swap）参数存在但恒为 0。
6. 广告只有策略层与本地桩，**未接入真实广告 SDK**。
7. release 构建**有意不混淆**（为了崩溃堆栈可读），因此 APK 内代码可被反编译阅读。
8. 全部为虚拟记账单位，**不涉及真实资金，不构成任何投资建议**。

---

## 六、路线图

- [x] **阶段一**：审计现有源码 → 清理结构与 Git → 上传 → 验证（本仓库）
- [ ] **阶段二**：在 `engine/` 下开发通用金融 OHLC 价格模拟引擎 **MarketSim Core**
  - Rust 核心（整数 tick 定价、随机种子、HSMM 市场状态、动态波动率、多周期聚合、快照恢复）
  - Python 数据管线与统计验证（样本外分离，不泄漏未来数据）
  - Kotlin/JNI 适配层，实现 `BarSource`，与现有 UI 解耦
  - 引擎不得包含任何《FX模拟器》业务规则，也不得硬编码黄金

详细的阶段计划、约束与验收标准见 [`docs/PROJECT_AUDIT.md`](docs/PROJECT_AUDIT.md) §10 与 `docs/ENGINE_ARCHITECTURE.md`（阶段二产出）。

---

## 七、文档索引

| 文档 | 说明 |
|---|---|
| [`docs/PROJECT_AUDIT.md`](docs/PROJECT_AUDIT.md) | **项目审计报告**：真实结构、模块职责、依赖、问题与风险（逐文件读源码后写成） |
| [`docs/BUILD.md`](docs/BUILD.md) | 构建与环境说明、历史注意事项 |
| [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | V-0.0.8 更新日志 |
| [`docs/reports/DELIVERY-REPORT.md`](docs/reports/DELIVERY-REPORT.md) | 完整交付报告（含版本历史 0.1.1 → V-0.04） |
| [`docs/UI-REFACTOR.md`](docs/UI-REFACTOR.md) | 设计令牌与色板接入记录（含 WCAG 对比度实算） |
| [`datasets/README.md`](datasets/README.md) | 行情来源、CSV/GLB1 格式、导入与生成步骤 |

---

## 八、许可

代码尚未声明开源许可证。**在补充 LICENSE 之前，默认保留所有权利（All rights reserved）。**

行情数据不随仓库分发，其权利归原始数据提供方，本项目不对其再分发作任何授权。
