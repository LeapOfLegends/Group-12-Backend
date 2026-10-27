# LEAP Instrument History — Frozen Implementation Plan

**Status:** Architecture and feature decisions frozen on 2026-10-20. No source-code changes made.  
**Repository:** `LeapOfLegends/Group-12-Backend`, reviewed against `main` (`f7b421d9fa7f82e3de77f1d7d9cbf22e5bc8a4ea`).  
**Implementation:** Four independently tested pull requests.

## 1. Purpose and boundaries

Build a read-only historical closing-price chart feature for supported US equities, crypto, and FX instruments. Fetch data on demand, persist completed historical candles in PostgreSQL, and incrementally fill the cache when another request needs newer or older data.

The existing `instruments` table remains the source of *current* market prices. The history table is a charting cache, never an input to order pricing, validation, execution, holdings, or portfolio accounting. Do not modify Kafka, order lifecycle, or the existing five-second snapshot scheduler. Do not create a dedicated history scheduler, extra microservice, provider registry, or persistent cache-coverage table.

## 2. Frozen decisions

| Decision | Final policy |
| --- | --- |
| 1. Cache | Best-effort on-demand cache, one history table, incremental tail/backfill, bounded in-memory cooldown for failed/unavailable retrievals; no guaranteed complete coverage |
| 2. Providers | `HistoricalMarketDataProvider` interface with separate `AlpacaHistoricalDataClient` and `BiquoteHistoricalDataClient`; reuse existing configured `RestClient` beans and `MarketDataRequest` |
| 3A. Equity adjustment | Alpaca **raw/unadjusted** historical prices; no split-reprocessing policy in MVP |
| 3B. Candle completion | Persist and return **completed candles only**; live prices remain on the existing instrument endpoint |
| 3C. Chart ranges | Equity 1D/5D use represented trading sessions; FX/crypto 1D/5D use trailing 24h/120h; 1M/1Y/5Y use trailing calendar months/years |
| 4A. Data columns | Store only closing prices, not full OHLC |
| 4B. Keys and writes | Composite PK `(instrument_id,timeframe,bar_start)`, idempotent PostgreSQL upserts, UTC timestamps, short batch transactions |
| 4C. HTTP API | One authenticated endpoint with instrument metadata, timeframe, available bounds, best-effort `partial` flag, and points; no frontend pagination |
| 5. Implementation | Four PRs: foundation; working US equities; crypto/FX; hardening and verification |

## 3. Historical range contract

| Selector | US equity meaning | FX/crypto meaning | Resolution |
| --- | --- | --- | --- |
| `1D` | Latest represented regular trading session (current session, if candles have completed) | Trailing 24 hours | `5Min` |
| `5D` | Latest five represented regular trading sessions | Trailing 120 hours | `30Min` |
| `1M` | Trailing calendar month | Trailing calendar month | `1Day` |
| `1Y` | Trailing calendar year | Trailing calendar year | `1Week` |
| `5Y` | Trailing five calendar years | Trailing five calendar years | `1Month` |

Use `America/New_York` to assign US equity intraday bars to trading dates and regular market hours (normally 09:30–16:00 ET, with early-close handling based on available bars). For 1D/5D, query a broader initial lookback (approximately 14 calendar days) and select the latest one/five distinct trading dates represented by completed regular-session candles; expand the lookback in a bounded way when insufficient dates are present. No additional exchange-calendar dependency. Before the first completed candle of a new session, show the latest represented session rather than an empty 1D graph. FX legitimately lacks weekend bars, while crypto can trade continuously.

Define trailing calendar durations by subtracting calendar months/years from the request's reference time, not by selecting the current named month or year. Historical bar timestamps always denote **bar starts**. The API may contain fewer points than a nominal expected count due to market closures, source retention, sparse trades, bar completion, and the best-effort cache policy. Do not fill market gaps with fabricated prices.

## 4. Architecture and types

```text
InstrumentController
  GET /instruments/{instrumentId}/history?range=1D
    -> InstrumentHistoryService
       -> InstrumentRepository (instrument + MarketCode)
       -> InstrumentHistoryRepository (MyBatis -> PostgreSQL)
       -> select exactly one HistoricalMarketDataProvider
          -> AlpacaHistoricalDataClient (existing alpacaMarketDataRestClient)
          -> BiquoteHistoricalDataClient (existing biquoteMarketDataRestClient)
```

