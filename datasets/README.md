# 行情数据说明（datasets）

本仓库**不包含**行情数据本体。这里只说明数据的来源、格式与重建方法。

---

## 1. 数据从哪来

| 品种 | 来源 | 原始格式 | 覆盖范围（项目历史上使用过的） |
|---|---|---|---|
| `XAUUSD` | 经纪商 MT5 终端导出（Exness 模拟账户） | MT5 "导出到 CSV"（M1） | 2024-01 ~ 2026-09，33 个月 |
| `XAGUSD`、`EURUSD`、`GBPUSD`、`USDCAD`、`USDCHF`、`USDJPY` | 同上 | 同上 | 各约 25 个月 |
| `BTCUSDT`、`ETHUSDT` | Binance 现货 K 线导出 | CSV（K 线） | 2023-10 ~ 2026-10，各 37 个月 |

这些是**历史行情**，用于回放式模拟，**不是实时行情**。App 不联网。

### 为什么数据不在仓库里

行情数据的分发权归原始提供方。本项目**没有任何书面授权**可以公开再分发这些数据（详见 [`../docs/PROJECT_AUDIT.md`](../docs/PROJECT_AUDIT.md) §7），因此：

- 原始 CSV、以及由它派生的 `.glb1` 二进制，**都不入库**。
- 但**格式定义、解析器、编码器、转换命令全部入库**，所以拿到数据的人可以完整重建，结果逐字节可复现。

> 换句话：仓库缺的是"数据"，不缺"处理数据的能力"。

---

## 2. 自己准备数据

### 2.1 从 MT5 导出（外汇 / 贵金属）

1. 打开 MT5 → `工具` → `选项` → `图表`，把"历史数据最大柱数"调大。
2. 在 `市场报价` 里右键目标品种 → `图表窗口`，把周期切到 **M1**，向左滚动加载足够历史。
3. `工具` → `选项` → `图表` → 或直接在主图表上 `Ctrl+S` / 右键 `保存为`，格式选 **CSV**。
4. 把文件放进项目根的 `market-data/`（XAUUSD）或 `历史数据/`（其它品种）。

文件名建议带品种与时间范围，例如 `EURUSDm_M1_202410180000_202610162058.csv`。解析器会做表头映射与分隔符探测，列至少要有时间、开、高、低、收；点差与成交量可选。

### 2.2 从 Binance 导出（加密）

把 K 线 CSV 放到任意目录，用测试任务透传目录参数导入：

```bash
./gradlew testDebugUnitTest -Dgoldlab.importBinance=<你的目录>
```

---

## 3. 生成内置资产（GLB1）

App 运行时读的是 `app/src/main/assets/market/**/*.glb1`（列式二进制），不是 CSV。转换由仓库内的编码器完成：

```bash
./gradlew testDebugUnitTest -Dgoldlab.genMarket=1 --tests com.fxsimulator.Glb1RoundTripTest
```

该测试会：

1. 读 `market-data/*.csv`；
2. 校验数据（`Candle.isValid()`：时间戳、有限性、`high>=low`、`high>=open/close`、`low<=open/close`）；
3. 编码为 `.glb1` 写入 `app/src/main/assets/market/`；
4. 再读回来做 round-trip 比对 —— **转换结果正确性由测试保证**，不是靠人眼看。

对应源码：

| 角色 | 文件 |
|---|---|
| 格式常量 / 唯一价格换算入口 | `app/src/main/java/com/fxsimulator/data/market/glb1/Glb1Format.kt` |
| 编码器 | `.../glb1/Glb1Writer.kt` |
| 解码器 | `.../glb1/Glb1Reader.kt` |
| 装载与目录约定 | `.../data/market/BundledMarketData.kt` |
| CSV 解析 | `.../data/csv/CsvCandleParser.kt`、`CsvSchema.kt` |
| round-trip 测试 | `app/src/test/java/com/fxsimulator/Glb1RoundTripTest.kt` |

---

## 4. 目录与命名约定

```
app/src/main/assets/market/
├── 2024-01.glb1            ← XAUUSD 历史遗留：平铺（仅 XAUUSD 允许）
├── 2024-02.glb1
├── XAGUSD/2024-01.glb1     ← 其它品种：按品种子目录
├── EURUSD/2024-01.glb1
├── BTCUSDT/2023-10.glb1
└── ETHUSDT/2023-10.glb1
```

- 文件名固定为 `yyyy-MM.glb1`。
- **只有 XAUUSD 允许退回平铺目录**。这是刻意的防错设计：如果所有品种都允许平铺，读不到子目录时会静默把黄金价格当成外汇价格，盈亏会被放大到百万级（源码 `BundledMarketData.dirsFor()` 注释记录了该事故）。
- 装载优先级：`.glb1` → `.csv` → `.csv.gz`。

---

## 5. GLB1 格式速览

自研列式二进制，目的是让 87 MB 的 CSV 派生成约 25 MB 且单月加载从百毫秒级降到毫秒级（该收益为首版实测记录，本次审计未重测）。

```
magic "GLB1" | version=1 | header 30B | 列描述表 28B/列 | varint 编码的列数据
```

- 7 个列：`TIME_DELTA`（毫秒增量）、`OPEN/HIGH/LOW/CLOSE_DELTA`（价格 tick 增量）、`VOLUME`（ticks traded）、`SPREAD_DELTA`。
- 价格全程以**整数 tick** 表示（`toTicks` / `toPrice`），避免浮点误差破坏 OHLC 约束 —— 这也是未来 Rust 引擎沿用整数 tick 的先例。
- 单位枚举：`MILLISECONDS` / `PRICE_TICKS` / `TICKS_TRADED`。

> 第二阶段会把该格式的**规格**（而非数据）正式写入 `docs/DATA_FORMAT.md`，并让 Rust 核心与 Kotlin 侧共用同一套字段语义。

---

## 6. 数据使用边界

- 数据属于原始提供方，**本仓库不授予任何再分发权**。
- 数据仅用于个人学习与模拟器开发。
- `recon/` 下的两个小体量 golden 样本（7 KB / 251 KB）是**指标算法对拍用的派生结果**，不含完整行情序列；若原始权利方要求，可随时移除。
