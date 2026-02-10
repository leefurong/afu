# 股票数据与 K 线 / MA / 金叉死叉 总览

本文档说明：全量代码、Tushare、日 K 缓存、MA、金叉死叉 的数据来源、存储位置、谁计算谁写入，以及对 LLM 暴露的接口与「先本地取、缺则异步补」的策略。底层缓存表结构、占位符、拓展策略见 [k_line_store_design.md](./k_line_store_design.md)。

---

## 1. 概念与角色

| 概念 | 说明 |
|------|------|
| **全量代码** | A 股所有股票代码列表（如 `000001.SZ`、`600000.SH`），每日更新，供「某日全市场金叉」等统计限定范围。 |
| **Tushare** | 外部数据源，提供日 K 线等接口；本系统用其**补全/拓展**本地缓存，不直接对 LLM 暴露。 |
| **缓存（DB）** | SQLite 表 `k_line_daily`：按 `(ts_code, trade_date)` 存**日 K 行**；可选存 **MA** 与 **金叉/死叉** 标记，用于「某日全市场金叉」类查询。 |
| **日 K** | 开高低收、成交量等行情；原始来自 Tushare，写入缓存后供 MA、金叉等计算与读取。 |
| **MA** | 移动平均（如 MA5、MA20）；可在**内存里按日 K 现算**，也可在** DB 层**算好写回 `row_payload`，供金叉死叉与统计用。 |
| **金叉 / 死叉** | 短期均线上穿/下穿长期均线；在 DB 层算好后写入列 `cross_type`，供「某日哪些股票金叉」类查询。 |

---

## 2. 数据从哪里来、存在哪

```
Tushare（日 K 接口）
       ↓ 拉取、写入
k_line_daily 表（SQLite）
  - ts_code, trade_date, row_payload [, cross_type]
  - row_payload：日 K 行（EDN），补算后可含 ma5/ma10/ma20/ma30/ma60
  - cross_type：金叉 / 死叉（由 k_line_store 根据 MA 计算写回）
       ↑ 读取 / 写入
stock 层（get-daily-k、ma、cross-signals-on-date）
       ↑ 通过 SCI 暴露给
LLM（execute_clojure 工具）
```

- **全量代码**：单独来源（如 stock-list-store），不放在 K 线表里；LLM 通过 `(stock/all-stock-codes)` 拿到。
- **日 K**：Tushare → 只写入缓存；**不**在内存里长期保存，只做「缺则拉取并写库」。
- **MA、金叉死叉**：  
  - **写**：在 **k_line_store** 里由 `update-ma-for-stock-date-range!` 统一算并写回（读缓存行 → 算 MA → 算 cross_type → UPDATE）。  
  - **读**：  
    - 「某日金叉/死叉统计」直接读 DB 的 `cross_type`（和 `row_payload` 非占位符）；  
    - 「某几只股票某段区间 MA」由 **stock 层** 读缓存日 K 后**在内存里现算**，不依赖 DB 里预存的 MA（以支持任意周期、逻辑简单）。

---

## 3. 对 LLM 暴露的 stock 接口（SCI）

LLM 只能通过 `execute_clojure` 调用以下 stock API（见 `sci_sandbox.clj`）：

| 接口 | 作用 | 数据路径 |
|------|------|----------|
| `(stock/all-stock-codes)` | 返回全量 A 股代码列表 | stock-list-store |
| `(stock/get-daily-k-for-multiple-stocks codes date-from date-to)` | 多股票、区间内日 K | **仅读缓存**；缺则异步补数 |
| `(stock/ma-for-multiple-stocks codes period date-from date-to)` | 多股票、区间内 period 日 MA | **仅读缓存日 K + 内存现算 MA**；缺则异步补数 |
| `(stock/cross-signals-on-date codes trade-date opts)` | 某日、给定代码中金叉/死叉统计 | **只读 DB**（cross_type + row_payload）；缺则异步补数 |

说明：

- **不暴露**给 LLM：`golden-cross-for-multiple-stocks`（多股票区间金叉）已从 SCI 移除，避免「全量代码 + 大区间」现算超时；该逻辑仅作内部实现保留（算完会写回 DB）。
- 日 K / MA / cross-signals 都采用「**先本地取，不足再异步补**」：本次立刻返回当前缓存结果，并在满足条件时触发后台补数（见下节）。

---

## 4. 「先本地取、缺则异步补」

### 4.1 策略