Keep `MarketDataProvider` unchanged for current snapshots. Reuse `MarketDataRequest(symbol,assetClass,currency,marketCode)` for support checks and provider selection. A shared `HistoricalBar` model should carry timestamp, closing price and the completion status/semantics needed to reject open bars. A `HistoricalTimeframe` enum supports the five frozen resolutions. Repository code handles SQL; adapters handle HTTP, provider symbol mapping, pagination/chunking, source parsing, and validation; the history service determines chart semantics, routing, caching and response composition. Provider selection must be unambiguous; do not silently merge results from multiple providers for a single instrument.

**Required existing-repository integration fixes:**
1. Change `InstrumentRepository.findById()` to join `markets` and select `market_code` as `findTradableInstruments()` does. The existing `InstrumentEntity` already contains `marketCode`.
2. Expose `instrumentId` in `InstrumentDTO` and update `InstrumentService.toDto()` accordingly so clients can call the ID-based endpoint.
3. Mirror the `InstrumentController` authorization policy (`CLIENT`, `SUPER_ADMIN`, `FINANCE`, `OPERATIONS`).

## 5. Persistence contract

```sql
CREATE TABLE instrument_price_history (
    instrument_id BIGINT NOT NULL REFERENCES instruments(instrument_id),
    timeframe VARCHAR(10) NOT NULL
        CHECK (timeframe IN ('5Min','30Min','1Day','1Week','1Month')),
    bar_start TIMESTAMPTZ NOT NULL,
    close_price NUMERIC(30,16) NOT NULL CHECK (close_price > 0),
    PRIMARY KEY (instrument_id, timeframe, bar_start)
);
```

Use `INSERT ... ON CONFLICT (instrument_id,timeframe,bar_start) DO UPDATE SET close_price=EXCLUDED.close_price WHERE instrument_price_history.close_price IS DISTINCT FROM EXCLUDED.close_price`. This is idempotent under repeated or concurrent requests; correcting a previously stored price still requires fetching the revised candle again. The composite PK already supports reads filtered by instrument, timeframe and timestamp window. No extra index or surrogate ID is needed initially. Keep provider calls outside transactions, with a short transaction around each upsert batch.

For a fresh database setup, add the table to `Database-Setup/createTables.sql` and drop it before `instruments` in that script. **Do not rerun this destructive schema script against a populated database merely to add history.** Use an additive migration for any persistent environment.

## 6. Retrieval and caching contract

1. Validate the range and resolve an existing instrument, including market code; build `MarketDataRequest`.
2. Resolve range, timeframe, reference instant and correct market-specific session/window policy.
3. Read cached rows for that instrument/timeframe/window.
4. Estimate missing beginning or ending history using stored boundaries and latest eligible completed bar. Treat interior gaps cautiously: missing bars can be legitimate non-trading periods. Do **not** claim full coverage based solely on `MIN/MAX(bar_start)`.
5. If a fetch is warranted and not in a bounded failure/unavailability cooldown, select exactly one supporting historical adapter and request missing data. The adapter completes its supported pagination/chunking and returns validated bars with their timestamps.
6. Exclude every unfinished candle. Normalize UTC bar starts and positive `BigDecimal` closes. Use short batch upserts.
7. Re-query PostgreSQL, select the requested sessions or rolling interval, sort ascending, and compute response boundaries from returned points.
8. Return usable cached points during provider failures, marking known incomplete retrievals as partial. Never manufacture missing bars. Keep negative-cache/cooldown state in memory only; no persistence guarantee across restarts or across application instances.

An up-to-date cache should avoid repeated unchanged-history fetches. The first request to a new range may perform a backfill. The latest eligible completed candle drives tail refresh; do not fetch and persist an in-progress candle. Coalescing identical concurrent provider requests and more sophisticated cache completeness checks may be deferred unless tests show a meaningful problem. Rate limits may be shared with live data credentials, so retries and concurrent history fetching must be bounded.

## 7. Provider responsibilities

