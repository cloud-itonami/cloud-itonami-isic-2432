(ns nonferrousmfg.dust-control-contract-test
  "The MAGNESIUM FINISHING / COMBUSTIBLE-DUST cell decision contract as
  executable tests -- activity -> decision -> effect -> audit for
  `:coordinate-dust-control`:

    - a clean proposal against a verified+registered finishing unit
      ESCALATES (never auto-commits at any phase -- dust hazard is
      always a human plant supervisor's call) and, once approved,
      commits a DCD dust-control draft record + one ledger fact;
    - an unverified/unregistered finishing unit -> HARD hold;
    - an unknown control measure -> HARD hold;
    - an INVENTED dust-explosivity measurement (Kst/MIE/MEC/capacity)
      -> HARD hold, unoverridable, even by an approving supervisor
      (fabricated measurement, never let through);
    - the same proposal coordinated twice -> HARD hold (dedicated
      `:proposed?` fact);
    - rejected approval -> hold, no SSoT mutation."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
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

(defn- dust-request [subject equipment-id & [extra]]
  {:op :coordinate-dust-control :effect :propose :subject subject
   :value (merge {:equipment-id equipment-id
                  :control-measure :housekeeping-interval
                  :unmeasured [:kst-bar-m-s :mie-mj :mec-g-m3 :collector-capacity-m3-h]}
                 extra)})

(deftest clean-dust-control-escalates-then-commits-on-approval
  (testing "dust-hazard stake -> always human approval, even when governor-clean"
    (let [[db actor] (fresh)
          res (exec-op actor "dc1" (dust-request "dcd-1" "finish-001") coordinator)]
      (is (= :interrupted (:status res)) "never auto-commits -- high-stakes escalation")
      (is (empty? (store/dust-control-history db)) "nothing committed before approval")
      (let [r2 (approve! actor "dc1")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:proposed? (store/dust-control db "dcd-1"))))
        (is (= 1 (count (store/dust-control-history db))))
        (let [facts (vec (store/ledger db))]
          (is (= 1 (count facts)) "exactly one audit fact")
          (is (= :coordinate-dust-control (:op (first facts))))
          (is (= "DCD-000000" (:dust-control-number (store/dust-control db "dcd-1")))))))))

(deftest dust-control-unverified-equipment-holds
  (let [[db actor] (fresh)
        res (exec-op actor "dc2" (dust-request "dcd-2" "finish-002") coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (not= :interrupted (:status res)) "HARD hold is not overridable")
    (is (some #{:dust-control-equipment-not-verified} (-> (store/ledger db) last :basis)))
    (is (empty? (store/dust-control-history db)))))

(deftest dust-control-unknown-measure-holds
  (let [[db actor] (fresh)
        res (exec-op actor "dc3"
                  (dust-request "dcd-3" "finish-001" {:control-measure :magic-dust-eliminator})
                  coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:dust-control-invalid-measure} (-> (store/ledger db) last :basis)))))

(deftest dust-control-invented-measurement-holds
  (testing "a proposal carrying a Kst/MIE value is a fabricated measurement -- HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "dc4"
                    (dust-request "dcd-4" "finish-001" {:kst-bar-m-s 50.0})
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:dust-control-invented-measurement} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dust-control-history db))))))

(deftest dust-control-double-proposal-holds
  (let [[db actor] (fresh)
        _ (do (exec-op actor "dc5a" (dust-request "dcd-5" "finish-001") coordinator)
              (approve! actor "dc5a"))
        res (exec-op actor "dc5b" (dust-request "dcd-5" "finish-001") coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:dust-control-already-proposed} (-> (store/ledger db) last :basis)))
    (is (= 1 (count (store/dust-control-history db))))))

(deftest dust-control-rejected-approval-holds
  (let [[db actor] (fresh)
        _ (exec-op actor "dc6" (dust-request "dcd-6" "finish-001") coordinator)
        r2 (reject! actor "dc6")]
    (is (= :hold (get-in r2 [:state :disposition])))
    (is (empty? (store/dust-control-history db)) "rejection never mutates the SSoT")
    (is (some #(= :approval-rejected (:t %)) (store/ledger db)))))