- **读**：优先只读本地缓存（或 DB 里已有 MA/金叉），不阻塞等待 Tushare。
- **缺**：用简单阈值判断「数据是否明显不足」（例如该次请求涉及的行数 &lt; 1000）。
- **补**：若不足且请求非空，则**异步**触发「补日 K + 在 DB 层算 MA 与金叉死叉并写回」，**不**阻塞本次返回。
- **告知**：返回里带 `backfill_triggered`（或 `summary.backfill_triggered`），LLM 可据此提示用户「数据在补全，请稍后再试」。

### 4.2 各接口的触发条件与补数入口

| 接口 | 不足判定 | 异步触发的函数 | 说明 |
|------|----------|----------------------|------|
| **cross-signals-on-date** | 该日在 DB 的行数（含占位符）&lt; 股票数 | `ensure-range-and-update-ma-for-codes!` | 按「该交易日 −60 日」到「该交易日」补日 K 并算 MA/金叉写库 |
| **get-daily-k-for-multiple-stocks** | 返回数据的最早/最晚日期未覆盖请求区间 [date-from, date-to] | `ensure-range-and-update-ma-for-codes-range!` | 按请求的 date-from～date-to 补日 K 并算 MA/金叉写库 |
| **ma-for-multiple-stocks** | 返回数据的最早/最晚日期未覆盖 MA 所用区间 [start-d, date-to] | `ensure-range-and-update-ma-for-codes-range!` | 同 get-daily-k，按 MA 使用的区间补日 K 并算 MA/金叉写库 |

### 4.3 链式写回（补日 K 时顺带算 MA、金叉死叉）

无论是 `ensure-range-and-update-ma-for-codes!` 还是 `ensure-range-and-update-ma-for-codes-range!`，内部流程一致：

1. **补日 K**：对每个 code 调用 `extend-to-cover!` → 向 Tushare 拉取缺失区间 → `insert-rows!` 写入 `k_line_daily`（只写日 K，占位符按设计文档）。
2. **算 MA + 金叉死叉并写回**：对同一 code 再 `(future (update-ma-for-stock-date-range! ...))`：
   - 在 **k_line_store** 内从 DB 读该区间行；
   - 算 MA5/10/20/30/60 与金叉/死叉（cross_type）；
   - 通过 `update-daily-row-mas!` 写回 `row_payload` 与 `cross_type`。

因此：**一旦触发「补日 K」，就会在 DB 层链式完成 MA 与金叉死叉的计算与写回**，无需再调 stock 的 MA 或金叉接口。

---

## 5. 典型调用链（帮助理解）

- **用户问「某日有哪些股票金叉」**  
  → LLM 调 `cross-signals-on-date(codes, trade-date)`  
  → 读 DB 的 `cross_type` 与行数；若行数 &lt; 1000 则异步 `ensure-range-and-update-ma-for-codes!`（补日 K → 算 MA/金叉写库）  
  → 本次仅返回当前 DB 结果 + `backfill_triggered`，LLM 可提示「稍后再试」。

- **用户问「这几只股票这段时间的 MA5」**  
  → LLM 调 `ma-for-multiple-stocks(codes, 5, date-from, date-to)`  
  → 仅读缓存日 K，在内存里算 MA；若缓存行数 &lt; 1000 则异步 `ensure-range-and-update-ma-for-codes-range!`（同上，补日 K + 写 MA/金叉）  
  → 本次返回当前算出的 MA + `backfill_triggered`；用户稍后再问同一问题时会读到更多日 K，结果更完整。

- **全量代码**  
  → `(stock/all-stock-codes)` → stock-list-store，与 K 线缓存、Tushare 补数无直接关系。

---

## 6. 文件与职责速查

| 位置 | 职责 |
|------|------|
| `sci_sandbox.clj` | 向 SCI 暴露 stock 命名空间（get-daily-k、ma、cross-signals-on-date、all-stock-codes）。 |
| `sci_sandbox/stock.clj` | 对 LLM 的 stock API 实现；只读缓存/DB、内存算 MA、判断不足并触发异步补数、返回 `backfill_triggered`。 |
| `sci_sandbox/k_line_store.clj` | 日 K 缓存读写、向 Tushare 拓展、`update-ma-for-stock-date-range!`（MA + 金叉死叉 算并写 DB）、`ensure-range-and-update-ma-for-codes!` / `ensure-range-and-update-ma-for-codes-range!`。 |
| `k_line_store_design.md` | 缓存表结构、占位符、拓展策略、Cron 等详细设计。 |

---

*文档版本：与「先本地取、缺则异步补」及 cross-signals/MA/get-daily-k 行为一致。*