**Alpaca US equities:** Use the single-symbol endpoint `GET /v2/stocks/{symbol}/bars`, include `feed=iex` and `adjustment=raw`, pass range and timeframe, and exhaust `next_page_token`. Reuse the configured `alpacaMarketDataRestClient`, credentials and allowlisted-symbol semantics. Filter intraday equity results to represented regular trading sessions.

**Alpaca crypto:** Use `GET /v1beta3/crypto/us/bars` with `symbols`, timeframe, start/end and page token. Reuse the same configured Alpaca data client and existing supported-crypto symbol convention. Crypto charts use rolling windows for 1D/5D.

**Biquote FX:** Use `GET /api/{symbol}/ohlc` with native `5m`, `30m`, `1d`, ISO date-range parameters, and a request-size limit (currently documented up to 1,000 bars per call). Respect `isOpen` when identifying unfinished bars. Reuse the existing LEAP-to-provider symbol translation, e.g. `EUR/USD -> EURUSD`, and configured `biquoteMarketDataRestClient`. Derive completed weekly/monthly closes from the last available completed daily close of the relevant **finished** week/month, with consistent period-start timestamps. Define calendar aggregation boundaries and do not mislabel an open weekly/monthly period as final. Handle date-range chunking only where supported; no algorithm can recover data a provider does not retain. In particular, 5Y FX coverage is not guaranteed and must be tested by symbol.

**Both providers:** No history request should drive order execution, and no history adapter should update `instruments` bid/ask/last fields. Maintain useful error distinctions for transport timeouts, provider-side errors, malformed data and rate limiting.

## 8. HTTP contract

`GET /instruments/{instrumentId}/history?range=1D`

Accepted range values: `1D`, `5D`, `1M`, `1Y`, `5Y`.

```json
{
  "instrumentId": 12,
  "symbol": "AAPL",
  "range": "1D",
  "timeframe": "5Min",
  "currency": "USD",
  "availableFrom": "2026-10-20T13:30:00Z",
  "availableTo": "2026-10-20T13:35:00Z",
  "partial": false,
  "points": [
    {"timestamp": "2026-10-20T13:30:00Z", "close": 250.25},
    {"timestamp": "2026-10-20T13:35:00Z", "close": 250.80}
  ]
}
```

Prices above are illustrative, not market quotes. All point timestamps and `availableFrom/availableTo` are **bar start times**, not bar-end times or last-trade times. For `points: []`, both availability bounds are `null`.

**`partial` semantics:** `true` indicates a known failed/interrupted/limited retrieval or another known incomplete fetch that prevented filling requested history. `false` means no known retrieval shortfall occurred; it is **not** a guarantee that every possible historical candle exists.

**Status policy:** `200` for successful usable history, including a known empty result; `200` plus `partial:true` when cached history is usable despite an upstream failure; `400` for invalid range; `404` for nonexistent instrument; `503` when an upstream failure prevents providing any cached/usable history. Unsupported historical-provider routing needs a clear client-facing error mapping before PR 2; do not represent configuration errors as a successful empty history response. Preserve existing authorization. No frontend response pagination; this does **not** remove required provider-side pagination.

## 9. Incremental implementation plan and acceptance gates

### PR 1 — Foundation

- Add the history table and safe deployment approach; implement the MyBatis history repository, query and upsert.
- Add `HistoricalBar`, `HistoricalTimeframe`, `HistoricalMarketDataProvider` and a persistence representation if needed.
- Update `InstrumentRepository.findById()` to include `marketCode` and expose `instrumentId` via `InstrumentDTO` and its mapping.
- Tests: schema and FK/PK constraints, time-window queries, duplicate/conflicting upserts, UTC conversion, positive price validation, and existing DTO behavior.
- **Gate:** Compiles and tests pass independently. No history endpoint required yet. Existing snapshot tests remain green.

### PR 2 — Working US equity vertical slice

- Implement `AlpacaHistoricalDataClient` for IEX equities, including `raw`, pagination, and finalized-candle filtering.
- Implement `InstrumentHistoryService` with a basic best-effort cache; connect `InstrumentController` and response DTO.
- Support all five equity ranges and market-aware 1D/5D session selection.
- Tests: session selection in NY time, weekends/DST, no market bars, partial current day, multi-page responses, cached repeat request, incremental fetch, provider failures and authorization.
- **Gate:** Authenticated AAPL-style US equity requests work end-to-end against mocks/integration fixtures; all existing tests pass.

