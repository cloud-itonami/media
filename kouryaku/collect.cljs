#!/usr/bin/env nbb
;; collect.cljs — ゲーム攻略情報の収集器。
;;
;; ## 何を集めるか
;;
;; 攻略情報を 4 種の record に正規化する:
;;   :game       作品（カタログ）
;;   :character  キャラクター
;;   :item       アイテム
;;   :stage      ステージ / ロケーション
;; さらに **「どのステージにどのキャラクターが出るか」を `:kouryaku/rel` として
;; 持たせる**。これが攻略情報の実体で、単体の一覧より価値がある部分。
;;
;; ## 出典の追跡可能性（この設計の要）
;;
;; 全 record が `:src/source` `:src/url` `:src/license` を持ち、Wikidata 由来は
;; さらに `:src/revision`（lastrevid）を持つ。revision があると **第三者が
;; `Special:EntityData/Q42.json?revision=N` を叩いて同一バイトを再取得し、
;; こちらが数値を捏造・改変していないことを検証できる**。product-corpus の
;; `:src/warc-offset` と同じ役割で、削らない。
;;
;; ## 使い方
;;   nbb --classpath . kouryaku/collect.cljs --target pokeapi-character --limit 60
;;   nbb --classpath . kouryaku/collect.cljs --all --limit 40
;;   nbb --classpath . kouryaku/collect.cljs --list

(ns kouryaku.collect
  (:require [kouryaku.util :as u]
            [clojure.string :as str]))

(def sources-path "kouryaku/sources.edn")
(def corpus-dir "kouryaku/corpus")
(def raw-dir ".raw")   ;; 生 JSON の置き場（git 管理外 → DataLad/annex へ）

(def registry (u/read-edn sources-path))
(def http-opts (:http registry))
(def sources (into {} (map (juxt :source/id identity) (:sources registry))))
(def targets (:targets registry))

;; ---------------------------------------------------------------------------
;; 生応答の保全
;;
;; 派生 EDN だけを残すと、後から「抽出のバグ」と「source がそのデータを持って
;; いない」を切り分けられなくなる（product-corpus が楽天 microdata で踏んだ穴）。
;; 生 JSON は `.raw/` に落として bytes plane（DataLad/B2）へ回す。git には入れない。

