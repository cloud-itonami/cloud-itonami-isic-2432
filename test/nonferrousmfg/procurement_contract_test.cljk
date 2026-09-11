(ns nonferrousmfg.procurement-contract-test
  "The equipment-procurement decision contract as executable tests --
  activity -> decision -> effect -> audit for the sourcing-draft cell.
  The single invariant under test:

    A `:coordinate-equipment-procurement` proposal can only become a
    committed PRO-###### sourcing draft when the condition and
    sourcing route are declared from the closed sets, every cost claim
    carrying a number carries its own provenance (source + measured-at
    -- unmeasured values are left ABSENT, never guessed), the
    equipment class is named -- and even then it NEVER auto-commits at
    any phase: approving a procurement draft becomes a real financial
    commitment, which is always the human approver's act. Every
    decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [nonferrousmfg.phase :as phase]
            [nonferrousmfg.store :as store]
            [nonferrousmfg.operation :as op]))

(defn- fresh []
  (let [db (-> (store/mem-store) (store/sample-data!))]
    [db (op/build db)]))

(def coordinator {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- procurement-request
  ([] (procurement-request {}))
  ([overrides]
   {:op :coordinate-equipment-procurement :effect :propose :subject "pro-1"
    :value (merge {:equipment-class "magnesium-hpdc-machine"
                   :condition :new
                   :sourcing-route :direct-manufacturer
                   :cost-claims {}}
                  overrides)}))

(deftest clean-procurement-always-needs-approval
  (testing "a governor-clean procurement draft still escalates -- procurement approval is a financial commitment, always human"
    (let [[db actor] (fresh)
          res (exec-op actor "p1"
                       (procurement-request {:cost-claims
                                             {:price-jpy {:value 48000000
                                                          :source "manufacturer quotation 2026-08-30"
                                                          :measured-at "2026-08-30"}}})
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "p1")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:proposed? (store/procurement db "pro-1"))))
        (is (= "PRO-000000" (:procurement-number (store/procurement db "pro-1"))))
        (is (= 1 (count (store/procurement-history db))))
        (is (= 1 (count (store/ledger db))))))))

(deftest procurement-rejection-holds
  (testing "a human rejecting the escalation -> HOLD, no SSoT mutation, hold fact on the ledger"
    (let [[db actor] (fresh)
          res (exec-op actor "p2" (procurement-request) coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (reject! actor "p2")]
        (is (= :hold (get-in r2 [:state :disposition])))
        (is (nil? (store/procurement db "pro-1")))
        (is (some #{:approval-rejected} (map :t (store/ledger db))))
        (is (= 0 (count (store/procurement-history db))))))))

(deftest fabricated-condition-is-held
  (testing "a condition value outside the closed set -> HARD hold, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "p3"
                       (procurement-request {:condition :slightly-used}) coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:invalid-procurement-condition} (-> (store/ledger db) first :basis))))))

(deftest omitted-condition-is-held
  (testing "silently omitting the condition is not honesty -- HARD hold like a fabricated value"
    (let [[db actor] (fresh)
          req (assoc (procurement-request)
                     :value (dissoc (:value (procurement-request)) :condition))
          res (exec-op actor "p4" req coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:invalid-procurement-condition} (-> (store/ledger db) first :basis))))))

(deftest unknown-condition-is-legal-and-escalates
  (testing "`:unknown` is the honest declaration for an uninspected unit -- passes the governor, still needs human approval"
    (let [actor (op/build (store/mem-store))
          res (exec-op actor "p5"
                       (procurement-request {:condition :unknown}) coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "p5")]
        (is (= :commit (get-in r2 [:state :disposition])))))))

(deftest fabricated-sourcing-route-is-held
  (testing "a sourcing route outside the closed set -> HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "p6"
                       (procurement-request {:sourcing-route :broker-special-deal}) coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:invalid-sourcing-route} (-> (store/ledger db) first :basis))))))

(deftest invented-price-is-held
  (testing "a cost claim carrying a number WITHOUT source + measured-at -> HARD hold (never let an invented price through)"
    (let [[db actor] (fresh)
          res (exec-op actor "p7"
                       (procurement-request {:cost-claims {:price-jpy {:value 48000000}}})
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:invented-cost-claim} (-> (store/ledger db) first :basis))))))

(deftest invented-lead-time-is-held
  (testing "the same rule binds lead-time / capacity / utility claims, not just price"
    (let [[db actor] (fresh)
          res (exec-op actor "p8"
                       (procurement-request {:cost-claims {:lead-time-days {:value 180}}})
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:invented-cost-claim} (-> (store/ledger db) first :basis))))))

(deftest fabricated-cost-field-is-held
  (testing "a claim key outside the closed cost-claim-keys set -> HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "p9"
                       (procurement-request {:cost-claims
                                             {:annual-discount-jpy {:value 1000000
                                                                    :source "verbal promise"
                                                                    :measured-at "2026-08-30"}}})
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:invented-cost-claim} (-> (store/ledger db) first :basis))))))

(deftest unmeasured-values-omitted-commit-after-approval
  (testing "leaving unmeasured costs ABSENT is the honest shape -- clean draft, human approval, PRO record carries no invented numbers"
    (let [[db actor] (fresh)
          _ (exec-op actor "p10" (procurement-request) coordinator)
          r2 (approve! actor "p10")
          p (store/procurement db "pro-1")]
      (is (= :commit (get-in r2 [:state :disposition])))
      (is (= {} (:cost-claims p)) "no unmeasured value was invented on commit"))))

(deftest missing-equipment-class-is-held
  (testing "a procurement draft naming no equipment class -> HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "p11"
                       (procurement-request {:equipment-class ""}) coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:missing-procurement-class} (-> (store/ledger db) first :basis))))))

(deftest no-double-proposal
  (testing "the same procurement record cannot be proposed twice (dedicated :proposed? fact)"
    (let [[db actor] (fresh)
          r1 (exec-op actor "p12" (procurement-request) coordinator)
          _ (approve! actor "p12")
          r2 (exec-op actor "p13" (procurement-request) coordinator)]
      (is (= :interrupted (:status r1)))
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (some #{:already-proposed} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/procurement-history db)))))))

(deftest procurement-never-auto-commits-at-any-phase
  (testing "even governor-clean and at phase 3, procurement is never in any :auto set"
    (is (every? #(not (contains? (:auto (val %)) :coordinate-equipment-procurement))
                phase/phases))
    (is (contains? (:writes (get phase/phases 3)) :coordinate-equipment-procurement))))

(deftest proposal-only-effect-boundary-holds-for-procurement
  (testing "a procurement request whose caller effect is not :propose -> unconditional HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "p14"
                       (assoc (procurement-request) :effect :issue-purchase-order)
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:not-propose-effect} (-> (store/ledger db) first :basis))))))
