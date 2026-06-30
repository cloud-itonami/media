(ns media.desks-test
  "JVM unit tests for desk profiles, relation inference, and subject seeds."
  (:require [clojure.test :refer [deftest is testing]]
            [media.desks :as desks]
            [media.taxonomy :as tax]))

(deftest desk-profiles
  (testing "every genre desk has a complete profile"
    (is (= (set (keys desks/profiles)) tax/genres))
    (is (every? (fn [p] (and (:audience p) (:tone p) (:relation p))) (vals desks/profiles)))
    (is (tax/relation? (:relation (desks/profile "incident"))))
    ;; unknown genre falls back to the tech desk (normalize-genre default)
    (is (= (desks/profile "tech") (desks/profile "BOGUS")))))

(deftest relation-inference
  (testing "cues win over the desk default, result always taxonomy-valid"
    (is (= "warns"   (desks/infer-relation "science" "study finds health risk")))
    (is (= "alerts"  (desks/infer-relation "incident" "BREAKING: emergency declared")))
    (is (= "advises" (desks/infer-relation "labor" "how to apply for support")))
    (is (= "funds"   (desks/infer-relation "policy" "new subsidy grant announced")))
    (is (= "warns"   (desks/infer-relation "policy" "危険な状況への警告")))
    ;; no cue → desk default
    (is (= "impacts" (desks/infer-relation "markets" "quarterly earnings came in steadily")))
    (is (= "explains" (desks/infer-relation "tech" "a calm overview of the system")))
    (is (every? tax/relation? (map #(desks/infer-relation % "") (seq tax/genres))))))

(deftest subject-seeds
  (testing "16 canonical subjects, 2 per genre, all valid kinds"
    (is (= 16 (count desks/seed-subjects)))
    (is (every? #(tax/kind? (:kind %)) desks/seed-subjects))
    (is (every? #(tax/genre? (:genre %)) desks/seed-subjects))
    (is (= tax/genres (set (map :genre desks/seed-subjects))))
    (is (= 2 (count (filter #(= "tech" (:genre %)) desks/seed-subjects))))))
