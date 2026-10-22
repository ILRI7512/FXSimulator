"""用真实数据对账：Python 的 EMA/RSI/ATR 是否与 Kotlin 一致。

为什么要这个脚本：
    Python 策略层如果指标算法与 Kotlin 有偏差，回测出来的信号就会不一样，
    而"差一点点"在指标里非常隐蔽（ATR 第 0 根口径差 1 根，整条止损链都会错位 —— 已经在
    阶段 5 出过一次这种事）。所以这里直接拿 Kotlin 生成的黄金值逐点比对。

数据来源：
    - 原始行情：market-data/2024-01.csv（与 App 内置资产同源）
    - Kotlin 基准：recon/indicator-golden.csv（IndicatorGoldenTest 生成）

运行：
    python tools/verify_python_indicators.py
"""

import csv
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python"))

from quant_api import strategy  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
BARS = os.path.join(ROOT, "market-data", "2024-01.csv")
GOLDEN = os.path.join(ROOT, "recon", "indicator-golden.csv")

# Kotlin 侧的默认参数（见 IndicatorCatalog）
PERIODS = {"trend.ema.0": 20, "momentum.rsi.0": 14, "volatility.atr.0": 14}


def load_bars(path):
    o, h, l, c = [], [], [], []
    with open(path, "r", encoding="utf-8-sig", newline="") as f:
        reader = csv.reader(f)
        next(reader)  # 表头（UTF-8，带 BOM 也能读）
        for row in reader:
            if len(row) < 7:
                continue
            o.append(float(row[3]))
            h.append(float(row[4]))
            l.append(float(row[5]))
            c.append(float(row[6]))
    return o, h, l, c


def load_golden(path):
    data = {}
    with open(path, "r", encoding="utf-8") as f:
        reader = csv.reader(f)
        next(reader)
        for key, idx, val in reader:
            data.setdefault(key, {})[int(idx)] = None if val == "nan" else float(val)
    return data


def main():
    if not os.path.exists(BARS):
        print("缺少行情文件：", BARS)
        return 1
    if not os.path.exists(GOLDEN):
        print("缺少基准文件：", GOLDEN, "（先跑 IndicatorGoldenTest）")
        return 1

    o, h, l, c = load_bars(BARS)
    golden = load_golden(GOLDEN)
    print("行情 %d 根，基准 %d 条序列" % (len(c), len(golden)))

    mine = {
        "trend.ema.0": strategy.ema(c, PERIODS["trend.ema.0"]),
        "momentum.rsi.0": strategy.rsi(c, PERIODS["momentum.rsi.0"]),
        "volatility.atr.0": strategy.atr(h, l, c, PERIODS["volatility.atr.0"]),
    }

    failed = False
    for key, series in mine.items():
        want = golden.get(key)
        if not want:
            print("  %-20s 基准里没有这条序列，跳过" % key)
            continue
        checked = 0
        bad_nan = 0
        max_abs = 0.0
        max_rel = 0.0
        worst = None
        for idx, expected in want.items():
            got = series[idx] if idx < len(series) else None
            checked += 1
            if (expected is None) != (got is None):
                bad_nan += 1
                if bad_nan <= 3:
                    print("    %s[%d] 缺失不一致：Kotlin=%r Python=%r" % (key, idx, expected, got))
                continue
            if expected is None:
                continue
            d = abs(got - expected)
            rel = d / max(1.0, abs(expected))
            if d > max_abs:
                max_abs = d
            if rel > max_rel:
                max_rel = rel
                worst = (idx, got, expected)
        ok = bad_nan == 0 and max_rel <= 1e-9
        status = "OK" if ok else "MISMATCH"
        print("  %-20s %-8s 点数=%5d 最大绝对差=%.3e 最大相对差=%.3e"
              % (key, status, checked, max_abs, max_rel))
        if worst is not None and not ok:
            print("      最差点 idx=%d Python=%.10f Kotlin=%.10f" % worst)
        if not ok:
            failed = True

    print("\n结论：" + ("全部一致（相对误差 ≤ 1e-9）" if not failed else "存在不一致，Python 侧需要按 Kotlin 口径修正"))
    return 0 if not failed else 2


if __name__ == "__main__":
    sys.exit(main())
