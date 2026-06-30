(ns media.desks
  "Per-genre desk profiles, A→B relation inference, and canonical subject (B)
  seeds. Pure. Profiles steer generation (audience + tone); relation inference
  picks the edge type from the source instead of always defaulting to
  \"explains\"; seeds give autopilot real targets per genre."
  (:require [clojure.string :as str]
            [media.taxonomy :as tax]))

(defn- ->js [x] #?(:cljs (clj->js x) :clj x))

(def profiles
  {"tech"     {:audience "builders and researchers"            :tone "precise, technical" :relation "explains"}
   "policy"   {:audience "affected citizens and regulated orgs" :tone "plain, actionable"  :relation "impacts"}
   "labor"    {:audience "workers and vulnerable cohorts"       :tone "supportive, practical" :relation "serves"}
   "markets"  {:audience "sector participants and holders"      :tone "concise, analytical" :relation "impacts"}
   "incident" {:audience "affected communities"                 :tone "urgent, clear"      :relation "alerts"}
   "culture"  {:audience "fans and creators"                    :tone "engaging"           :relation "contextualizes"}
   "local"    {:audience "neighborhood residents"               :tone "neighborly, concrete" :relation "serves"}
   "science"  {:audience "patients and the public"              :tone "careful, evidence-based" :relation "advises"}})

(defn profile [genre] (get profiles (tax/normalize-genre genre)))

;; source-text cue → relation (checked before falling back to the desk default).
(def ^:private relation-cues
  [[#"(?i)warn|risk|danger|hazard|警告|危険"        "warns"]
   [#"(?i)alert|emergency|breaking|速報|緊急"        "alerts"]
   [#"(?i)how to|guide|apply|tutorial|申請|手引"     "advises"]
   [#"(?i)deadline|support|free|hotline|支援|無料"   "serves"]
   [#"(?i)fund|grant|subsidy|補助|助成"              "funds"]
   [#"(?i)track|monitor|index|追跡|指数"             "tracks"]
   [#"(?i)enable|unlock|launch|release|可能に|公開"   "enables"]])

(defn infer-relation
  "Pick an A→B relation from source text cues, else the genre desk's default,
  else \"explains\". Always returns a taxonomy-valid relation."
  [genre text]
  (let [t (or text "")
        cue (some (fn [[re rel]] (when (re-find re t) rel)) relation-cues)]
    (tax/normalize-relation (or cue (:relation (profile genre)) "explains"))))

;; Canonical subjects (B) per genre — 2 each (16) so autopilot has targets.
(def seed-subjects
  [{:kind "discipline" :label "AI researchers and builders"  :topic "AI research"   :genre "tech"}
   {:kind "discipline" :label "software engineers"           :topic "software"      :genre "tech"}
   {:kind "cohort"     :label "affected citizens"            :topic "public policy" :genre "policy"}
   {:kind "org"        :label "regulated enterprises"        :topic "compliance"    :genre "policy"}
   {:kind "cohort"     :label "low-income workers"           :topic "labor welfare" :genre "labor"}
   {:kind "cohort"     :label "job seekers"                  :topic "employment"    :genre "labor"}
   {:kind "market"     :label "equity investors"             :topic "markets"       :genre "markets"}
   {:kind "cohort"     :label "retail holders"               :topic "investing"     :genre "markets"}
   {:kind "place"      :label "affected regions"             :topic "safety"        :genre "incident"}
   {:kind "cohort"     :label "local residents"              :topic "emergency"     :genre "incident"}
   {:kind "cohort"     :label "fans and audiences"           :topic "culture"       :genre "culture"}
   {:kind "cohort"     :label "independent creators"         :topic "creative work" :genre "culture"}
   {:kind "place"      :label "city residents"               :topic "local"         :genre "local"}
   {:kind "cohort"     :label "community groups"             :topic "community"     :genre "local"}
   {:kind "cohort"     :label "patients and caregivers"      :topic "health"        :genre "science"}
   {:kind "discipline" :label "research community"           :topic "science"       :genre "science"}])

;; ── JS exports ───────────────────────────────────────────────────────────────
(defn supported-desks [] (->js (vec (sort (keys profiles)))))
(defn js-profile [genre] (->js (profile genre)))
(defn js-infer-relation [genre text] (infer-relation genre text))
(defn js-seed-subjects [] (->js seed-subjects))
