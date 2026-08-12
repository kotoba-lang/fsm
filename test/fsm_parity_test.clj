(ns fsm-parity-test
  "Parity gate binding the two implementations of the player FSM together.

   This package states one state machine twice:

     * `src/kami/fsm.cljc` (+ the `src/kotoba/fsm.cljc` facade) — the generic
       map-driven EDN engine. `default-player-fsm` is the transition table the
       bounded profile mirrors. This is what every consumer and `fsm_test.clj`
       actually calls, so it is the ORACLE here.
     * `src/kami/fsm.kotoba` (+ the `src/kotoba/fsm.kotoba` facade) — the closed
       `:kami.fsm/player-v1` profile, with different names and a different
       encoding (a `[:variant …]` per state instead of a keyword, a
       `[:set :keyword]` instead of a Clojure set).

   Until this file existed, nothing connected them. `.github/workflows/ci.yml`
   compiles `test/kotoba/fsm_conformance.kotoba` and checks its `main` returns
   42 — that proves the Kotoba graph links and executes, and it hard-codes five
   hand-picked transitions inside the Kotoba side itself, so it can never
   observe the `.cljc` at all. `fsm_test.clj` tests only the `.cljc`. Either
   side could drift and the build would stay green.

   The gate closes that: it compiles the `.kotoba` in-process and runs it
   against the `.cljc` over the WHOLE closed domain — every state crossed with
   every subset of the declared event alphabet — plus events outside the
   alphabet and two-step sequences.

   Correspondence established (measured, not assumed — see the shape comments
   below):

     | Kotoba export       | `.cljc`                                   |
     |---------------------|-------------------------------------------|
     | `idle-state`        | the state keyword `:idle`, and            |
     |                     | `(initial default-player-fsm)`            |
     | `move-state`        | the state keyword `:move`                 |
     | `jump-state`        | the state keyword `:jump`                 |
     | `advance-player-v1` | `(advance default-player-fsm state events)`|
     |   arg `state`       | the `state` argument                      |
     |   arg `events`      | the `events` set                          |
     |   result            | the returned state keyword                |

   `params` has NO Kotoba counterpart: every variant case carries an `:i64`
   payload that the profile always sets to 0, so the Kotoba side models
   transitions only, not the per-state visual params. `params-is-unbound`
   below pins that as a tripwire rather than leaving it implicit."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            ;; The compiler pin in deps.edn :test exposes the KIR interpreter
            ;; as `kotoba.compiler.ir`. Newer compiler pins renamed it to
            ;; `kotoba.kir`; this repo is pinned to the older layout (verified
            ;; in the resolved dep: src/kotoba/compiler/ir.cljc exists,
            ;; src/kotoba/kir.cljc does not), so require this one. If the pin
            ;; is bumped past the rename, this require is what will fail first.
            [kotoba.compiler.ir :as ir]
            [kotoba.fsm :as cljc]))

;; ---------------------------------------------------------------------------
;; Compiling the `.kotoba` in-process
;; ---------------------------------------------------------------------------

;; `:js-kotoba-v1` is the target the compiler requires for entryless libraries
;; and for typed values, which is what these modules are. The gate never uses
;; the emitted JS — only the KIR, which it walks with the reference
;; interpreter. Compiling is deferred so a compile failure is reported as a
;; failing test rather than a namespace-load crash that hides the rest.
(def ^:private impl-kir
  (delay (:kir (compiler/compile-source (slurp "src/kami/fsm.kotoba")
                                        :js-kotoba-v1))))

