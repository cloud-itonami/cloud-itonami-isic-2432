(ns nonferrousmfg.magnesium-traceability
  "MES traceability decision contract for the MAGNESIUM foundry cell.

  This is the magnesium-specific slice of the ISIC 2432 foundry actor's
  traceability plane. The generic (:log-production-batch /
  :coordinate-shipment) ops in `nonferrousmfg.operation` carry no
  magnesium-specific hazard contract; a magnesium batch that reaches a
  shipment proposal without verified melt/cover-gas/dust-collection
  records is exactly the case this module refuses.

  Pairing: the magnesium-HPDC melt-and-shot decision contract lives in
  `cloud-itonami/igata` (`igata.methods.magnesium-hpdc`). This module
  consumes the records that cell produces; it does not command
  equipment and never substitutes a measured value.

  Pure functions only (activity -> decision -> audit). No I/O, no
  side effects, no LLM, no equipment command path. Every returned
  decision carries an audit tail asserting
  `:audit/bot-commanded-equipment false`.")

(def magnesium-alloys
  "Magnesium alloy grades this foundry cell is allowed to log. Anything
  else is outside the magnesium slice and is assessed by the generic
  foundry path instead (returns :not-magnesium-scope)."
  #{:az91d :am50 :am60 :az63 :zk60 :mg9alzn1})

(def required-traceability-fields
  "Fields a magnesium production batch MUST carry to be traceable under
  the MES traceability cell. Each value must be a MEASURED record;
  the caller supplies it, this module never fills it in."
  [:lot-id
   :heat-id
   :alloy-grade
   :melt-temp-c-record        ; measured melt temperature, not a setpoint
   :cover-gas-record          ; declared agent + measured flow
   :dust-collection-ok?       ; dry collection interlock verified this batch
   :machine-guard-ok?         ; shot-sleeve / machine guard verified
   :defect-rate-recorded?])

(def hazard-interlock-fields
  "Interlocks whose failure quarantines the batch regardless of data
  completeness. These mirror the igata melt-and-shot interlocks so the
  two cells agree on the same hazard vocabulary (dry dust collection,
  Class-D extinguisher present, no water contact, machine guard)."
  #{:dust-collection-ok? :machine-guard-ok? :no-water-contact-ok? :class-d-present?})

(defn- present-not-unmeasured? [v]
  (and (some? v)
       (not= :unmeasured v)
       (not= :unknown v)))

(defn- audit-tail [op subject]
  {:audit/op op
   :audit/subject subject
   :audit/bot-commanded-equipment false
   :audit/values-invented false})

(defn assess-batch
  "Activity -> decision: assess one magnesium production batch record
  for MES traceability and hazard-interlock compliance.

  Returns a map:
    :verdict   :traceable | :quarantine | :not-magnesium-scope
    :gaps      vector of missing/unmeasured field keywords (traceability)
    :interlock-failures vector of failed/absent interlock keywords
    :audit     audit tail

  Semantics:
    - non-magnesium alloy (or absent :alloy-grade) -> :not-magnesium-scope
    - ANY failed hazard interlock -> :quarantine (overridable only by a
      human, never by re-running this function)
    - missing/unmeasured traceability fields -> :quarantine with :gaps
    - otherwise :traceable
    - nothing is invented: a missing field is reported as a gap, never
      defaulted."
  [batch]
  (let [subject (:lot-id batch (:id batch :batch))
        grade (:alloy-grade batch)
        scope? (contains? magnesium-alloys grade)
        interlocks (vec (sort
                          (filter #(not (true? (get batch %)))
                                  hazard-interlock-fields)))
        gaps (vec (sort
                    (for [f required-traceability-fields
                          :when (not (present-not-unmeasured? (get batch f)))]
                      f)))
        verdict (cond
                  (not scope?) :not-magnesium-scope
                  (seq interlocks) :quarantine
                  (seq gaps) :quarantine
                  :else :traceable)]
    {:verdict verdict
     :gaps (if (= :not-magnesium-scope verdict) [] gaps)
     :interlock-failures (if (= :not-magnesium-scope verdict) [] interlocks)
     :audit (assoc (audit-tail :assess-batch subject)
                   :audit/verdict verdict
                   :audit/alloy-grade grade)}))

(defn shipment-decision
  "Decision -> effect (proposal, never an effect by itself): decide
  whether a shipment request for an assessed batch may go out.

  Inputs:
    assessment -- output of `assess-batch`
    shipment   -- {:destination ...} plus, when releasing a quarantine,
                  the human approval record {:approved :human-approval
                  {:by \"...\" :scope #{:quarantine-release :shipment}}}

  Returns:
    {:decision :release-proposal  -- traceable batch; still requires the
                                   ordinary human shipment approval
                                   workflow (operation actor escalate)
     :decision :quarantine-release-proposal -- interlock failures
                                   RESOLVED (re-assessed :traceable) and
                                   a scoped human approval is attached
     :decision :refuse            -- quarantine active, or approval
                                   missing / out of scope / not human
    :audit -- audit tail}

  A human approval is real only when it names an approver (:by) and
  carries the exact scope #{:quarantine-release :shipment}. The bot
  itself never releases a quarantine and never commands equipment."
  [assessment shipment]
  (let [subject (get-in shipment [:shipment-id] (get-in assessment [:audit :audit/subject]))
        approval (:human-approval shipment)
        approved? (and (map? approval)
                       (some? (:by approval))
                       (contains? (set (:scope approval)) :quarantine-release)
                       (contains? (set (:scope approval)) :shipment))
        decision
        (case (:verdict assessment)
          :not-magnesium-scope :refuse
          :traceable :release-proposal
          :quarantine (if approved?
                        :quarantine-release-proposal
                        :refuse)
          :refuse)]
    {:decision decision
     :audit (assoc (audit-tail :shipment-decision subject)
                   :audit/decision decision
                   :audit/requires-human-shipment-approval true
                   :audit/human-approver (when approved? (:by approval)))}))
