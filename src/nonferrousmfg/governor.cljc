(ns nonferrousmfg.governor
  "Non-Ferrous Foundry Plant Operations Governor -- the independent
  compliance layer that earns the NonFerrousFoundryAdvisor the right
  to commit. The advisor has no notion of whether a piece of equipment
  it wants to schedule maintenance against has actually been
  inspected/registered, whether a batch it wants to coordinate a
  shipment against has actually been QC-verified/registered, whether a
  maintenance proposal secretly tries to ACTUATE (rather than merely
  draft-schedule) the melting furnace, die-casting machine, or pouring
  line, whether a shipment proposal's own claimed weight would blow
  through the batch's own logged production weight, or when an act
  stops being a coordination proposal and becomes direct furnace/
  pouring-line-equipment control, so this MUST be a separate system
  able to *reject* a proposal and fall back to HOLD.

  `:itonami.blueprint/governor` is
  `:nonferrous-foundry-plant-operations-governor` (see
  docs/adr/0001-architecture.md).

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to coordinate?
                                       Anything else -- HARD hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:furnace/actuate`
                                       or `:pouring-line/run`) is the
                                       'direct furnace/pouring-line-
                                       equipment control' scope
                                       violation this actor must NEVER
                                       perform -- HARD, PERMANENT,
                                       unconditional.
    4. Furnace-actuate blocked     -- for `:schedule-maintenance`, does
                                       the proposal's own `:value`
                                       declare `:actuate-furnace?
                                       true`? Directly actuating the
                                       melting furnace, die-casting
                                       machine, or pouring line is this
                                       actor's other permanent scope
                                       boundary (see README `What this
                                       actor does NOT do`) -- HARD,
                                       PERMANENT, unconditional. NO
                                       phase and NO human approval can
                                       ever override this (see
                                       `nonferrousmfg.phase`: this op
                                       is never a member of any phase's
                                       `:auto` set either -- two
                                       independent layers agree).
    5. Equipment not verified/
       registered                  -- for `:schedule-maintenance`,
                                       INDEPENDENTLY verify the
                                       referenced equipment's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`nonferrousmfg.registry/
                                       equipment-ready?`) -- never
                                       trust the advisor's own
                                       rationale about verification/
                                       registration status. Grounded in
                                       this blueprint's own HARD
                                       invariant ('foundry/batch record
                                       must be independently verified/
                                       registered before any action'):
                                       maintenance must never be
                                       scheduled against equipment
                                       whose own conditions have not
                                       actually been inspected or
                                       whose registration is not
                                       actually on file.
    6. Already scheduled           -- for `:schedule-maintenance`,
                                       refuses to schedule the SAME
                                       maintenance record twice, off a
                                       dedicated `:scheduled?` fact
                                       (never a `:status` value).
    7. Batch not verified/
       registered                  -- for `:coordinate-shipment`,
                                       INDEPENDENTLY verify the
                                       referenced batch's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`nonferrousmfg.registry/batch-
                                       ready?`) -- never trust the
                                       advisor's own rationale. Also
                                       part of the 'foundry/batch
                                       record' HARD invariant: a
                                       batch's own verified/registered
                                       status is as much a ground-truth
                                       fact as an equipment unit's own.
    8. Shipment weight exceeded    -- for `:coordinate-shipment`,
                                       INDEPENDENTLY recompute whether
                                       the batch's own recorded
                                       `:shipped-weight-kg` plus
                                       the proposal's own claimed
                                       `:weight-kg` would exceed
                                       the batch's own recorded
                                       `:weight-kg`
                                       (`nonferrousmfg.registry/
                                       shipment-weight-exceeded?`) --
                                       ground truth from the batch's
                                       own permanent fields, never a
                                       self-reported weight claim.
    9. Invalid alloy-grade         -- for `:log-production-batch`, if
                                       the patch declares an
                                       `:alloy-grade` outside the
                                       closed known set
                                       (`nonferrousmfg.registry/alloy-
                                       grade-valid?`), the batch record
                                       is rejected rather than let a
                                       fabricated alloy grade through.
   10. Invalid defect-rate         -- for `:log-production-batch`, if
                                       the patch declares a
                                       `:defect-rate-percent` that is
                                       not a physically plausible
                                       reading
                                       (`nonferrousmfg.registry/defect-
                                       rate-valid?`), the batch record
                                       is rejected rather than let
                                       fabricated/sensor-error data
                                       through.
   11. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake` is
                                       in `high-stakes`
                                       (`:coordination/safety-concern`,
                                       ALWAYS set for `:flag-safety-
                                       concern`) -- escalate to a human
                                       plant supervisor. SOFT: the
                                       human may approve.
   12. Dust-control equipment not
       verified/registered          -- for `:coordinate-dust-control`,
                                       the referenced finishing
                                       equipment must independently
                                       pass `equipment-ready?` (HARD).
   13. Invalid dust-control measure -- for `:coordinate-dust-control`,
                                       the `:control-measure` must be in
                                       the closed known measure set (HARD).
   14. Invented dust measurement    -- for `:coordinate-dust-control`,
                                       any non-nil Kst / MIE / MEC /
                                       collector-capacity value in the
                                       proposal is a fabricated
                                       measurement -- HARD, PERMANENT.
                                       Measurements are recorded as
                                       `:unmeasured`, never invented.
   15. Dust-control already
       proposed                     -- refuses to coordinate the SAME
                                       dust-control proposal twice,
                                       off a dedicated `:proposed?` fact
                                       (HARD)."
  (:require [nonferrousmfg.registry :as registry]
            [nonferrousmfg.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-production-batch :schedule-maintenance
    :flag-safety-concern :coordinate-shipment
    :coordinate-dust-control})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all five are propose-shaped drafts, NEVER a direct furnace/
  pouring-line/dust-collector-equipment-control effect."
  #{:batch/upsert :maintenance/schedule
    :safety-concern/flag :shipment/propose :dust-control/propose})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Safety concerns and combustible-dust coordination are the ops in
  this domain that always demand human eyes regardless of confidence
  -- magnesium finishing dust is combustible and reacts with water, so
  every dust-control proposal is reviewed by a human plant supervisor."
  #{:coordination/safety-concern :coordination/dust-hazard})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- furnace-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct melting-furnace/die-casting-machine/pouring-
  line control, a fabricated actuation effect) is this actor's central
  scope boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :furnace-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は溶解炉・ダイカストマシン・注湯ライン等の直接操作に該当する可能性があり、恒久的に禁止")}]))

