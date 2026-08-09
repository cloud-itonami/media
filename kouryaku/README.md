# kouryaku — ゲーム攻略情報の収集・保管・問い合わせ・公開

キャラクター / ステージ / アイテムと **その出現関係** を出典付きで集め、EDN で
保管し、Datalog で引き、デジタル庁デザインシステムで公開する。

```
sources.edn     収集元レジストリ（ライセンス・robots 実測・公開ゲート・掃き先）
collect.cljs    収集       source → 正規化 EDN（出典付き）
query.cljs      問い合わせ  corpus → DataScript
publish.cljs    公開       corpus → 静的 HTML（jp-go-dds）
corpus/*.edn    正規化済みレコード（1 行 1 EDN map、git 管理）
.raw/           生応答（git 管理外 → cloud-itonami/media-corpus の annex/B2）
```

```bash
npm install                                    # datascript のみ
nbb --classpath . kouryaku/collect.cljs --all --limit 40
nbb --classpath . kouryaku/query.cljs verify
nbb --classpath . kouryaku/query.cljs stage valley-windworks-area
K=../../kotoba-lang
JP_GO_DDS_CSS=$K/jp-go-digital-design-system/resources/jp_go_dds/dds.css \
nbb --classpath ".:$K/html/src:$K/css/src:$K/jp-go-digital-design-system/src" \
    kouryaku/publish.cljs
```

## データモデル

4 種の record（`:game` / `:character` / `:item` / `:stage`）と、record 間の
`:kouryaku/rel`。**攻略情報の実体は rel の側**にある —— 「どのステージに、どの
キャラクターが、どのバージョンで、どれくらいの確率で、何レベルで出るか」は
単体の一覧には無い。

```clojure
{:kouryaku/id   "pokeapi:location-area/valley-windworks-area"
 :kouryaku/kind :stage
 :kouryaku/name {:en "Valley Windworks"}
 :kouryaku/rel  [{:rel/type :encounter
                  :rel/target "pokeapi:pokemon/bidoof"
                  :rel/props {:version "diamond" :encounter-score 10
                              :min-level 8 :max-level 8 :methods ["walk"]}}]
 :src/source :pokeapi :src/url "https://pokeapi.co/api/v2/location-area/…"
 :src/license :pokeapi-fair-use :src/fetched-at "2026-08-05T…"}
```

`query.cljs` は rel を **別 entity に展開してから** DataScript に載せる。入れ子の
まま 1 属性に押し込むと、この corpus の一番の価値が Datalog から辿れなくなる。

```bash
$ nbb --classpath . kouryaku/query.cljs where bidoof
▸ bidoof の出現ステージ（3 件）
  Valley Windworks   diamond   10  Lv8-8    walk
  Valley Windworks   pearl     10  Lv8-8    walk
  Eterna Forest      platinum  10  Lv12-12  walk
```

## 出典の追跡可能性

全レコードが `:src/source` `:src/url` `:src/license` を持ち、Wikidata 由来は
`:src/revision`（lastrevid）も持つ。revision があると **第三者が
`Special:EntityData/Q42.json?revision=N` を叩いて同一バイトを再取得し、こちらが
数値を捏造・改変していないことを検証できる**。`query.cljs verify` がこれを検査し、
欠落があれば exit 1。公開はこの検査を通ったものだけ。

## なぜ source がこの 2 つだけなのか

攻略情報が最も濃いのは game wiki だが、主要どころは軒並み自動アクセスを拒否して
いた（実測 2026-08-05）。**迂回しない。**

| 候補 | 実測 | 判定 |
|---|---|---|
| `zelda.fandom.com` | robots.txt 自体が Cloudflare の JS challenge の裏 | 不可 |
| `terraria.wiki.gg` | `ClaudeBot` / `GPTBot` / `CCBot` を名指しで `Disallow: /` | 不可 |
| `minecraft.wiki` | 全 UA に `Disallow: /*api.php` + AI bots 節で `ClaudeBot` | 不可 |
| `stardewvalley.wiki.gg` | api.php が 401 | 不可 |
| **Wikidata** | `/wiki/` は許可（`Special:EntityData` が使える） | **可**（CC0） |
| **PokéAPI** | robots.txt に directive も content-signal も無し | **可**（fair use 遵守） |

⚠ `query.wikidata.org/robots.txt` には `Disallow: /sparql` がある。AI agent を
名指ししていない blanket rule で、WDQS 自体は Wikimedia が programmatic query 用に
提供している endpoint だが、**Disallow と書いてあるのは事実**なので:
一覧の発見だけを SPARQL に投げ、件数の多い本体取得は robots-clean な
`Special:EntityData` に回してある。`:discovery/enabled? false` にすれば SPARQL は
一切飛ばない。

## 分担 —— Wikidata は深くない（測ってある）

| source | 担当 | 実測 |
|---|---|---|
| Wikidata | 作品カタログ + 著名キャラクター | ゲームキャラクター全体で 37,449 件。ただし **1 作品あたりは数十件**で、GTA V / BotW 等 4 作品の `P1441` は「キャラクター 20 / 架空のラジオ局 20 / 架空の人 19…」程度。**アイテムとステージはほぼ無い** |
| PokéAPI | 攻略の深さ | キャラクター 1,302 / アイテム 2,180 / ステージ 1,533 + 出現関係 |

「Wikidata だけで済ませなかったのはなぜか」を後から再調査させないために数字ごと
残してある。

## 既知の source 側の欠落（バグではない）

- **ステージに日本語名が無い。** `location-area` も親の `location` も言語は
  de / en / fr のみ。Sinnoh 12 件と Kanto 4 件（pallet-town / kanto-route-1 /
  celadon-city / viridian-forest）で確認し、ja / ja-Hrkt は 1 件も無い。
  キャラクターとアイテムには ja がある（40/40）ので「PokéAPI に日本語が無い」
  ではなく「location 系にだけ無い」。公開面は英語名を出しつつ
  **「日本語名なし」と明示する**（黙って英語を出すと日本語対応済みに見える）。
- **`encounter-score` は百分率ではない。** PokéAPI の `max_chance` は
  エンカウント方式ごとの確率の合計で、100 を超える（実測: Valley Windworks の
  magikarp が 155）。`%` を付けて表示しない。

## 公開ゲート

`sources.edn` の `:source/publish?` が false の source は publish.cljs が 1 件も
出さない。収集できることと再配布してよいことは別で、その判断を各ページに散らすと
必ずどこかで漏れる。出した source の attribution は全ページのフッタに必ず刷る。

## bytes plane

生応答は git に入れない（レコード 104 件で既に 16 MB、大半は Wikidata entity JSON
の 1 MB 超）。`.raw/` → `cloud-itonami/media-corpus`（DataLad + git-annex +
B2）。正規化済みの `corpus/*.edn` は小さく diff もレビューもしたいので通常の git
blob に置く —— **EDN だから annex、ではない**（ADR-2608039700）。

## UI

skill `kotoba-uiux`（オーナー判断 2026-08-05）に従い base は
`kotoba-lang/jp-go-digital-design-system`。app が触るのは `jp-go-dds.core` /
`.page` / `.tokens` だけ、生 hex / px font-size を書かない、layout は `dds-ext-*`。
決定論 audit（`kotoba-lang/design-quality`）で index / 一覧 / キャラクター /
アイテム / ステージの各ページが **100.00 / min 95**。
