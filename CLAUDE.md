# cloud-itonami/media — 2 つの面を持つ repo

> **2026-08-09**: オーナー指示により media 主題を cloud-itonami に集約した
> （`media-gamers` を統合して retire、`etzhayyim/com-etzhayyim-media-gamers` stub も
> retire、配信ゾーンを gftd.ai / etzhayyim.com から **itonami.cloud** へ）。
> この repo は以下の **2 面**を持つ。名前(`media`)が主題を 1 つしか示さないので、
> ここで名乗っておく（CLAUDE.md「名前が機能を示さない repo は README 冒頭で名乗る」）。
>
> | 面 | 中身 | 配信 |
> |---|---|---|
> | **A→B link medium** | 下記のとおり。news = A、media = A→B のエッジ | `media.itonami.cloud`（⚠ 現在デプロイ不可、下記） |
> | **kouryaku 攻略データ** | ゲームのキャラクター/ステージ/アイテムと出現関係の EDN コーパス + 公開面。`kouryaku/README.md` が正本 | `kouryaku.itonami.cloud` |
>
> ⚠ **link medium の worker は現在デプロイできない。** `wrangler.jsonc` の
> `main` が指す `src/app.cljc` が存在しない（実体は `src/app.ts` と
> `clj/src/media/*.cljc`）。これが `media.gftd.ai` が 522 を返していた原因で、
> route を itonami.cloud に付け替えても直らない —— entrypoint を決める作業が別途要る。
> kouryaku 面はこれと独立に動く（静的 assets のみ）。

---

# media.gftd.ai — the A→B medium

Companion to news.gftd.ai (ADR-2606161200 split). **news = A** (primary-source
collection); **media = the medium** that attaches the directed A→B edge.

## Model

`A —[news source]→  media link  —→ B (subject)`

- **A** = a primary source collected by news.gftd.ai (`:news/*`, read cross-graph).
- **B** = `:media.subject/*` — cohort | org | place | market | discipline | did.
- **medium** = `:media.link/*` — a reified, scored, attributed A→B edge with a
  B-framed rendering (title/brief) and genre. The article/post is the edge's
  rendering, not the edge itself.

## Pipeline (the medium's job)

```
news :news/source ──Follow──▶ media.onCommit
  → candidateSubjects (A→B ranking, bridge score)   [media.link / media.score, CLJS]
  → linkSourceToSubject (create scored :media.link)
  → generateBrief (B-framed, grounded in A)          [litellm]
  → publishLink (post as did:web:media.gftd.ai:genre:{genre}, reconcile postUri)
```

`autopilot` runs all four for one source. Heavy multi-subject linking / entity
resolution defers to the pod (`MEDIA_POD_URL`).

## Hourly, queue-scaled analysis

```
Cron Trigger (hourly :07)
  → scheduled(): scan recent news :news/source (last ~70m) → enqueue 1 job/source
      → Cloudflare Queue "media-analysis" (ANALYSIS_QUEUE)
          → queue(): runAnalysis per source (max_concurrency 5, retries 3, DLQ)
```

`runAnalysis` is the SDK-free A→B analysis (candidateSubjects → link → generate,
no social delivery) so it runs in the queue consumer / scheduled context without
a magatama SDK; `autopilot` = `runAnalysis` + delivery. Idempotent links
(`lnk-<sha(source|subject|genre)>`) make re-enqueue safe. Owner one-time:
`wrangler queues create media-analysis` + `... media-analysis-dlq`.

Multi-language: each brief renders in the subject's (or source's) language —
184 ISO 639-1 languages via `media.i18n` (`langDirective` steers litellm).

## Runtime / storage

- ClojureScript CF Worker (shadow-cljs `:esm` → `js/media.js`), same pattern as
  news. TS shell owns IO + SDK + Web Crypto; CLJS is pure (validation, EDN
  tx/query, A→B candidate ranking, bridge scoring, post text).
- Storage = kotoba Datomic via kotobase.net. media graph = `media-link-v1`
  (links + subjects); reads A from `news-intel-v1`. media is a registered kotoba
  **operator** (KOTOBASE_OPERATOR_DIDS) so it may `datomic.transact`.
- Genre desks = path DIDs `did:web:media.gftd.ai:genre:{tech|policy|labor|markets}`.

## Boundary (consensys-pattern)

media = consumer-facing 媒体ブランド/フィード = etzhayyim front candidate; it
consumes news (primary-source custody / rights / liability = gftd function) via
consent capability (ADR-2606011400).

## Build & deploy

```bash
cd clj && npx shadow-cljs release worker   # → ../js/media.js
cd .. && wrangler secret put KOTOBA_BEARER # media authn JWT
gftd deploy --no-svelte
```