(defn- save-raw! [target-id key body]
  (when body
    (u/spit (str raw-dir "/" (name target-id) "/" (str/replace key #"[^A-Za-z0-9_.-]" "_") ".json")
            body)))

;; ---------------------------------------------------------------------------
;; 共通: record の骨格

(defn- base-record [src-id url]
  (let [s (get sources src-id)]
    {:src/source src-id
     :src/url url
     :src/license (:source/license s)
     :src/attribution (:source/attribution s)
     :src/consent (:source/consent s)
     :src/fetched-at (u/now-iso)}))

;; ---------------------------------------------------------------------------
;; PokéAPI adapter
;;
;; 母数（実測 2026-08-05）: pokemon 1302 / item 2180 / location-area 1533。
;; 名前は多言語で入っているので `names[]` から ja / en を拾う（ja-Hrkt もあるが
;; 表示用には ja を優先。無ければ en に落とす）。

(defn- pokeapi-names
  "PokéAPI の `names` 配列 → {:ja \"...\" :en \"...\"}。lang key は API により
  `language.name` が \"ja\" / \"ja-Hrkt\" / \"en\" 等。"
  [names]
  (reduce (fn [acc n]
            (let [lang (get-in n ["language" "name"])
                  v (get n "name")]
              (cond-> acc
                (and (= lang "ja") v) (assoc :ja v)
                (and (= lang "ja-Hrkt") v (not (:ja acc))) (assoc :ja v)
                (and (= lang "en") v) (assoc :en v))))
          {} (or names [])))

(defn- pokeapi-flavor
  "flavor_text_entries / effect_entries から ja / en の説明を 1 本ずつ。

  ⚠ **本文のキーは endpoint ごとに違う**（実測 2026-08-05）:
  `pokemon-species` は `flavor_text`、`item` は `text`、`effect_entries` は
  `effect` / `short_effect`。最初 `flavor_text` しか見ておらず、**アイテムの
  説明が全件空**になっていた（0 件を『source が持っていない』と誤読しかけた
  ちょうどその形）。候補キーを順に見る。

  改行と制御文字は潰す（ゲーム内テキストは固定幅表示のため \\f や \\n を含む）。
  U+3000（全角空白）は本文の一部なので残す。"
  [entries]
  (reduce (fn [acc e]
            (let [lang (get-in e ["language" "name"])
                  v (some-> (or (get e "flavor_text") (get e "text")
                                (get e "short_effect") (get e "effect"))
                            (str/replace #"[\n\f\r­]" " ")
                            (str/replace #"[ \t]+" " ")
                            str/trim)]
              (cond-> acc
                (and (= lang "ja") v (not (:ja acc))) (assoc :ja v)
                (and (= lang "en") v (not (:en acc))) (assoc :en v))))
          {} (or entries [])))

(defn- pokeapi-get [src path]
  (let [url (str (:hydrate/endpoint src) path)
        body (u/http-get url http-opts)]
    (u/sleep! (:source/rate-limit-ms src))
    (when body
      [url body (try (js->clj (js/JSON.parse body)) (catch :default _ nil))])))

(defn- pokeapi-index
  "一覧 endpoint を limit 件だけ引いて [{name url}...] を返す。"
  [src resource limit]
  (when-let [[_ _ j] (pokeapi-get src (str resource "/?limit=" limit "&offset=0"))]
    (get j "results")))

(defn- collect-pokeapi-character [src target limit]
  (let [idx (pokeapi-index src (:target/resource target) limit)]
    (println (str "  一覧 " (count idx) " 件"))
    (vec
     (keep
      (fn [{:strs [name]}]
        (when-let [[url body j] (pokeapi-get src (str "pokemon/" name))]
          (save-raw! (:target/id target) name body)
          ;; 種族側（species）に多言語名と説明があるので 1 回だけ追加取得する。
          (let [[surl sbody sj] (or (pokeapi-get src (str "pokemon-species/" name)) [nil nil nil])
                _ (save-raw! (:target/id target) (str name "-species") sbody)
                nm (pokeapi-names (get sj "names"))]
            (merge (base-record :pokeapi url)
                   {:kouryaku/id (str "pokeapi:pokemon/" name)
                    :kouryaku/kind :character
                    :kouryaku/slug name
                    :kouryaku/game (:target/game target)
                    :kouryaku/name (if (seq nm) nm {:en name})
                    :kouryaku/desc (pokeapi-flavor (get sj "flavor_text_entries"))
                    :kouryaku/props
                    {:height-dm (get j "height")
                     :weight-hg (get j "weight")
                     :base-experience (get j "base_experience")
                     :types (mapv #(get-in % ["type" "name"]) (get j "types"))
                     :abilities (mapv #(get-in % ["ability" "name"]) (get j "abilities"))
                     :stats (into {} (map (fn [s] [(get-in s ["stat" "name"]) (get s "base_stat")])
                                          (get j "stats")))
                     :generation (get-in sj ["generation" "name"])
                     :capture-rate (get sj "capture_rate")
                     :is-legendary (get sj "is_legendary")
                     :habitat (get-in sj ["habitat" "name"])}
                    :src/species-url surl}))))
      idx))))

(defn- collect-pokeapi-item [src target limit]
  (let [idx (pokeapi-index src (:target/resource target) limit)]
    (println (str "  一覧 " (count idx) " 件"))
    (vec
     (keep
      (fn [{:strs [name]}]
        (when-let [[url body j] (pokeapi-get src (str "item/" name))]
          (save-raw! (:target/id target) name body)
          (merge (base-record :pokeapi url)
                 {:kouryaku/id (str "pokeapi:item/" name)
                  :kouryaku/kind :item
                  :kouryaku/slug name
                  :kouryaku/game (:target/game target)
                  :kouryaku/name (let [nm (pokeapi-names (get j "names"))]
                                   (if (seq nm) nm {:en name}))
                  :kouryaku/desc (merge (pokeapi-flavor (get j "effect_entries"))
                                        (pokeapi-flavor (get j "flavor_text_entries")))
                  :kouryaku/props
                  {:cost (get j "cost")
                   :category (get-in j ["category" "name"])
                   :fling-power (get j "fling_power")
                   :attributes (mapv #(get % "name") (get j "attributes"))
                   ;; どのキャラクターが持っているか = 野生入手の攻略情報
                   :held-by (mapv #(get-in % ["pokemon" "name"]) (get j "held_by_pokemon"))}})))
      idx))))

(defn- collect-pokeapi-stage [src target limit]
  (let [idx (pokeapi-index src (:target/resource target) limit)]
    (println (str "  一覧 " (count idx) " 件"))
    (vec
     (keep
      (fn [{:strs [name]}]
        (when-let [[url body j] (pokeapi-get src (str "location-area/" name))]
          (save-raw! (:target/id target) name body)
          ;; ⚠ **ステージ名に日本語は付かない。これは source の事実であって
          ;; こちらのバグではない**（実測 2026-08-05）。`location-area` の `names`
          ;; は en のみのことが多く、親の `location` を辿っても言語は de / en / fr
          ;; の 3 つしか無い —— Sinnoh (canalave-city 他 12 件) と Kanto
          ;; (pallet-town / kanto-route-1 / celadon-city / viridian-forest) の
          ;; いずれも ja / ja-Hrkt を 1 件も持たない。**キャラクターとアイテムには
          ;; ja がある**（40/40）ので、「PokéAPI に日本語が無い」ではなく
          ;; 「location 系にだけ無い」。
          ;;
          ;; したがって親 `location` を辿るのは名前のためではなく `region`
          ;; （ステージのグループ化に使う）のため。名前は両者を merge して
          ;; area 優先とし、`:name-from` に **ja がどこから来たか**を残す
          ;; （UI が「日本語名なし」を正直に出せるようにするため）。
          (let [loc-name (get-in j ["location" "name"])
                [lurl lbody lj] (or (some->> loc-name (str "location/") (pokeapi-get src))
                                    [nil nil nil])
                _ (save-raw! (:target/id target) (str name "-location") lbody)
                area-nm (pokeapi-names (get j "names"))
                loc-nm (pokeapi-names (get lj "names"))
                merged (merge loc-nm area-nm)]
          (merge (base-record :pokeapi url)
                 {:kouryaku/id (str "pokeapi:location-area/" name)
                  :kouryaku/kind :stage
                  :kouryaku/slug name
                  :kouryaku/game (:target/game target)
                  :kouryaku/name (if (seq merged) merged {:en name})
                  :kouryaku/props {:location loc-name
                                   :region (get-in lj ["region" "name"])
                                   ;; ja の出所。:none = source が日本語名を持たない。
                                   :name-from (cond (:ja area-nm) :area
                                                    (:ja loc-nm) :location
                                                    :else :none)}
                  :src/location-url lurl
                  ;; **ここが攻略情報の本体**: このステージに、どのキャラクターが、
                  ;; どのバージョンで、何 % で出るか。
                  :kouryaku/rel
                  (vec
                   (for [pe (get j "pokemon_encounters")
                         vd (get pe "version_details")]
                     {:rel/type :encounter
                      :rel/target (str "pokeapi:pokemon/" (get-in pe ["pokemon" "name"]))
                      ;; ⚠ `max_chance` は **エンカウント方式ごとの確率の合計**で、
                      ;; 100 を超えうる（実測 2026-08-05: Valley Windworks の
                      ;; magikarp が 155）。「155%」と表示すると嘘になるので、
                      ;; 属性名から % を外し、意味を props 名で示す。
                      :rel/props {:version (get-in vd ["version" "name"])
                                  :encounter-score (get vd "max_chance")
                                  :methods (vec (distinct (map #(get-in % ["method" "name"])
                                                               (get vd "encounter_details"))))
                                  :min-level (some->> (get vd "encounter_details")
                                                      (map #(get % "min_level"))
                                                      (remove nil?) seq (apply min))
                                  :max-level (some->> (get vd "encounter_details")
                                                      (map #(get % "max_level"))
                                                      (remove nil?) seq (apply max))}}))}))))
      idx))))

;; ---------------------------------------------------------------------------
;; Wikidata adapter
;;
;; discovery（一覧）は WDQS、hydration（本体）は Special:EntityData。
;; **robots に blanket Disallow がある WDQS へのリクエストを最小化する**ための
;; 分割で、件数の多い本体取得は robots-clean な `/wiki/` 配下に寄せてある。
;; 詳細は sources.edn の注記。

(defn- wdqs [src query]
  (when (:discovery/enabled? src)
    (let [url (str (:discovery/endpoint src) "?query=" (js/encodeURIComponent query))
          j (u/http-get-json url (assoc http-opts :accept "application/sparql-results+json"))]
      (u/sleep! (:source/rate-limit-ms src))
      (get-in j ["results" "bindings"]))))

(defn- qid-from-uri [uri]
  (when uri (last (str/split uri #"/"))))

(defn- wd-label
  "labels / descriptions map から {:ja .. :en ..} を取る。

  ⚠ **`mul`（多言語共通ラベル）を必ず見る。** Wikidata は「どの言語でも綴りが
  同じ名前」を各言語に複製せず `mul` 1 本に集約する。実測 2026-08-05:
  Minecraft (Q49740) と Roblox (Q692989) は `labels` に **en も ja も持たず**
  `mul: \"Minecraft\"` / `mul: \"Roblox\"` だけを持っていた（labels の言語数は
  42 / 28 あるのに en が無い）。ja/en しか見ていなかったため、公開ページに
  作品名ではなく `Q49740` という QID がそのまま出ていた。

  descriptions 側に mul はほぼ無い（説明文は言語ごとに違う）ので、mul を足すのは
  labels のときだけ。"
  ([m] (wd-label m false))
  ([m mul?]
   (let [base (reduce (fn [acc lang]
                        (if-let [v (get-in m [lang "value"])]
                          (assoc acc (keyword lang) v) acc))
                      {} ["ja" "en"])]
     (if (and mul? (empty? base))
       (if-let [v (get-in m ["mul" "value"])] {:en v} base)
       base))))

(defn- wd-claims
  "truthy な claim の値（QID 参照 / 時刻 / 文字列）を素直に取り出す。"
  [entity pid]
  (vec (keep (fn [c]
               (let [dv (get-in c ["mainsnak" "datavalue"])]
                 (case (get dv "type")
                   "wikibase-entityid" (get-in dv ["value" "id"])
                   "time" (get-in dv ["value" "time"])
                   "string" (get dv "value")
                   nil)))
             (get-in entity ["claims" pid]))))

(defn- wd-resolve-labels
  "参照されている QID 群のラベルを **1 クエリにまとめて**引く。

  Wikidata の claim は `Q1406` のような裸の QID しか返さないので、そのままだと
  `:platform [\"Q1406\" \"Q48263\" …]` という読めない配列になる（実測: 1 作品で
  プラットフォーム 27 件）。EntityData を QID ごとに叩くと数百リクエストになる
  ため、`VALUES` で束ねて SPARQL 1 発にする。**robots に Disallow のある経路への
  リクエスト数を増やさない**ためでもある。

  discovery を無効化している場合は解決しない（ラベルの無い QID のまま残る）。"
  [src qids]
  (if-not (:discovery/enabled? src)
    {}
    (into {}
          (mapcat
           (fn [batch]
             (let [q (str "SELECT ?e ?eLabel WHERE { VALUES ?e {"
                          (str/join " " (map #(str "wd:" %) batch))
                          "} SERVICE wikibase:label { bd:serviceParam wikibase:language \"ja,en\". } }")]
               ;; ⚠ **ラベルが無い entity には label service が QID 文字列を返す**
               ;; （実測 2026-08-05: Minecraft の P400 に混じっている Q94 / Q48263 が
               ;; まさにこれで、ja / en どちらのラベルも持たない）。そのまま採ると
               ;; 「ラベルは Q94 という名前」に見えてしまうので、id と一致する
               ;; 応答は解決失敗として落とし、下流には :label nil を渡す。
               (keep (fn [r]
                       (let [id (qid-from-uri (get-in r ["e" "value"]))
                             lbl (get-in r ["eLabel" "value"])]
                         (when (and id lbl (not= id lbl)) [id lbl])))
                     (wdqs src q))))
           (partition-all 300 (distinct qids))))))

(defn- wd-decorate
  "props の中の QID 配列を {:id .. :label ..} に置き換える。ラベルが引けなかった
  QID は **落とさずに :label nil のまま残す**（黙って消すと『その作品には
  プラットフォームが無い』に見えてしまう）。"
  [records labels]
  (let [dec (fn [v] (cond
                      (and (string? v) (re-matches #"Q\d+" v))
                      {:id v :label (get labels v)}
                      (vector? v) (mapv #(if (and (string? %) (re-matches #"Q\d+" %))
                                           {:id % :label (get labels %)} %) v)
                      :else v))]
    (mapv (fn [r] (update r :kouryaku/props #(into {} (map (fn [[k v]] [k (dec v)]) %)))) records)))

(defn- collect-wikidata [src target limit]
  (let [q (str/replace (:target/discovery target) "%LIMIT%" (str limit))
        rows (wdqs src q)
        _ (println (str "  discovery " (count rows) " 件"
                        (when-not (:discovery/enabled? src) "（SPARQL 無効化中）")))
        pairs (map (fn [r] [(qid-from-uri (get-in r ["e" "value"]))
                            (qid-from-uri (get-in r ["game" "value"]))]) rows)]
    (vec
     (keep
      (fn [[qid game-qid]]
        (when qid
          (let [url (str (:hydrate/endpoint src) qid ".json")
                body (u/http-get url http-opts)
                _ (u/sleep! (:source/rate-limit-ms src))
                _ (save-raw! (:target/id target) qid body)
                j (try (js->clj (js/JSON.parse body)) (catch :default _ nil))
                e (get-in j ["entities" qid])]
            (when e
              (merge (base-record :wikidata url)
                     {:kouryaku/id (str "wd:" qid)
                      :kouryaku/kind (:target/kind target)
                      :kouryaku/slug qid
                      :kouryaku/game (when game-qid (str "wd:" game-qid))
                      :kouryaku/name (wd-label (get e "labels") true)
                      :kouryaku/desc (wd-label (get e "descriptions"))
                      :kouryaku/props
                      (cond-> {}
                        (= :game (:target/kind target))
                        (merge {:developer (wd-claims e "P178")
                                :publisher (wd-claims e "P123")
                                :platform (wd-claims e "P400")
                                :series (wd-claims e "P179")
                                :genre (wd-claims e "P136")
                                :released (first (wd-claims e "P577"))})
                        (= :character (:target/kind target))
                        (merge {:present-in-work (wd-claims e "P1441")
                                :sex-or-gender (wd-claims e "P21")
                                :occupation (wd-claims e "P106")}))
                      ;; **第三者が同一 revision を再取得して検証できる。**
                      :src/revision (get e "lastrevid")})))))
      pairs))))

(defn- collect-wikidata+labels [src target limit]
  ;; discovery 側の DISTINCT に加えて、こちらでも id で畳む。SPARQL の書き方 1 つで
  ;; 重複が corpus に入るのは事故が起きやすすぎるので、二重に止める。
  (let [recs (->> (collect-wikidata src target limit)
                  (reduce (fn [acc r] (if (contains? acc (:kouryaku/id r)) acc
                                          (assoc acc (:kouryaku/id r) r)))
                          {})
                  vals vec)
        qids (->> recs
                  (mapcat (comp vals :kouryaku/props))
                  (mapcat #(if (vector? %) % [%]))
                  (filter #(and (string? %) (re-matches #"Q\d+" %))))
        labels (wd-resolve-labels src qids)]
    (println (str "  ラベル解決 " (count labels) "/" (count (distinct qids)) " QID"))
    (wd-decorate recs labels)))

;; ---------------------------------------------------------------------------
;; driver

(defn- collect-target [target limit]
  (let [src (get sources (:target/source target))]
    (println (str "▸ " (name (:target/id target))
                  "  source=" (name (:target/source target))
                  " kind=" (name (:target/kind target))
                  " limit=" limit))
    (let [k [(:target/source target) (:target/kind target)]
          recs (cond
                 (= k [:pokeapi :character]) (collect-pokeapi-character src target limit)
                 (= k [:pokeapi :item]) (collect-pokeapi-item src target limit)
                 (= k [:pokeapi :stage]) (collect-pokeapi-stage src target limit)
                 (= :wikidata (:target/source target)) (collect-wikidata+labels src target limit)
                 :else (do (println "  [warn] 未対応の (source, kind) 組み合わせ") []))]
      (if (seq recs)
        (let [p (str corpus-dir "/" (name (:target/id target)) ".edn")]
          (u/write-shard! p recs)
          (println (str "  → " (count recs) " 件 " p)))
        ;; 0 件は 2 種類ある。どちらか分からないまま「無い」と書かない。
        (println "  → 0 件（抽出バグか source が持っていないかを .raw/ の生応答で切り分けること）"))
      (count recs))))

(defn- close-refs!
  "**rel の参照先で corpus に居ないものを取りに行き、参照グラフを閉じる。**

  これが無いと、ステージ 12 件を取っただけの corpus では出現キャラクターが軒並み
  『収集範囲外』になる（実測 2026-08-05: Sinnoh のステージは全国図鑑の後半を
  参照するので、先頭 40 件を取っても 1 件も一致しない）。ページとしては正直だが
  中身が無い —— **limit を上げて当てずっぽうに広げるのではなく、実際に参照されて
  いるものだけを取る**方が、件数あたりの価値が高く冪等でもある。

  取得済みの character shard に追記する形で書き戻す。"
  [src]
  (let [existing (u/read-corpus corpus-dir)
        known (set (map :kouryaku/id existing))
        wanted (->> existing
                    (mapcat :kouryaku/rel)
                    (map :rel/target)
                    (filter #(str/starts-with? (str %) "pokeapi:pokemon/"))
                    distinct
                    (remove known)
                    vec)
        target {:target/id :pokeapi-character :target/game "pokeapi:franchise/pokemon"}]
    (println (str "▸ close-refs  未取得の参照先 " (count wanted) " 件"))
    (if (empty? wanted)
      (println "  参照グラフは既に閉じている")
      (let [new-recs
            (vec (keep (fn [id]
                         (let [slug (last (str/split id #"/"))]
                           (when-let [[url body j] (pokeapi-get src (str "pokemon/" slug))]
                             (save-raw! :pokeapi-character slug body)
                             (let [[surl sbody sj] (or (pokeapi-get src (str "pokemon-species/" slug))
                                                       [nil nil nil])
                                   _ (save-raw! :pokeapi-character (str slug "-species") sbody)
                                   nm (pokeapi-names (get sj "names"))]
                               (merge (base-record :pokeapi url)
                                      {:kouryaku/id id
                                       :kouryaku/kind :character
                                       :kouryaku/slug slug
                                       :kouryaku/game (:target/game target)
                                       :kouryaku/name (if (seq nm) nm {:en slug})
                                       :kouryaku/desc (pokeapi-flavor (get sj "flavor_text_entries"))
                                       :kouryaku/props
                                       {:height-dm (get j "height")
                                        :weight-hg (get j "weight")
                                        :base-experience (get j "base_experience")
                                        :types (mapv #(get-in % ["type" "name"]) (get j "types"))
                                        :abilities (mapv #(get-in % ["ability" "name"]) (get j "abilities"))
                                        :stats (into {} (map (fn [s] [(get-in s ["stat" "name"]) (get s "base_stat")])
                                                             (get j "stats")))
                                        :generation (get-in sj ["generation" "name"])
                                        :capture-rate (get sj "capture_rate")
                                        :is-legendary (get sj "is_legendary")
                                        :habitat (get-in sj ["habitat" "name"])}
                                       :src/species-url surl})))))
                       wanted))
            p (str corpus-dir "/pokeapi-character.edn")
            merged (->> (concat (u/read-shard p) new-recs)
                        (reduce (fn [acc r] (assoc acc (:kouryaku/id r) r)) {})
                        vals
                        (sort-by :kouryaku/id)
                        vec)]
        (u/write-shard! p merged)
        (println (str "  → " (count new-recs) " 件追加、shard 計 " (count merged) " 件"))))))

(defn -main [& args]
  (let [argv (vec args)
        flag (fn [k] (let [i (.indexOf argv k)] (when (>= i 0) (get argv (inc i)))))
        limit (js/parseInt (or (flag "--limit") "40") 10)]
    (cond
      (some #{"--close-refs"} argv)
      (close-refs! (get sources :pokeapi))

      (some #{"--list"} argv)
      (doseq [t targets]
        (println (str (name (:target/id t)) "\t" (name (:target/source t))
                      "\t" (name (:target/kind t)))))

      (some #{"--all"} argv)
      (let [n (reduce + (map #(collect-target % limit) targets))]
        (println (str "\n合計 " n " 件")))

      (flag "--target")
      (if-let [t (first (filter #(= (flag "--target") (name (:target/id %))) targets))]
        (collect-target t limit)
        (do (println (str "不明な target: " (flag "--target") "  --list で一覧")) (js/process.exit 2)))

      :else
      (println "usage: collect.cljs [--all | --target <id> | --list] [--limit N]"))))

(apply -main *command-line-args*)
