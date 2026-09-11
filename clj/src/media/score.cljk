(ns media.score
  "Pure A→B bridge scoring. The medium's value = how much it bridges the gap
  between a primary source (A) and a subject (B). Generalizes the news bridge
  metric (inequality/loneliness/separation) plus an A↔B topical-overlap term:
  a strong link is one where A is highly relevant to B AND closes a real gap."
  (:require [kotoba.lang.text :as str]
            [clojure.set :as set]))

(defn- ->clj [x] #?(:cljs (js->clj x :keywordize-keys true) :clj x))
(defn- ->js  [x] #?(:cljs (clj->js x) :clj x))

(def ^:private inequality-terms ["inequality" "poverty" "wage" "gap" "discriminat" "afford" "eviction" "debt" "格差" "貧困"])
(def ^:private loneliness-terms ["lonely" "loneliness" "isolat" "solitude" "孤独" "孤立"])
(def ^:private separation-terms ["separat" "divid" "exclud" "marginal" "segregat" "分断" "排除"])
(def ^:private action-terms     ["how to" "guide" "apply" "deadline" "free" "support" "hotline" "申請" "支援" "無料"])

(defn- hits [text terms]
  (let [t (str/lower (or text ""))]
    (reduce (fn [n term] (if (str/includes? t term) (inc n) n)) 0 terms)))

(defn- clamp [x lo hi] (max lo (min hi x)))
(defn- tokens [s] (set (re-seq #"[a-z0-9]+" (str/lower (or s "")))))

(defn- overlap
  "A↔B topical overlap (0-100): Jaccard of source text tokens vs subject label/
  topic tokens. The relevance term — does this source actually concern B."
  [source-text subject-text]
  (let [a (tokens source-text) b (tokens subject-text)]
    (if (or (empty? a) (empty? b)) 0
        (int (* 100 (/ (count (set/intersection a b))
                       (max 1 (count (set/union a b)))))))))

(defn score-bridge
  "JS entry: ({sourceText, subjectText}) → {relevance, arbitrage, bridgeScores}.
  Deterministic, LLM-free baseline for ranking A→B candidates."
  [js-obj]
  (let [o (->clj js-obj)
        src (:sourceText o)
        bridges {:inequalityBridge (clamp (* (hits src inequality-terms) 20) 0 100)
                 :lonelinessBridge (clamp (* (hits src loneliness-terms) 20) 0 100)
                 :separationBridge (clamp (* (hits src separation-terms) 20) 0 100)
                 :actionability    (clamp (* (hits src action-terms) 20) 0 100)}
        relevance (overlap src (:subjectText o))
        gap (/ (+ (:inequalityBridge bridges) (:lonelinessBridge bridges) (:separationBridge bridges)) 3.0)
        ;; arbitrage = relevant AND closes a gap AND actionable (multiplicative-ish)
        arbitrage (clamp (int (* 0.5 (+ (* 0.5 relevance) (* 0.3 gap) (* 0.2 (:actionability bridges))) )) 0 100)]
    (->js {:relevance relevance :arbitrage arbitrage :bridgeScores bridges})))