### PR 3 — Crypto and FX

- Extend Alpaca historical client to crypto and implement Biquote historical client for supported FX.
- Implement FX symbol translation, rolling windows, Biquote `isOpen`, daily-to-week/month conversion, and page/chunk limits.
- Test market closures, provider route selection, native-vs-aggregated candles, incomplete periods, a symbol with limited history, and 5Y FX availability behavior.
- **Gate:** Every supported asset class uses the same endpoint without changing current-price or order code; tests remain green.

### PR 4 — Reliability, integration, and documentation

- Harden rate-limit/timeout/error handling, bounded negative caching, concurrent duplicate requests, idempotent writes, boundary cases and partial metadata.
- Run integration and regression tests, review actual query plans only if performance warrants, and document retention limitations and raw-price discontinuities.
- Reconcile API behavior with the frontend and run end-to-end acceptance tests.
- **Gate:** New and existing suites pass; documentation matches observed behavior; no unnecessary components have been introduced.

## 10. Source-plan contradictions resolved by user decisions

1. Original plan proposed **split-adjusted** equity history. **Superseded:** use `raw` for the MVP. The documentation must explain split discontinuities.
2. Original plan proposed extending existing snapshot client classes. **Superseded:** use separate historical client classes, sharing configured `RestClient`s.
3. Original plan implied that cached boundaries could establish complete history. **Qualified:** best-effort caching cannot guarantee interior completeness or distinguish every no-trade interval from an incomplete fetch.
4. Original plan's API `partial:false` could be interpreted as completeness. **Qualified:** it only means no known retrieval shortfall.
5. Original plan's `1Y`/`5Y` FX chart controls must not imply that the provider guarantees the corresponding historical coverage.
6. “No API pagination” refers to **frontend responses**; Alpaca historical **provider requests must still paginate**.
7. Existing repository ID lookups do not populate `marketCode`, and `InstrumentDTO` omits `instrumentId`; PR 1 fixes both.

## 11. Remaining implementation ambiguities — proposed defaults, not separately approved design changes

- **Cache freshness heuristic:** choose the expected latest completed candle by asset/timeframe and use a bounded in-memory failure cooldown. Exact cooldown duration, lookback expansion limit and retry count remain small implementation constants/configuration choices; do not create persistent cache state to solve them.
- **Known-partial classification:** distinguish successful no-data/market-closure intervals from failed or truncated provider responses. Empty success is `200` with empty points; no cached history after upstream failure is `503`.
- **Unsupported provider routing:** choose and document a consistent client-facing error before PR 2. Avoid an empty success when the instrument exists but no history adapter supports it.
- **Calendar aggregation:** define a stable week start and month start (recommended ISO Monday weeks and UTC calendar months for FX), use the final completed daily close of each **completed** period, and enforce precise bar_start semantics.
- **Market-session lookback:** start with approximately 14 days, but bound any expansion so long market closures or missing data cannot cause unbounded API requests.
- **Concurrent identical requests:** DB upserts are safe; provider calls might still duplicate. Add request coalescing only if PR 4 testing shows material API load.
- **Provider retention and quotas:** test real Biquote history availability for every supported FX symbol before claiming any horizon; historical and five-second market-data requests may share provider limits.
- **Schema deployment:** use an additive migration where persistent data exists; the current `createTables.sql` is destructive.

None of these reopen the nine product/architectural decisions. They are explicit implementation acceptance details and must not be silently represented as already verified provider guarantees.

## 12. References

- Backend repository: https://github.com/LeapOfLegends/Group-12-Backend
- Existing market-data architecture: https://github.com/LeapOfLegends/Group-12-Backend/blob/main/Docs/market-data-architecture.md
- Alpaca single-stock bars: https://docs.alpaca.markets/us/v1.1/reference/stockbarsingle-1
- Alpaca crypto bars: https://docs.alpaca.markets/us/reference/cryptobars-1
- Biquote OHLC endpoint: https://biquote.io/docs/