(defn- furnace-actuate-blocked-violations
  "HARD, PERMANENT, unconditional: a `:schedule-maintenance` proposal
  whose own `:value` declares `:actuate-furnace? true` is attempting
  to directly actuate the melting furnace, die-casting machine, or
  pouring line -- this actor may only ever propose/schedule a DRAFT
  maintenance window, never actuate equipment directly. No override,
  ever."
  [{:keys [op]} proposal]
  (when (and (= op :schedule-maintenance)
             (true? (:actuate-furnace? (:value proposal))))
    [{:rule :furnace-actuate-blocked
      :detail "溶解炉・ダイカストマシン・注湯ラインの直接操作(actuate)提案は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- equipment-not-verified-violations
  "For `:schedule-maintenance`, INDEPENDENTLY verify the referenced
  equipment exists and is both `:verified?` AND `:registered?` --
  never trust the advisor's own report. This is the HARD invariant
  ('foundry/batch record must be independently verified/registered
  before any action')."
  [{:keys [op]} proposal st]
  (when (= op :schedule-maintenance)
    (let [equipment-id (:equipment-id (:value proposal))
          eq (and equipment-id (store/equipment-unit st equipment-id))]
      (when-not (and eq (registry/equipment-ready? eq))
        [{:rule :equipment-not-verified
          :detail (str equipment-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済み設備記録が無い状態での保守作業予定提案")}]))))

(defn- already-scheduled-violations
  "For `:schedule-maintenance`, refuses to schedule the SAME
  maintenance record twice, off a dedicated `:scheduled?` fact (never
  a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-maintenance)
    (when (store/maintenance-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- batch-not-verified-violations
  "For `:coordinate-shipment`, INDEPENDENTLY verify the referenced
  batch exists and is both `:verified?` AND `:registered?` -- never
  trust the advisor's own report. Also part of the 'foundry/batch
  record must be independently verified/registered before any action'
  HARD invariant."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-shipment)
    (let [batch-id (:batch-id (:value proposal))
          b (and batch-id (store/batch st batch-id))]
      (when-not (and b (registry/batch-ready? b))
        [{:rule :batch-not-verified
          :detail (str batch-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済みバッチ記録が無い状態での出荷調整提案")}]))))

(defn- shipment-weight-exceeded-violations
  "For `:coordinate-shipment`, INDEPENDENTLY recompute whether the
  batch's own recorded shipped-to-date weight plus the proposal's own
  claimed weight would exceed the batch's own recorded `:weight-kg`
  -- ground truth from the batch's own permanent fields, never a
  self-reported weight claim."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-shipment)
    (let [{:keys [batch-id weight-kg]} (:value proposal)
          b (and batch-id (store/batch st batch-id))]
      (cond
        ;; No batch, no recorded capacity, or no stated amount: the headroom
        ;; cannot be computed, so it is not headroom. This used to fall
        ;; through as "not over capacity" and ship.
        (not (registry/shipment-weight-exceeded-checkable? b weight-kg))
        [{:rule :shipment-weight-exceeded
          :detail "生産量/既存出荷実績/申請量のいずれかが数値として確定できない -- 空き容量を検算できないため出荷しない"}]

        (registry/shipment-weight-exceeded? b weight-kg)
        [{:rule :shipment-weight-exceeded
          :detail (str batch-id " の記録済み生産量(" (:weight-kg b)
                       "kg)を、既存出荷実績(" (:shipped-weight-kg b 0.0)
                       "kg)+今回申請(" weight-kg "kg)が超過")}]))))

(defn- invalid-alloy-grade-violations
  "For `:log-production-batch`, if the patch declares an
  `:alloy-grade` outside the closed known set, reject rather than let
  a fabricated alloy grade through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [alloy-grade (:alloy-grade (:value proposal))]
      (when (and (some? alloy-grade) (not (registry/alloy-grade-valid? alloy-grade)))
        [{:rule :invalid-alloy-grade
          :detail (str alloy-grade " は既知の alloy-grade 値ではない")}]))))

(defn- invalid-defect-rate-violations
  "For `:log-production-batch`, if the patch declares a
  `:defect-rate-percent` that is not a physically plausible reading,
  reject rather than let fabricated/sensor-error data through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [rr (:defect-rate-percent (:value proposal))]
      (when (and (some? rr) (not (registry/defect-rate-valid? rr)))
        [{:rule :invalid-defect-rate
          :detail (str rr "% は物理的に妥当な不良率の範囲外")}]))))

(defn- dust-control-equipment-not-verified-violations
  "For `:coordinate-dust-control`, INDEPENDENTLY verify the referenced
  finishing equipment exists and is both `:verified?` AND
  `:registered?` -- never trust the advisor's own report. Same
  'foundry/batch record must be independently verified/registered
  before any action' HARD invariant as maintenance scheduling."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-dust-control)
    (let [equipment-id (:equipment-id (:value proposal))
          eq (and equipment-id (store/equipment-unit st equipment-id))]
      (when-not (and eq (registry/equipment-ready? eq))
        [{:rule :dust-control-equipment-not-verified
          :detail (str equipment-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済み設備記録が無い状態での粉塵対策調整提案")}]))))

(defn- dust-control-invalid-measure-violations
  "For `:coordinate-dust-control`, the proposed control measure must be
  one of the closed known measures -- an unrecognized 'measure' is a
  fabricated control, never let through."
  [{:keys [op]} proposal]
  (when (= op :coordinate-dust-control)
    (let [measure (:control-measure (:value proposal))]
      (when-not (registry/control-measure-valid? measure)
        [{:rule :dust-control-invalid-measure
          :detail (str (pr-str measure) " は既知の可燃性粉塵対策手段ではない")}]))))

(defn- dust-control-invented-measurement-violations
  "HARD: for `:coordinate-dust-control`, the proposal's own `:value`
  must carry NO value for any dust-explosivity measurement key (Kst /
  MIE / MEC / collector capacity). Those numbers come from a qualified
  test lab and calibrated instrumentation -- an advisor proposal that
  'remembers' a Kst is a fabricated measurement, and a fabricated
  dust-explosivity number is precisely the kind of invented value this
  fleet's rules forbid. The proposal names measurements as UNMEASURED
  instead."
  [{:keys [op]} proposal]
  (when (= op :coordinate-dust-control)
    (let [value (:value proposal)]
      (when-not (registry/dust-control-measurement-free? value)
        [{:rule :dust-control-invented-measurement
          :detail "粉塵爆発特性値(Kst/MIE/MEC/集塵機容量)を提案が値として持ってはいけない -- 実測は試験機関の行為。未測定は :unmeasured で名指す"}]))))

(defn- dust-control-already-proposed-violations
  "For `:coordinate-dust-control`, refuses to coordinate the SAME
  dust-control proposal twice, off a dedicated `:proposed?` fact (never
  a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :coordinate-dust-control)
    (when (store/dust-control-already-proposed? st subject)
      [{:rule :dust-control-already-proposed
        :detail (str subject " は既に調整提案済み")}])))

(defn check
  "Censors a NonFerrousFoundryAdvisor proposal against the governor
  rules. Returns {:ok? bool :violations [..] :confidence c :escalate?
  bool :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (furnace-control-blocked-violations proposal)
                           (furnace-actuate-blocked-violations request proposal)
                           (equipment-not-verified-violations request proposal st)
                           (already-scheduled-violations request st)
                           (batch-not-verified-violations request proposal st)
                           (shipment-weight-exceeded-violations request proposal st)
                           (invalid-alloy-grade-violations request proposal)
                           (invalid-defect-rate-violations request proposal)
                           (dust-control-equipment-not-verified-violations request proposal st)
                           (dust-control-invalid-measure-violations request proposal)
                           (dust-control-invented-measurement-violations request proposal)
                           (dust-control-already-proposed-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
