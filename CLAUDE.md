# cloud-itonami/media — 2 つの面を持つ repo

> **2026-08-09**: オーナー指示により media 主題を cloud-itonami に集約した
> （`media-gamers` を統合して retire、`etzhayyim/com-etzhayyim-media-gamers` stub も
> retire、配信ゾーンを gftd.ai / etzhayyim.com から **itonami.cloud** へ）。
> この repo は以下の **2 面**を持つ。名前(`media`)が主題を 1 つしか示さないので、
> ここで名乗っておく（CLAUDE.md「名前が機能を示さない repo は README 冒頭で名乗る」）。
>
> | 面 | 中身 | 配信 |
> |---|---|---|
> | **A→B link medium** | 下記のとおり。news = A、media = A→B のエッジ | `media.itonami.cloud`（build は通る。deploy は未実施 —— 下記の前提条件が未達） |
> | **kouryaku 攻略データ** | ゲームのキャラクター/ステージ/アイテムと出現関係の EDN コーパス + 公開面。`kouryaku/README.md` が正本 | `kouryaku.itonami.cloud` |
>
> **2026-08-09: link medium の build は通るようになった。** `main` が存在しない
> `src/app.cljc` を指していたのを `src/app.ts`（`export default {}` を持つ TS シェル）に
> 直し、依存の `workspace:*`（monorepo 抽出の残骸）を west sibling の `file:` に
> 置き換えた。`wrangler deploy --dry-run` が 3519 KiB / gzip 507 KiB で通り、
> 全 binding が解決する。
>
> ⚠ **ただし deploy はしていない。ランタイム前提が 3 つとも未達**（実測 2026-08-09）:
>
> | 前提 | 状態 |
> |---|---|
> | Queue `media-analysis` | **存在しない**（アカウントにあるのは `murakumo-kaizen` のみ） |
> | Secret `KOTOBA_BEARER` | **未設定**（worker 自体が未作成） |
> | `MEDIA_POD_URL` = `media-actor.gftd.ai` | **応答なし** |
>
> この状態で deploy すると hourly cron が認証情報なしで共有 datom 面
> （`kotobase.net` / `media-link-v1`）に書きに行く。**build が通ることと動くことは
> 別**なので、前提を満たせる人が deploy する:
>
> ```bash
> npx wrangler queues create media-analysis
> npx wrangler queues create media-analysis-dlq
> npm run install:deps          # --install-links 必須
> npx wrangler deploy
> npx wrangler secret put KOTOBA_BEARER
> ```
>
> kouryaku 面はこれと独立に動く（静的 assets のみ、既に稼働中）。

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
npm run install:deps                       # npm install --install-links（必須）
npm run cljs:release                       # → js/media.js
npx wrangler deploy --dry-run              # bundle 検証
npx wrangler deploy                        # 上記の前提 3 件を満たしてから
npx wrangler secret put KOTOBA_BEARER      # media authn JWT
```

⚠ `npx shadow-cljs` は動かない（`npm ERR! cb.apply is not a function`）。
shadow-cljs は clojure CLI 経由で起動する —— `npm run cljs:release` と
`wrangler.jsonc` の `build.command` は両方その形に直してある。
