# market/ 目录说明

本目录存放 App 内置的行情资产（`*.glb1`），**不随仓库分发**（`.gitignore` 已排除）。

原因：行情数据的分发权归原始提供方，本项目无书面再分发授权，详见
[`../../../../../datasets/README.md`](../../../../../datasets/README.md)
与 [`../../../../../docs/PROJECT_AUDIT.md`](../../../../../docs/PROJECT_AUDIT.md) §7。

## 重建方式

```bash
# 1) 把 MT5 导出的 M1 CSV 放进项目根的 market-data/（XAUUSD）或 历史数据/（其它品种）
# 2) 生成
./gradlew testDebugUnitTest -Dgoldlab.genMarket=1 --tests com.fxsimulator.Glb1RoundTripTest
```

## 目录约定

- XAUUSD（历史遗留）：平铺 `market/yyyy-MM.glb1`
- 其它品种：`market/<SYMBOL>/yyyy-MM.glb1`
- 只有 XAUUSD 允许平铺；其它品种平铺会被刻意拒绝，避免把黄金价格当外汇价格。
