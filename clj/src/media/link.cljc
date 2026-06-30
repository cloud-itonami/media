(ns media.link
  "Pure A→B candidate resolution: given a primary source (A) and the known
  subjects (B) in the graph, rank which subjects this source plausibly connects
  to, with a bridge score per candidate. Deterministic baseline — app.cljc may
  escalate ambiguous cases to the pod / LLM for richer entity resolution."
  (:require [media.score :as score]
            [clojure.string :as str]))

(defn- ->clj [x] #?(:cljs (js->clj x :keywordize-keys true) :clj x))
(defn- ->js  [x] #?(:cljs (clj->js x) :clj x))

(defn- subject-text [s]
  (str/join " " (remove str/blank? [(:label s) (:topic s) (:region s)])))

(defn candidate-subjects
  "JS entry: ({source:{title,summary,text,topic,region}, subjects:[...], min, max})
  → JS array of {subjectId, kind, relevance, arbitrage, bridgeScores}, sorted by
  arbitrage desc, filtered to relevance >= min (default 10), capped at max
  (default 5)."
  [js-obj]
  (let [o (->clj js-obj)
        src-text (str/join " " (remove str/blank?
                                       [(get-in o [:source :title]) (get-in o [:source :summary])
                                        (get-in o [:source :text]) (get-in o [:source :topic])
                                        (get-in o [:source :region])]))
        min-rel (or (:min o) 10)
        max-n   (or (:max o) 5)
        scored (->> (:subjects o)
                    (map (fn [s]
                           (let [sc (->clj (score/score-bridge
                                            (->js {:sourceText src-text
                                                   :subjectText (subject-text s)})))]
                             {:subjectId (:id s) :kind (:kind s)
                              :relevance (:relevance sc) :arbitrage (:arbitrage sc)
                              :bridgeScores (:bridgeScores sc)})))
                    (filter #(>= (:relevance %) min-rel))
                    (sort-by :arbitrage >)
                    (take max-n))]
    (->js (vec scored))))
