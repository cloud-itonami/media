(ns media.schema
  "Datomic attribute schema for media.gftd.ai — the A→B medium graph.

  Two entity classes plus the reified medium edge:
   - :media.subject/*  = B (recipient): cohort | org | place | market | …
   - :media.link/*     = the medium itself: a directed A→B edge from a
     news primary source (A) to a subject (B), carrying genre, relation type,
     bridge scores and the rendering reference. news's :news/* source corpus is
     A (read cross-graph); the human-readable article rendering is produced by
     the generate step and referenced here.")

;; ── B = subject (recipient) ───────────────────────────────────────────────
(def subject-attrs
  #{:media.subject/id        ; "subj-<kind>-<key>"  (stable entity ref)
    :media.subject/kind      ; cohort | org | place | market | discipline | did
    :media.subject/label
    :media.subject/did       ; delivery DID when B is itself an actor
    :media.subject/cohortDims ; EDN-string map (cohort attributes, PII-free)
    :media.subject/region
    :media.subject/topic
    :media.subject/lang      ; ISO 639-1 — target language for delivery to B
    :media.subject/createdAt
    :media.subject/updatedAt})

;; ── the medium = A→B link (reified relation) ──────────────────────────────
(def link-attrs
  #{:media.link/id          ; "lnk-<sha256(sourceId|subjectId|genre)>"
    :media.link/genre       ; tech | policy | labor | markets | …
    :media.link/relation    ; impacts | enables | warns | serves | explains | …
    :media.link/sourceId    ; → A  (news :news/id, cross-graph ref)
    :media.link/sourceUrl   ; denormalized A provenance
    :media.link/subjectId   ; → B  (:media.subject/id)
    :media.link/title       ; rendering: B-framed headline
    :media.link/brief       ; rendering: B-framed body (grounded in A)
    :media.link/writerDid   ; did:web:media.gftd.ai:genre:{genre}
    :media.link/bridgeScores ; EDN-string map {inequalityBridge … actionability}
    :media.link/arbitrage   ; social-arbitrage score (0-100)
    :media.link/relevance   ; A→B relevance (0-100)
    :media.link/provenance  ; EDN-string map (A source attribution chain)
    :media.link/socialPost
    :media.link/lang        ; ISO 639-1 — language the brief/post was rendered in
    :media.link/delivered   ; boolean — a public post landed
    :media.link/postUri     ; at:// uri of the delivered post
    :media.link/createdAt
    :media.link/updatedAt})
