(ns nonferrousmfg.methods.test-cartridge-inert-handling
  "Tests for the cartridge dry-inert-handling cell decision contract.
  Mirrors the accepted igata methods-contract test shape
  (test/igata/methods/test_magnesium_hpdc.cljc, PR cloud-itonami/igata#2)."
  (:require [clojure.test :refer [deftest is testing]]
            [nonferrousmfg.methods.cartridge-inert-handling :as cih]))

(def ^:private base-req
  {:activity/id "act-001"
   :powder {:family "MgH2" :lot-id "lot-2026-08-31-A"}
   :measured-o2-ppm 50
   :o2-limit-ppm 100
   :inert-gas {:agent "argon" :measured-flow-lmin 12.5}
   :measured-press-force-kn 80
   :interlocks #{:dry-dust-collection :grounding-bonding :oxygen-monitor :class-d-extinguisher}
   :suppression-agent "argon flood"
   :requested-effect :simulate-plan
   :human-approval {:approver-did "did:web:plant-supervisor.etzhayyim.com"
                    :approved-at "2026-08-31T12:00:00Z"
                    :scope #{:transfer :compact}}})

(def ^:private base-offer
  {:activity/id "act-002"
   :manufacturer "acme-inert-systems"
   :model "glove-1000"
   :equipment-class :inert-powder-handling-and-pressing
   :condition "new"
   :seller "acme direct"
   :source-url "https://acme-inert-systems.example/products/glove-1000"
   :observed-at "2026-08-31T12:00:00Z"})

;; ── plan-powder-transfer-and-pressing ──────────────────────────────────────

(deftest valid-plan-is-approved-as-simulate-only
  (let [r (cih/plan-powder-transfer-and-pressing base-req)]
    (is (= :approved (:decision r)))
    (is (= :simulate-plan-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (:audit/bot-commanded-equipment (:audit r))))))

(deftest missing-activity-id-refuses
  (is (= :refused (:decision (cih/plan-powder-transfer-and-pressing
                              (dissoc base-req :activity/id))))))

(deftest unknown-powder-family-refuses
  (is (= :refused (:decision (cih/plan-powder-transfer-and-pressing
                              (assoc-in base-req [:powder :family] "TiH2"))))))

(deftest missing-powder-lot-refuses
  (is (= :refused (:decision (cih/plan-powder-transfer-and-pressing
                              (assoc-in base-req [:powder :lot-id] nil))))))

(deftest unmeasured-oxygen-refuses-without-substituting-a-constant
  (let [r (cih/plan-powder-transfer-and-pressing (dissoc base-req :measured-o2-ppm))]
    (is (= :refused (:decision r)))
    (is (re-find #"unmeasured" (:audit/refusal (:audit r))))))

(deftest oxygen-over-limit-refuses
  (is (= :refused (:decision (cih/plan-powder-transfer-and-pressing
                              (assoc base-req :measured-o2-ppm 200 :o2-limit-ppm 100))))))

(deftest oxygen-at-limit-is-accepted
  (is (= :approved (:decision (cih/plan-powder-transfer-and-pressing
                               (assoc base-req :measured-o2-ppm 100))))))

(deftest missing-inert-gas-refuses
  (is (= :refused (:decision (cih/plan-powder-transfer-and-pressing
                              (dissoc base-req :inert-gas))))))

(deftest unmeasured-press-force-refuses-without-inventing-capacity
  (is (= :refused (:decision (cih/plan-powder-transfer-and-pressing
                              (dissoc base-req :measured-press-force-kn))))))

(deftest incomplete-interlocks-refuse
  (is (= :refused (:decision (cih/plan-powder-transfer-and-pressing
                              (assoc base-req :interlocks #{:oxygen-monitor}))))))

(deftest water-suppression-refuses-outright
  (let [r (cih/plan-powder-transfer-and-pressing
           (assoc base-req :suppression-agent "water sprinkler"))]
    (is (= :refused (:decision r)))
    (is (re-find #"water" (:audit/refusal (:audit r))))))

(deftest machine-command-refused-unconditionally
  (let [r (cih/plan-powder-transfer-and-pressing
           (assoc base-req :requested-effect :command-machine))]
    (is (= :refused (:decision r)))
    (is (re-find #"no-physical-command" (:audit/refusal (:audit r))))))

(deftest missing-human-approval-defers-never-approves
  (is (= :refused (:decision (cih/plan-powder-transfer-and-pressing
                              (dissoc base-req :human-approval))))))

(deftest partial-approval-scope-refuses
  (is (= :refused (:decision (cih/plan-powder-transfer-and-pressing
                              (assoc-in base-req [:human-approval :scope] #{:transfer}))))))

;; ── designate-cartridge ────────────────────────────────────────────────────

(deftest valid-designation-is-approved
  (let [r (cih/designate-cartridge
           {:activity/id "act-003" :cartridge-class :cartridge
            :powder-lot-id "lot-2026-08-31-A" :cartridge-serial "car-0001"})]
    (is (= :approved (:decision r)))
    (is (= :record-designation (get-in r [:effect :effect/kind])))
    (is (= "lot-2026-08-31-A" (get-in r [:effect :effect/designation :powder-lot-id])))))

(deftest unrecognized-cartridge-class-refuses
  (is (= :refused (:decision (cih/designate-cartridge
                              {:activity/id "act-004" :cartridge-class :tank
                               :powder-lot-id "lot-1"})))))

(deftest designation-without-lot-linkage-refuses
  (is (= :refused (:decision (cih/designate-cartridge
                              {:activity/id "act-005" :cartridge-class :cartridge})))))

;; ── screen-equipment-offer ─────────────────────────────────────────────────

(deftest offer-screening-is-always-deferred-not-approved
  (let [r (cih/screen-equipment-offer base-offer)]
    (is (= :deferred (:decision r)))
    (is (= :deferred-human-approval (get-in r [:effect :effect/kind])))
    (is (false? (:audit/bot-commanded-equipment (:audit r))))))

(deftest offer-with-unknown-condition-is-distinguished-not-refused
  (is (= :deferred (:decision (cih/screen-equipment-offer
                               (assoc base-offer :condition "unknown"))))))

(deftest offer-with-indistinguishable-condition-refuses
  (is (= :refused (:decision (cih/screen-equipment-offer
                              (assoc base-offer :condition "barely used, like new"))))))

(deftest offer-without-source-url-refuses
  (is (= :refused (:decision (cih/screen-equipment-offer
                              (dissoc base-offer :source-url))))))

(deftest missing-price-is-recorded-unmeasured-never-invented
  (let [r (cih/screen-equipment-offer base-offer)]
    (is (contains? (set (get-in r [:effect :effect/screening :unmeasured-fields]))
                   :price))))


;; ── plan-cartridge-to-reactor-dispatch ─────────────────────────────────────

(def ^:private base-dispatch
  {:activity/id "act-006"
   :cartridge-serial "car-0001"
   :cartridge-class :reactor-feed
   :reactor-serial "RX-2026-0007"
   :leak-test-record-id "lt-car-0001"
   :interlocks #{:reactor-depressurized-verified :h2-detector-armed
                 :ignition-sources-cleared :inert-sequence-verified}
   :human-approval {:approver-did "did:web:plant-supervisor.etzhayyim.com"
                    :approved-at "2026-08-31T12:05:00Z"
                    :scope #{:couple-cartridge-to-reactor}}
   :requested-effect :simulate-dispatch})

(deftest valid-dispatch-is-approved-as-simulate-only
  (let [r (cih/plan-cartridge-to-reactor-dispatch base-dispatch)]
    (is (= :approved (:decision r)))
    (is (= :simulate-dispatch-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (= "RX-2026-0007" (get-in r [:effect :effect/dispatch :reactor-serial])))
    (is (= :reactor-feed (get-in r [:effect :effect/dispatch :cartridge-class])))
    (is (false? (:audit/bot-commanded-equipment (:audit r))))
    (is (contains? (set (:audit/gates-checked (:audit r))) :reactor-genealogy-recorded))))

(deftest dispatch-missing-activity-id-refuses
  (is (= :refused (:decision (cih/plan-cartridge-to-reactor-dispatch
                              (dissoc base-dispatch :activity/id))))))

(deftest dispatch-missing-cartridge-serial-refuses
  (is (= :refused (:decision (cih/plan-cartridge-to-reactor-dispatch
                              (dissoc base-dispatch :cartridge-serial))))))

(deftest dispatch-non-reactor-feed-class-refuses
  (let [r (cih/plan-cartridge-to-reactor-dispatch
           (assoc base-dispatch :cartridge-class :storage))]
    (is (= :refused (:decision r)))
    (is (re-find #"reactor-feed" (:audit/refusal (:audit r))))))

(deftest dispatch-missing-reactor-serial-refuses
  (is (= :refused (:decision (cih/plan-cartridge-to-reactor-dispatch
                              (dissoc base-dispatch :reactor-serial))))))

(deftest dispatch-missing-leak-evidence-defers-not-invented
  (let [r (cih/plan-cartridge-to-reactor-dispatch
           (dissoc base-dispatch :leak-test-record-id))]
    (is (= :refused (:decision r)))
    (is (re-find #"unmeasured" (:audit/refusal (:audit r))))))

(deftest dispatch-incomplete-interlocks-refuse
  (is (= :refused (:decision (cih/plan-cartridge-to-reactor-dispatch
                              (assoc base-dispatch :interlocks #{:h2-detector-armed}))))))

(deftest dispatch-machine-command-refused-unconditionally
  (let [r (cih/plan-cartridge-to-reactor-dispatch
           (assoc base-dispatch :requested-effect :command-couple))]
    (is (= :refused (:decision r)))
    (is (re-find #"no-physical-command" (:audit/refusal (:audit r))))))

(deftest dispatch-missing-human-approval-defers-never-approves
  (is (= :refused (:decision (cih/plan-cartridge-to-reactor-dispatch
                              (dissoc base-dispatch :human-approval))))))

(deftest dispatch-wrong-approval-scope-refuses
  (is (= :refused (:decision (cih/plan-cartridge-to-reactor-dispatch
                              (assoc-in base-dispatch [:human-approval :scope]
                                        #{:transfer}))))))
