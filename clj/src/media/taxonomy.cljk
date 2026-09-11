(ns media.taxonomy
  "The medium's controlled vocabularies: genre desks, A→B relation types, and
  subject kinds. Pure — used for validation and genre inference. Keeping these
  in one place lets coverage grow by editing a set, not the pipeline."
  (:require [kotoba.lang.text :as str]))

(defn- ->js [x] #?(:cljs (clj->js x) :clj x))

;; Genre desks (path DIDs did:web:media.gftd.ai:genre:{genre}).
(def genres
  #{"tech" "policy" "labor" "markets" "incident" "culture" "local" "science"})

;; A→B relation types — how the source connects to the subject.
(def relations
  #{"explains" "impacts" "enables" "warns" "serves" "contextualizes"
    "alerts" "tracks" "advises" "funds"})

;; Subject (B) kinds.
(def subject-kinds
  #{"cohort" "org" "place" "market" "discipline" "did"})

(defn genre?    [g] (contains? genres (some-> g str str/lower str/trim)))
(defn relation? [r] (contains? relations (some-> r str str/lower str/trim)))
(defn kind?     [k] (contains? subject-kinds (some-> k str str/lower str/trim)))

(defn normalize-genre
  "Known genre (lowercased) or \"tech\" as the safe default."
  [g]
  (let [c (some-> g str str/lower str/trim)]
    (if (contains? genres c) c "tech")))

(defn normalize-relation
  "Known relation or \"explains\" default."
  [r]
  (let [c (some-> r str str/lower str/trim)]
    (if (contains? relations c) c "explains")))

;; ── JS exports ───────────────────────────────────────────────────────────────
(defn supported-genres    [] (->js (vec (sort genres))))
(defn supported-relations [] (->js (vec (sort relations))))
(defn supported-kinds     [] (->js (vec (sort subject-kinds))))
(defn js-genre?           [g] (genre? g))
(defn js-relation?        [r] (relation? r))
(defn js-kind?            [k] (kind? k))
(defn js-normalize-genre  [g] (normalize-genre g))
(defn js-normalize-relation [r] (normalize-relation r))
