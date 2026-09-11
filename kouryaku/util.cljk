(ns kouryaku.util
  "collect / query / publish が共有する最小限の道具。

  外部依存を足さないために HTTP は `curl` の同期実行にしている（product-corpus
  collect.cljs と同じ手）。nbb で promise を跨いだ制御フローを書くより、収集器の
  ような直線的なバッチでは同期の方が読みやすく、失敗時の切り分けも楽。"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def cp (js/require "node:child_process"))

;; ---------------------------------------------------------------------------
;; file

(defn slurp [p] (.readFileSync fs p "utf8"))

(defn spit [p s]
  (.mkdirSync fs (.dirname path p) #js {:recursive true})
  (.writeFileSync fs p s))

(defn exists? [p] (.existsSync fs p))

(defn read-edn-str
  "tagged literal を握り潰さずに読む（未知タグは値をそのまま通す）。"
  [s]
  (edn/read-string {:default (fn [_ v] v)} s))

(defn read-edn [p] (read-edn-str (slurp p)))

;; ---------------------------------------------------------------------------
;; time / sleep

(defn now-iso [] (.toISOString (js/Date.)))

(defn sleep!
  "同期スリープ。Atomics.wait は SharedArrayBuffer 上でのみ使えるのでその形で。"
  [ms]
  (when (pos? ms)
    (let [ia (js/Int32Array. (js/SharedArrayBuffer. 4))]
      (js/Atomics.wait ia 0 0 ms))))

;; ---------------------------------------------------------------------------
;; HTTP

(defn- curl-once
  "GET して {:status <int> :body <string>} を返す。curl 自体が失敗したら :status 0。

  `-w '\\n%{http_code}'` で本文の末尾に status を付けさせ、1 回の呼び出しで
  両方取る（`-o /dev/null` で status だけ取ってから本文を取り直すと、対象
  サーバへのリクエストが単純に倍になる）。"
  [url {:keys [user-agent timeout-sec accept]}]
  (let [args (cond-> ["-sS" "-L" "--max-time" (str (or timeout-sec 60))
                      "-A" (or user-agent "media-kouryaku/0.1")
                      "-w" "\n%{http_code}"]
               accept (into ["-H" (str "Accept: " accept)])
               :always (conj url))
        r (.spawnSync cp "curl" (clj->js args)
                      #js {:encoding "utf8" :maxBuffer (* 128 1024 1024)})]
    (if (zero? (or (.-status r) 1))
      (let [out (or (.-stdout r) "")
            nl (.lastIndexOf out "\n")
            body (if (neg? nl) "" (.slice out 0 nl))
            code (js/parseInt (str/trim (if (neg? nl) "0" (.slice out (inc nl)))) 10)]
        {:status (if (js/isNaN code) 0 code) :body body})
      {:status 0 :body (or (.-stderr r) "")})))

(defn http-get
  "指数バックオフ付き GET。2xx 以外は再試行し、尽きたら nil を返す。

  **429 / 503 を成功として扱わないこと**が要点。product-corpus の実測では、
  スロットルされた応答を本文として受け取ったせいで『そのターゲットは 0 件だった』
  という誤った結論に直結した（パターン不一致と区別の付かない偽の 0 件）。"
  [url opts]
  (loop [attempt 0]
    (let [{:keys [status body]} (curl-once url opts)]
      (cond
        (and (>= status 200) (< status 300)) body
        (>= attempt (or (:retries opts) 3))
        (do (println (str "  [warn] GET 失敗 status=" status " " (subs url 0 (min 110 (count url)))))
            nil)
        :else
        (let [wait (* 1500 (js/Math.pow 2 attempt))]
          (println (str "  [retry] status=" status " → " (/ wait 1000) "s 待って再試行"))
          (sleep! wait)
          (recur (inc attempt)))))))

(defn http-get-json [url opts]
  (when-let [b (http-get url (assoc opts :accept (or (:accept opts) "application/json")))]
    (try (js->clj (js/JSON.parse b))
         (catch :default e
           (println (str "  [warn] JSON parse 失敗: " (.-message e)))
           nil))))

;; ---------------------------------------------------------------------------
;; corpus IO — 1 行 1 EDN map（append-only shard、superproject の各 ledger と同型）

(defn write-shard!
  "レコード列を 1 行 1 EDN map で書き出す。**既存を置き換える**（source から
  引き直せる導出データなので、追記して重複させるより冪等な方がよい）。"
  [p records]
  (spit p (apply str (map #(str (pr-str %) "\n") records))))

(defn read-shard [p]
  (->> (str/split-lines (slurp p))
       (remove #(let [t (str/trim %)] (or (empty? t) (str/starts-with? t ";"))))
       (keep (fn [l] (try (edn/read-string {:default (fn [_ v] v)} l)
                          (catch :default _ nil))))))

(defn shard-files [dir]
  (if (exists? dir)
    (->> (.readdirSync fs dir)
         (filter #(str/ends-with? % ".edn"))
         sort
         (map #(str dir "/" %))
         vec)
    []))

(defn read-corpus [dir]
  (vec (mapcat read-shard (shard-files dir))))