;; The facade is a two-module graph (`kotoba.fsm` requires `kami.fsm`), so it
;; needs the project linker rather than single-source compilation.
(def ^:private facade-kir
  (delay (:kir (compiler/compile-project
                {'kami.fsm   (slurp "src/kami/fsm.kotoba")
                 'kotoba.fsm (slurp "src/kotoba/fsm.kotoba")}
                'kotoba.fsm
                :js-kotoba-v1))))

(defn- modules []
  {"src/kami/fsm.kotoba (implementation)" @impl-kir
   "src/kotoba/fsm.kotoba (facade)"       @facade-kir})

;; ---------------------------------------------------------------------------
;; The interpreter boundary. Both shapes below were measured against this pin,
;; not guessed.
;; ---------------------------------------------------------------------------

;; A variant crosses as [schema case-tag payload], schema first, in declared
;; order — e.g. idle-state returns
;;   [[:variant :kami.fsm/player-state-v1 [[:idle :i64] [:move :i64] [:jump :i64]]]
;;    :idle 0]
;; so the `.cljc` state keyword is element 1 and the payload element 2.
(defn- state-tag [value] (nth value 1))
(defn- state-payload [value] (nth value 2))

;; A `[:set :keyword]` crosses as [type items-vector] — NOT a Clojure set. The
;; interpreter sorts and de-duplicates the items itself and rejects anything
;; else with "value is not the declared typed set".
(defn- ->kotoba-events [events] [[:set :keyword] (vec events)])

(def ^:private state-constructor
  {:idle 'idle-state :move 'move-state :jump 'jump-state})

(defn- kotoba-state [kir state]
  (ir/execute kir (state-constructor state) []))

(defn- kotoba-advance
  "Run the Kotoba profile. `state` is a boundary variant value, so the result of
   one call can be fed straight into the next."
  [kir state events]
  (ir/execute kir 'advance-player-v1 [state (->kotoba-events events)]))

(defn- cljc-advance [state events]
  (cljc/advance cljc/default-player-fsm state events))

;; ---------------------------------------------------------------------------
;; The closed domain, enumerated exhaustively
;; ---------------------------------------------------------------------------

(def ^:private states [:idle :move :jump])

;; The alphabet declared for this profile in migration/player-fsm-v1.edn
;; (:canonical :domain :events).
(def ^:private alphabet [:moving :still :jumping])

(defn- subsets [xs]
  (reduce (fn [acc x] (into acc (map #(conj % x)) acc)) #{#{}} xs))

(def ^:private event-sets
  (vec (sort-by (juxt count vec) (subsets alphabet))))

(defn- mismatches
  "Every (state, events) pair on which the two implementations disagree, with
   the inputs, so a failure names the distinguishing case instead of just
   reporting false."
  [kir]
  (vec (for [state states
             events event-sets
             :let [kotoba (state-tag (kotoba-advance kir (kotoba-state kir state) events))
                   expected (cljc-advance state events)]
             :when (not= kotoba expected)]
         {:state state :events events :kotoba kotoba :cljc expected})))

;; ---------------------------------------------------------------------------
;; Gates
;; ---------------------------------------------------------------------------

(def ^:private covered-exports
  '#{idle-state move-state jump-state advance-player-v1})

(deftest every-kotoba-export-is-covered
  ;; If someone adds an export to the profile, this fails until the gate binds
  ;; it to something on the `.cljc` side — an unbound export is exactly the
  ;; condition this file exists to prevent.
  (doseq [[label kir] (modules)]
    (testing label
      (is (= covered-exports (set (:exports kir)))))))

(deftest state-constructors-agree
  (doseq [[label kir] (modules)]
    (testing label
      (doseq [state states]
        (is (= state (state-tag (kotoba-state kir state)))
            (str "constructor " (state-constructor state) " must denote " state)))
      (testing "idle-state is the profile's initial state"
        (is (= (cljc/initial cljc/default-player-fsm)
               (state-tag (kotoba-state kir :idle))))))))

(deftest advance-parity-over-the-whole-domain
  ;; 3 states x 8 event subsets = 24 cases per module. Not a hand-picked few.
  (doseq [[label kir] (modules)]
    (testing label
      (is (= 24 (* (count states) (count event-sets))))
      (is (= [] (mismatches kir))))))

(deftest advance-parity-for-events-outside-the-alphabet
  ;; `[:set :keyword]` accepts any keyword, so a consumer can pass one the
  ;; profile never declared. Both sides must ignore it identically rather than
  ;; one of them trapping or falling through differently.
  (doseq [[label kir] (modules)]
    (testing label
      (doseq [state states
              events [#{:crouch} #{:crouch :still} #{:crouch :moving}
                      #{:crouch :jumping} #{:crouch :moving :still}]]
        (is (= (cljc-advance state events)
               (state-tag (kotoba-advance kir (kotoba-state kir state) events)))
            (str "state " state " events " events))))))

(deftest advance-parity-across-two-steps
  ;; Feeds the RETURNED variant back in rather than rebuilding it from a
  ;; constructor, which is the only way to check that the value the profile
  ;; hands out is accepted as the value it takes in. 3 x 8 x 8 = 192 pairs.
  (doseq [[label kir] (modules)]
    (testing label
      (doseq [state states
              first-events event-sets
              second-events event-sets]
        (let [after-one (kotoba-advance kir (kotoba-state kir state) first-events)
              after-two (state-tag (kotoba-advance kir after-one second-events))
              expected (-> state
                           (cljc-advance first-events)
                           (cljc-advance second-events))]
          (is (= expected after-two)
              (str "state " state " then " first-events " then " second-events)))))))

(deftest params-is-unbound
  ;; `params` is the one `.cljc` export with no Kotoba counterpart. The profile
  ;; carries an :i64 per case and always sets it to 0, so it models transitions
  ;; only. If that payload ever starts carrying data, this fails and forces
  ;; whoever did it to bind it to `params` rather than growing a second,
  ;; unchecked source of per-state values.
  (doseq [[label kir] (modules)]
    (testing label
      (doseq [state states]
        (is (= 0 (state-payload (kotoba-state kir state)))))))
  (testing "the .cljc params the profile does not model"
    (is (= {:emissive 0.35 :scale 1.0} (cljc/params cljc/default-player-fsm :idle)))
    (is (= {:emissive 0.95 :scale 1.08} (cljc/params cljc/default-player-fsm :move)))
    (is (= {:emissive 1.4 :scale 1.16} (cljc/params cljc/default-player-fsm :jump)))))
