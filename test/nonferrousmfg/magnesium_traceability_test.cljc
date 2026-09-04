(ns nonferrousmfg.magnesium-traceability-test
  "Focused tests for the magnesium MES traceability decision contract.
  Pure functions -- no store, no graph, no LLM, no equipment."
  (:require [clojure.test :refer [deftest is testing]]
            [nonferrousmfg.magnesium-traceability :as mt]))

(def good-batch
  {:lot-id "LOT-MG-001"
   :heat-id "HEAT-4471"
   :alloy-grade :az91d
   :melt-temp-c-record 680.0
   :cover-gas-record {:agent :sf6-n2-mix :flow-lpm 1.2}
   :dust-collection-ok? true
   :machine-guard-ok? true
   :no-water-contact-ok? true
   :class-d-present? true
   :defect-rate-recorded? true})

(deftest traceable-batch-passes
  (testing "a fully recorded batch with all interlocks verified is :traceable"
    (let [a (mt/assess-batch good-batch)]
      (is (= :traceable (:verdict a)))
      (is (empty? (:gaps a)))
      (is (empty? (:interlock-failures a))))))

(deftest non-magnesium-alloy-is-out-of-scope
  (testing "aluminum batches are assessed by the generic foundry path, not this contract"
    (is (= :not-magnesium-scope (:verdict (mt/assess-batch
                                            (assoc good-batch :alloy-grade :aluminum-silicon)))))))

(deftest missing-traceability-fields-quarantine
  (testing "unmeasured fields are gaps, never defaults"
    (let [a (mt/assess-batch (dissoc good-batch :melt-temp-c-record :cover-gas-record))]
      (is (= :quarantine (:verdict a)))
      (is (= [:cover-gas-record :melt-temp-c-record] (:gaps a))))))

(deftest unmeasured-sentinel-is-a-gap
  (testing ":unmeasured / :unknown values do not count as measurements"
    (let [a (mt/assess-batch (assoc good-batch :melt-temp-c-record :unmeasured))]
      (is (= :quarantine (:verdict a)))
      (is (= [:melt-temp-c-record] (:gaps a))))))

(deftest interlock-failure-quarantines
  (testing "a failed dust-collection interlock quarantines even a data-complete batch"
    (let [a (mt/assess-batch (assoc good-batch :dust-collection-ok? false))]
      (is (= :quarantine (:verdict a)))
      (is (= [:dust-collection-ok?] (:interlock-failures a))))))

(deftest absent-interlock-field-quarantines
  (testing "an interlock that was never verified counts as failed, not as passing"
    (let [a (mt/assess-batch (dissoc good-batch :class-d-present?))]
      (is (= :quarantine (:verdict a)))
      (is (= [:class-d-present?] (:interlock-failures a))))))

(deftest gaps-reported-on-interlock-quarantine-too
  (testing "both gap classes are reported in one assessment"
    (let [a (mt/assess-batch (-> good-batch
                                 (assoc :no-water-contact-ok? false)
                                 (dissoc :heat-id)))]
      (is (= :quarantine (:verdict a)))
      (is (= [:heat-id] (:gaps a)))
      (is (= [:no-water-contact-ok?] (:interlock-failures a))))))

(deftest every-assessment-carries-audit-tail
  (testing "audit tail asserts no equipment command and no invented values"
    (let [a (mt/assess-batch good-batch)]
      (is (false? (get-in a [:audit :audit/bot-commanded-equipment])))
      (is (false? (get-in a [:audit :audit/values-invented])))
      (is (= :assess-batch (get-in a [:audit :audit/op])))
      (is (= "LOT-MG-001" (get-in a [:audit :audit/subject]))))))

(deftest traceable-batch-gets-release-proposal
  (testing "a traceable batch yields a release proposal that still goes through human shipment approval"
    (let [d (mt/shipment-decision (mt/assess-batch good-batch) {:shipment-id "SHIP-9" :destination "buyer-yard-north"})]
      (is (= :release-proposal (:decision d)))
      (is (true? (get-in d [:audit :audit/requires-human-shipment-approval]))))))

(deftest quarantine-refuses-shipment-without-approval
  (testing "a quarantined batch with no human approval is refused"
    (let [a (mt/assess-batch (assoc good-batch :machine-guard-ok? false))
          d (mt/shipment-decision a {:shipment-id "SHIP-10" :destination "buyer-yard-north"})]
      (is (= :refuse (:decision d))))))

(deftest quarantine-release-requires-scoped-human-approval
  (testing "release of a quarantine needs BOTH scopes and a named human approver"
    (let [a (mt/assess-batch (assoc good-batch :machine-guard-ok? false))
          d1 (mt/shipment-decision a {:shipment-id "S11"
                                      :human-approval {:by "supervisor-7"
                                                       :scope #{:quarantine-release}}})]
      (is (= :refuse (:decision d1)) "missing :shipment scope must refuse")
      (let [d2 (mt/shipment-decision a {:shipment-id "S12"
                                        :human-approval {:by "supervisor-7"
                                                         :scope #{:quarantine-release :shipment}}})]
        (is (= :quarantine-release-proposal (:decision d2)))
        (is (= "supervisor-7" (get-in d2 [:audit :audit/human-approver])))))))

(deftest out-of-scope-batch-cannot-be-shipped-through-this-contract
  (testing "even a forged approval does not ship a non-magnesium batch via this module"
    (let [a (mt/assess-batch (assoc good-batch :alloy-grade :aluminum-silicon))
          d (mt/shipment-decision a {:shipment-id "S13"
                                     :human-approval {:by "supervisor-7"
                                                      :scope #{:quarantine-release :shipment}}})]
      (is (= :refuse (:decision d))))))

(deftest shipment-decision-carries-audit-tail
  (testing "shipment decisions also assert no equipment command"
    (let [d (mt/shipment-decision (mt/assess-batch good-batch) {:shipment-id "S14"})]
      (is (false? (get-in d [:audit :audit/bot-commanded-equipment]))))))
