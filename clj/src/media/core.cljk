(ns media.core
  "Pure media.gftd.ai core — the A→B medium logic, compiled to an ES module by
  shadow-cljs and called from ../src/app.cljc. No async IO: app.cljc owns fetch, the
  magatama SDK and Web Crypto (sha256 → subject/link ids). These fns validate,
  build the kotoba Datomic EDN tx/query payloads for subjects (B) and links
  (A→B), shape query results, and format the genre-desk post text.

  Wire format mirrors the news core / yatabase client: a transaction is an EDN
  vector of [:db/add E A V] ops; pr-str reproduces the grammar exactly."
  (:require [kotoba.lang.text :as str]
            [clojure.edn :as edn]
            [media.taxonomy :as tax]
            #?(:cljs [cljs.reader])))

(defn- ->clj [x] #?(:cljs (js->clj x :keywordize-keys true) :clj x))
(defn- ->js  [x] #?(:cljs (clj->js x) :clj x))
(defn- blank? [v] (or (nil? v) (and (string? v) (str/blank? v))))
(defn- read-edn [s] #?(:cljs (cljs.reader/read-string s) :clj (edn/read-string s)))

;; ── slug / genre desk DID ───────────────────────────────────────────────────

(defn slugify [s]
  (-> (or s "") str/lower
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn desk-writer-did
  "did:web:media.gftd.ai:genre:{genre} — the genre desk's attribution DID."
  [genre]
  (let [g (slugify genre)]
    (if (str/blank? g) "did:web:media.gftd.ai" (str "did:web:media.gftd.ai:genre:" g))))

;; ── EDN tx construction ─────────────────────────────────────────────────────

(defn- edn-scalar [v]
  (cond
    (nil? v) nil
    (or (string? v) (number? v) (boolean? v) (true? v) (false? v)) v
    (or (map? v) (sequential? v)) (pr-str v)
    :else (str v)))

(defn add-ops [eid attr-map]
  (into [] (keep (fn [[a v]] (let [v* (edn-scalar v)] (when (some? v*) [:db/add eid a v*])))) attr-map))

(defn- tx-edn [ops] (pr-str (vec ops)))

(def ^:private subject-field->attr
  {:kind :media.subject/kind :label :media.subject/label :did :media.subject/did
   :cohortDims :media.subject/cohortDims :region :media.subject/region :topic :media.subject/topic
   :lang :media.subject/lang})

(defn subject->tx-edn
  "JS subject (carries `id` = \"subj-<kind>-<key>\", `createdAt`) → tx EDN."
  [js-subject]
  (let [s (->clj js-subject) eid (:id s)
        m (-> (reduce (fn [acc [f a]] (if-some [v (get s f)] (assoc acc a v) acc)) {} subject-field->attr)
              (assoc :media.subject/id eid
                     :media.subject/createdAt (:createdAt s)
                     :media.subject/updatedAt (:createdAt s)))]
    (tx-edn (add-ops eid m))))

(def ^:private link-field->attr
  {:genre :media.link/genre :relation :media.link/relation
   :sourceId :media.link/sourceId :sourceUrl :media.link/sourceUrl :subjectId :media.link/subjectId
   :title :media.link/title :brief :media.link/brief :writerDid :media.link/writerDid
   :bridgeScores :media.link/bridgeScores :arbitrage :media.link/arbitrage :relevance :media.link/relevance
   :provenance :media.link/provenance :socialPost :media.link/socialPost :lang :media.link/lang})

(defn link->tx-edn
  "JS link (carries `id` = \"lnk-<hash>\", `createdAt`) → tx EDN upserting the
  A→B medium edge."
  [js-link]
  (let [l (->clj js-link) eid (:id l)
        m (-> (reduce (fn [acc [f a]] (if-some [v (get l f)] (assoc acc a v) acc)) {} link-field->attr)
              (assoc :media.link/id eid
                     :media.link/createdAt (:createdAt l)
                     :media.link/updatedAt (:createdAt l)
                     :media.link/delivered (boolean (:delivered l))))]
    (tx-edn (add-ops eid m))))

(defn reconcile-delivery-tx-edn
  "EDN tx marking a link delivered + recording its post uri."
  [link-id post-uri updated-at]
  (tx-edn (add-ops link-id {:media.link/delivered true
                            :media.link/postUri post-uri
                            :media.link/updatedAt updated-at})))

;; ── query construction (full-entity pull; app.cljc shapes/paginates) ──────────

(defn q-list-links [genre]
  (pr-str
   (if (blank? genre)
     '[:find (pull ?e [*]) :where [?e :media.link/id ?id]]
     [:find '(pull ?e [*]) :where ['?e :media.link/id '?id] ['?e :media.link/genre genre]])))

(defn q-by-link-id [link-id]
  (pr-str [:find '(pull ?e [*]) :where ['?e :media.link/id link-id]]))

(defn q-links-for-subject [subject-id]
  (pr-str [:find '(pull ?e [*]) :where ['?e :media.link/id '?id] ['?e :media.link/subjectId subject-id]]))

(defn q-list-subjects [kind]
  (pr-str
   (if (blank? kind)
     '[:find (pull ?e [*]) :where [?e :media.subject/id ?id]]
     [:find '(pull ?e [*]) :where ['?e :media.subject/id '?id] ['?e :media.subject/kind kind]])))

;; ── result decoding (shared with news core) ─────────────────────────────────

(defn decode-cell [x]
  (if-not (string? x)
    x
    (cond
      (and (>= (count x) 2) (str/starts-with? x "\"") (str/ends-with? x "\""))
      (-> (subs x 1 (dec (count x))) (str/replace "\\\"" "\"") (str/replace "\\\\" "\\"))
      (= x "true") true (= x "false") false (= x "nil") nil
      (re-matches #"[-+]?\d+" x)      #?(:cljs (js/parseInt x 10) :clj (parse-long x))
      (re-matches #"[-+]?\d+\.\d+" x) #?(:cljs (js/parseFloat x) :clj (parse-double x))
      :else x)))

(defn- attr-map->js [m]
  (->js (into {} (map (fn [[k v]] [(subs (str k) 1) v])) m)))

(defn shape-rows
  "Decode raw `.q` rows (each a single pulled-entity column) → JS array of
  plain entity objects keyed by full attribute string."
  [js-rows-edn]
  (let [rows (->clj js-rows-edn)]
    (->js (into []
                (keep (fn [row]
                        (let [cell (if (sequential? row) (first row) row)]
                          (when (string? cell)
                            (let [p (try (read-edn cell) (catch #?(:cljs :default :clj Throwable) _ nil))]
                              (when (map? p) (attr-map->js p)))))))
                rows))))

;; ── post text (genre-desk, B-framed) ────────────────────────────────────────

(defn- truncate [s n] (if (and (string? s) (> (count s) n)) (str (subs s 0 (dec n)) "…") s))

(defn link->post-text
  "Attributed Bluesky post text for an A→B link (≤300). Headline framed for B,
  then the brief, then the A source url."
  [js-link]
  (let [l (->clj js-link)]
    (->> [(truncate (:title l) 160) (truncate (:brief l) 100) (:sourceUrl l)]
         (remove blank?) (str/join "\n") (#(truncate % 300)))))

;; ── validation ──────────────────────────────────────────────────────────────

(defn- require-fields [m fields]
  (let [missing (filter #(blank? (get m %)) fields)]
    (if (seq missing)
      {:valid false :error (str "missing required field(s): " (str/join ", " (map name missing)))}
      {:valid true})))

(defn validate-subject [js-obj]
  (let [m (->clj js-obj) base (require-fields m [:kind :label])]
    (->js (if (and (:valid base) (not (tax/kind? (:kind m))))
            {:valid false :error (str "unknown subject kind: " (:kind m)
                                      " (expected one of " (str/join ", " (sort tax/subject-kinds)) ")")}
            base))))

(defn validate-link [js-obj]
  (let [m (->clj js-obj) base (require-fields m [:genre :subjectId])]
    (->js (if (and (:valid base) (not (tax/genre? (:genre m))))
            {:valid false :error (str "unknown genre: " (:genre m)
                                      " (expected one of " (str/join ", " (sort tax/genres)) ")")}
            base))))
