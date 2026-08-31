(ns nonferrousmfg.methods.cartridge-inert-handling
  "cartridge_inert_handling.cljc — cartridge dry-inert-handling cell decision
  contract (:cartridge-dry-inert-handling manufacturing cell,
  scripts/hermes-magnesium-systems-bots/system-scope.edn on com-junkawasaki
  origin/main).

  First executable slice of the cartridge dry-inert-handling cell: a PURE
  decision layer that models activity -> decision -> effect -> audit for
  transferring and compacting reactive magnesium / MgH2 powder under inert gas
  during cartridge integration, plus procurement screening for the cell's
  equipment classes (inert powder handling and pressing). MgH2 synthesis is
  OUTSOURCED in the first-generation boundary; cartridge integration (this
  cell) is retained in house.

  The bot may design and simulate; it may NOT command physical equipment — a
  machine command is refused unconditionally.

  Hazard boundaries encoded (reactive powder / combustible dust / inert-gas
  asphyxiation):
    - powder environment must be declared inert with a MEASURED oxygen level
      below the caller-supplied limit; air-exposed transfer is refused
    - dry dust collection, grounding/bonding, inert-gas asphyxiation warning
      (oxygen monitor), and Class-D extinguisher interlocks must be declared
      before a plan is approved; water-based suppression is refused outright
    - press compaction is a hazardous step: human approval with :transfer and
      :compact scope is required — absence defers, never approves
    - no material, capacity, yield, or pressure constant is invented: measured
      values are required inputs and missing price / lead-time / utility /
      safety / compliance values are recorded as :unmeasured, never filled in

  Pure fns; deterministic; keyword-keyed records; stdlib only (no dependency
  on langgraph/langchain — this layer is the same shape as igata's accepted
  magnesium-HPDC methods contract, PR cloud-itonami/igata#2)."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ── constants ──────────────────────────────────────────────────────────────

(def ^:private required-interlocks
  #{:dry-dust-collection :grounding-bonding :oxygen-monitor :class-d-extinguisher})
(def ^:private recognized-conditions #{"new" "used" "refurbished" "unknown"})
(def ^:private recognized-powder-families #{"Mg" "MgH2"})
(def ^:private recognized-cartridge-classes #{:cartridge :reactor-feed :storage})

;; ── helpers ────────────────────────────────────────────────────────────────

(defn- present? [x]
  (cond (string? x) (not (str/blank? x))
        (nil? x) false
        :else true))

(defn- audit-record
  "The audit tail every decision returns: what was decided, against which
  gates, and the explicit no-physical-command attestation."
  [activity-id decision refusal gates-checked effect]
  {:audit/activity-id activity-id
   :audit/decision decision
   :audit/refusal refusal
   :audit/gates-checked gates-checked
   :audit/effect effect
   :audit/bot-commanded-equipment false})

(defn- refuse [activity-id refusal gates]
  {:decision :refused
   :effect {:effect/kind :none}
   :audit (audit-record activity-id :refused refusal gates {:effect/kind :none})})

;; ── activity 1: powder transfer and pressing plan (hazardous — human
;;    approval required) ─────────────────────────────────────────────────────

(defn plan-powder-transfer-and-pressing
  "One powder transfer + press-compaction activity during cartridge
  integration.

  `req` keys (all measured values must be supplied by the caller; this
  function invents none):
    :activity/id            string
    :powder                 {:family \"Mg\"|\"MgH2\"  :lot-id string}
    :measured-o2-ppm        number — measured in the inert enclosure at the
                            start of this activity, not a design target
    :o2-limit-ppm           number — the caller's process limit; the measured
                            value must be at or below it
    :inert-gas              {:agent string  :measured-flow-lmin number}
    :measured-press-force-kn number — measured setpoint of the compaction press
    :interlocks             collection of interlock keywords (dry dust
                            collection, grounding/bonding, oxygen monitor,
                            Class-D extinguisher)
    :suppression-agent      string — must not contain \"water\"
    :human-approval         {:approver-did string  :approved-at string
                             :scope #{:transfer :compact} — must cover both
                             hazardous steps}
    :requested-effect       :simulate-plan (the only admissible kind) or
                            :command-machine (refused unconditionally)

  Returns {:decision :approved|:refused :effect {...} :audit {...}}."
  [req]
  (let [activity-id (get req :activity/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a powder-transfer activity needs an :activity/id")

          (not (and (map? (get req :powder))
                    (contains? recognized-powder-families
                               (get-in req [:powder :family] ""))
                    (present? (get-in req [:powder :lot-id]))))
          (do (note :powder-family-and-lot)
              (str "powder: family must be one of "
                   (pr-str (sort recognized-powder-families))
                   " with a traced lot-id for MES genealogy; got "
                   (pr-str (get-in req [:powder :family]))))

          (not (number? (get req :measured-o2-ppm)))
          (do (note :measured-o2-required)
              "unmeasured: :measured-o2-ppm is required and must be measured in the inert enclosure; this module never substitutes a design target")

          (not (number? (get req :o2-limit-ppm)))
          (do (note :o2-limit-required)
              "unmeasured: :o2-limit-ppm (the process limit) must be supplied by the caller; this module never invents a limit")

          (> (get req :measured-o2-ppm) (get req :o2-limit-ppm))
          (do (note :o2-below-limit)
              "safety: measured oxygen exceeds the caller's process limit; reactive Mg/MgH2 powder transfer in an over-limit atmosphere is refused (combustible-dust hazard)")

          (not (and (map? (get req :inert-gas))
                    (present? (get-in req [:inert-gas :agent]))
                    (number? (get-in req [:inert-gas :measured-flow-lmin]))))
          (do (note :inert-gas-declared-and-measured)
              "safety: powder transfer requires a declared inert gas with a measured flow; an undeclared atmosphere is refused")

          (not (number? (get req :measured-press-force-kn)))
          (do (note :measured-press-force-required)
              "unmeasured: :measured-press-force-kn (compaction press setpoint) is required and must be a measured value; this module never invents a capacity")

          (not (set/subset? required-interlocks
                            (set (map keyword (get req :interlocks)))))
          (do (note :interlocks-complete)
              (str "safety: interlocks incomplete; required "
                   (pr-str (sort required-interlocks))
                   " got " (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (str/includes? (str/lower-case (str (get req :suppression-agent))) "water")
          (do (note :suppression-not-water-based)
              "safety: water-based suppression in a reactive-powder cell is refused outright (Mg/MgH2 + water is an ignition and hydrogen-evolution hazard)")

          (= :command-machine (get req :requested-effect))
          (do (note :no-physical-command)
              "no-physical-command: the bot may design and simulate but may not command physical equipment; only :simulate-plan is admissible")

          (not (and (map? (get req :human-approval))
                    (present? (get-in req [:human-approval :approver-did]))
                    (present? (get-in req [:human-approval :approved-at]))
                    (set/subset? #{:transfer :compact}
                                 (set (map keyword (get-in req [:human-approval :scope]))))))
          (do (note :human-approval-required)
              "human-approval: powder transfer and press compaction are hazardous operations; a named human approver with :transfer and :compact scope must be recorded — absence defers, never approves")

          :else nil)]
    (if refusal
      (refuse activity-id refusal @gates)
      (let [effect {:effect/kind :simulate-plan-only
                    :effect/machine-command false
                    :effect/plan {:powder (:powder req)
                                  :measured-o2-ppm (:measured-o2-ppm req)
                                  :o2-limit-ppm (:o2-limit-ppm req)
                                  :inert-gas (:inert-gas req)
                                  :measured-press-force-kn (:measured-press-force-kn req)
                                  :interlocks (sort (set (map keyword (:interlocks req))))
                                  :suppression-agent (:suppression-agent req)}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id :approved "" (conj @gates :human-approval-approved :no-physical-command) effect)}))))

;; ── activity 2: cartridge enclosure designation (non-hazardous record) ─────

(defn designate-cartridge
  "Designate an assembled cartridge enclosure class for MES genealogy. This is
  a RECORD activity, not a hazardous one: no approval gate, but the cartridge
  class must be recognized and the lot linkage must be present, so the
  genealogy chain (powder lot -> cartridge) stays intact.

  Returns {:decision :approved|:refused :effect {...} :audit {...}}."
  [req]
  (let [activity-id (get req :activity/id "")
        gates (atom [])
        cartridge-class (some-> req :cartridge-class keyword)]
    (swap! gates conj :cartridge-class-recognized :lot-linkage-present)
    (cond
      (not (present? activity-id))
      (refuse activity-id "activity-id: a cartridge designation needs an :activity/id" @gates)

      (not (contains? recognized-cartridge-classes cartridge-class))
      (refuse activity-id
              (str "cartridge-class: must be one of "
                   (pr-str (sort (map name recognized-cartridge-classes)))
                   "; got " (pr-str cartridge-class))
              @gates)

      (not (present? (get req :powder-lot-id)))
      (refuse activity-id
              "lot-linkage: a designation must reference the powder lot-id it was filled from, or the MES genealogy chain is broken"
              @gates)

      :else
      (let [effect {:effect/kind :record-designation
                    :effect/machine-command false
                    :effect/designation {:cartridge-class cartridge-class
                                         :powder-lot-id (:powder-lot-id req)
                                         :cartridge-serial (get req :cartridge-serial)}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id :approved "" @gates effect)}))))

;; ── activity 3: equipment-offer screening (procurement — always deferred) ──

(defn screen-equipment-offer
  "Screen one equipment offer for the inert-powder-handling-and-pressing
  equipment class. Procurement is a financial commitment: the decision is
  ALWAYS :deferred to a human approver; this fn only assembles the auditable
  evidence record. Condition must be distinguished as \"new\", \"used\",
  \"refurbished\" or \"unknown\". Missing price / lead-time / utility /
  safety / compliance values are recorded as :unmeasured — never invented."
  [offer]
  (let [activity-id (get offer :activity/id "")
        gates (atom [])
        condition (get offer :condition)
        source-url (get offer :source-url)]
    (swap! gates conj :condition-distinguished :source-recorded)
    (cond
      (not (present? activity-id))
      (refuse activity-id "activity-id: an equipment screening needs an :activity/id" @gates)

      (not (contains? recognized-conditions condition))
      (refuse activity-id
              (str "condition: must be distinguished as one of "
                   (pr-str (sort recognized-conditions)) "; got " (pr-str condition))
              @gates)

      (not (present? source-url))
      (refuse activity-id "source: an offer needs a first-party :source-url" @gates)

      :else
      (let [effect {:effect/kind :deferred-human-approval
                    :effect/machine-command false
                    :effect/screening
                    {:manufacturer (get offer :manufacturer)
                     :model (get offer :model)
                     :equipment-class (get offer :equipment-class)
                     :condition condition
                     :seller (get offer :seller)
                     :source-url source-url
                     :observed-at (get offer :observed-at)
                     :unmeasured-fields [:price :currency :lead-time :utility :safety :compliance]}}]
        {:decision :deferred
         :effect effect
         :audit (audit-record activity-id :deferred
                              "procurement is a human decision; screening evidence assembled only"
                              (conj @gates :human-approval-required :no-financial-commitment)
                              effect)}))))
