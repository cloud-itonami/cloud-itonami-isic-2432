(ns nonferrousmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Drives the REAL NonFerrousFoundryOperationActor
  (`nonferrousmfg.operation/build` -> a compiled langgraph-clj
  StateGraph) over the REAL seeded store
  (`nonferrousmfg.store/sample-data!`), through the REAL Non-Ferrous
  Foundry Plant Operations Governor (`nonferrousmfg.governor/check`)
  and the REAL rollout phase gate (`nonferrousmfg.phase/gate`), then
  renders whatever those actually produced. Nothing on the page is
  invented here:

    - every table row is read back out of the store after the run
      (`store/ledger`, `store/all-batches`, `store/all-equipment`,
      `store/all-maintenance`, `store/shipment`,
      `store/safety-concerns`, `store/maintenance-history`,
      `store/shipment-history`),
    - every HARD-hold rule name and every violation detail string is
      the governor's own `:violations` entry off the ledger fact --
      never a literal in this namespace,
    - the phase-gate table is derived from `nonferrousmfg.phase/phases`
      and `nonferrousmfg.governor/allowed-ops`, and the governor
      configuration / ground-truth bound tables are read straight off
      the public vars of `nonferrousmfg.governor` and
      `nonferrousmfg.registry`.

  The ONE exception is `op-gate-contract-rows` below -- a static prose
  description of this actor's fixed op-gate contract, explicitly
  labelled as such where it is defined.

  Subject provenance (the demo may not invent subjects): every batch
  and equipment id driven below is seeded by `store/sample-data!`
  (`batch-001` `batch-002` `batch-003` `furnace-001` `diecast-002`),
  verified against that seed before this file was written. Every
  `mnt-*` / `ship-*` / `concern-*` subject is the draft record that its
  own op registers via `nonferrousmfg.registry`.

  Fields rendered are only fields the domain model actually carries. In
  particular `:approved-by` is NOT rendered on a committed shipment /
  maintenance record: `nonferrousmfg.operation`'s `:request-approval`
  node puts the approver on the record's `:payload`, while
  `store/commit-record!` persists `:value` -- so the approver is shown
  from the run timeline (where it is real), not from the stored record
  (where it does not exist).

  Deterministic: no clock, no randomness, no network, no timestamp in
  the page content. Re-running writes a byte-identical file.

  Run: `clojure -M:dev:render-html [out-file]`
  (default out-file `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [nonferrousmfg.governor :as governor]
            [nonferrousmfg.operation :as op]
            [nonferrousmfg.phase :as phase]
            [nonferrousmfg.registry :as registry]
            [nonferrousmfg.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase phase/default-phase})

(def ^:private scenarios
  "One entry = one coordination request driven through the real actor.
  `:approval`, when present, is the human decision handed back to the
  paused graph (`interrupt-before #{:request-approval}`).

  Order matters: `t04` must precede `t05` (it advances batch-001's own
  `:shipped-weight-kg`), and `t02` must precede `t10` (which is the
  same maintenance window a second time)."
  [{:tid "t01"
    :exercises "Production-batch logging against a verified + registered aluminium-silicon die-cast batch. Governor-clean, and :log-production-batch is the only member of phase 3's :auto set -> auto-commit with no human in the loop."
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:alloy-grade :aluminum-silicon
                      :defect-rate-percent 1.1
                      :last-assessed "2026-07-14"}}}

   {:tid "t02"
    :exercises "Refractory-inspection window against the verified + registered reverberatory furnace. Never auto-eligible at any phase -> escalates; the human plant supervisor approves."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "furnace-001"
                      :maintenance-type :refractory-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-furnace? false}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t03"
    :exercises "Molten-metal hazard concern. Always :coordination/safety-concern, so the governor escalates regardless of confidence; the human plant supervisor approves."
    :request {:op :flag-safety-concern :effect :propose :subject "concern-1"
              :value {:equipment-id "furnace-001" :severity :moderate
                      :description "溶解炉周辺の輻射熱上昇、湯漏れの兆候"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t04"
    :exercises "Outbound casting shipment against a verified + registered batch with real headroom (4000 of 20000 kg already shipped). Escalates; the human shipping approver approves and batch-001's own shipped weight advances."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-1"
              :value {:batch-id "batch-001" :weight-kg 5000.0
                      :destination "buyer-yard-north"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t05"
    :exercises "A governor-clean shipment the human VETOES. Distinct from a HARD hold: the governor cleared it, a person did not. Lands on the ledger as :approval-rejected, basis :approver-rejected."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-2"
              :value {:batch-id "batch-001" :weight-kg 1000.0
                      :destination "buyer-yard-west"}}
    :approval {:status :rejected :by "coord-1"}}

   {:tid "t06"
    :exercises "Shipment against the seeded zinc-aluminium batch that has never been QC-verified and is not on file. HARD hold -- and the approval offered below is never reached, because a HARD hold does not pause for a human."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-3"
              :value {:batch-id "batch-003" :weight-kg 1000.0
                      :destination "buyer-yard-south"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t07"
    :exercises "Shipment whose claimed weight would push batch-002 past its own logged production weight (2800 already shipped of 3000 kg, +1000 requested). The governor recomputes from the batch's own permanent fields rather than believing the claim. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-4"
              :value {:batch-id "batch-002" :weight-kg 1000.0
                      :destination "buyer-yard-east"}}}

   {:tid "t08"
    :exercises "Shipment stating no weight at all. Headroom cannot be recomputed, and un-checkable headroom is not headroom -- the governor refuses rather than falling through as 'not over capacity'. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-5"
              :value {:batch-id "batch-001"
                      :destination "buyer-yard-north"}}}

   {:tid "t09"
    :exercises "Maintenance against the seeded die-casting machine, which is neither inspected/commissioned nor registered. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-2"
              :value {:equipment-id "diecast-002"
                      :maintenance-type :tooling-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-furnace? false}}}

   {:tid "t10"
    :exercises "A maintenance proposal that tries to ACTUATE the melting furnace rather than draft a window. Permanent scope boundary -- it never reaches a human even though an approval is offered here. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-3"
              :value {:equipment-id "furnace-001" :maintenance-type :force-run
                      :scheduled-date "2026-09-01"
                      :actuate-furnace? true}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t11"
    :exercises "The SAME maintenance window as t02, scheduled twice. Guarded off a dedicated :scheduled? fact, never a :status value. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "furnace-001"
                      :maintenance-type :refractory-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-furnace? false}}}

   {:tid "t12"
    :exercises "A batch patch declaring an alloy grade outside the closed non-ferrous casting-alloy set. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:alloy-grade :unobtainium}}}

   {:tid "t13"
    :exercises "A batch patch claiming a defect rate above 100% -- a batch cannot reject more than all of its own output. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:defect-rate-percent 999.0}}}

   {:tid "t14"
    :exercises "A mis-wired caller whose own request :effect is not :propose -- checked before anything else, so it can never reach a commit path. HARD hold."
    :request {:op :log-production-batch :effect :direct-write :subject "batch-001"
              :patch {:alloy-grade :aluminum-silicon}}}

   {:tid "t15"
    :exercises "An op outside the closed four-op allowlist. Both the op allowlist and the proposal-effect allowlist reject it, so the ledger fact carries two rules. HARD hold."
    :request {:op :actuate-melting-furnace :effect :propose :subject "batch-001"}}])

(defn- drive!
  "Runs one scenario through the real compiled graph and returns the
  scenario enriched with what the graph actually did."
  [actor {:keys [tid request approval] :as scenario}]
  (let [r1 (g/run* actor {:request request :context coordinator} {:thread-id tid})
        paused? (= :interrupted (:status r1))
        r2 (when (and approval paused?)
             (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        final (:state (or r2 r1))
        audit (:audit final [])]
    (assoc scenario
           :verdict (:verdict final)
           :paused? paused?
           :escalation (first (filter #(= :approval-requested (:t %)) audit))
           :human (when r2 (:status approval))
           :disposition (:disposition final))))

(defn run-demo!
  "Seeds a fresh MemStore, builds the real actor, drives every scenario
  above through it. Returns {:db store :runs [..]} -- every field the
  renderer reads below is real governor/store output."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    {:db db :runs (mapv #(drive! actor %) scenarios)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fmt
  "Render a stored value, or an em dash when the domain model carries
  no value for that field on that record."
  [v]
  (if (nil? v) "—" (esc v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- flag [v]
  (if (true? v)
    "<span class=\"ok\">true</span>"
    (str "<span class=\"muted\">" (if (nil? v) "—" (esc v)) "</span>")))

(defn- codes
  "Render a SEQUENCE of keywords in the order the code produced it --
  used for `:basis`, whose order is the governor's own evaluation
  order."
  [coll]
  (str/join " " (map code coll)))

(defn- kw-codes
  "Render a SET of keywords. Sorted, because a set has no order and an
  unsorted render would make the output non-deterministic."
  [coll]
  (str/join " " (map code (sort-by str coll))))

(defn- tr [& cells] (str "<tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>\n"
       (str/join "\n" rows)
       "\n</tbody></table>"))

(defn- card [title note body]
  (str "<section class=\"card\"><h2>" (esc title) "</h2>"
       (when note (str "<p class=\"muted\">" note "</p>"))
       body "</section>"))

;; ----------------------------- sections -----------------------------

(defn- ledger-of [db] (vec (store/ledger db)))

(defn holds
  "The `:governor-hold` facts on the append-only ledger -- the evidence
  that the governor actually refused something in this run."
  [db]
  (filterv #(= :governor-hold (:t %)) (ledger-of db)))

(defn- summary-section [db runs]
  (let [led (ledger-of db)
        n (fn [t] (count (filter #(= t (:t %)) led)))]
    (card "Run summary"
          (str "Every number below is a count over the actor's own append-only ledger after "
               "driving " (count runs) " requests through " (code "nonferrousmfg.operation/build")
               ". Nothing here is a usage, revenue or performance claim.")
          (str
           (table ["Measure" "Count"]
                  [(tr "requests driven" (str "<span class=\"num\">" (count runs) "</span>"))
                   (tr "ledger facts" (str "<span class=\"num\">" (count led) "</span>"))
                   (tr "commits" (str "<span class=\"num ok\">" (n :committed) "</span>"))
                   (tr "governor HARD holds"
                       (str "<span class=\"num critical\">" (n :governor-hold) "</span>"))
                   (tr "human rejections"
                       (str "<span class=\"num critical\">" (n :approval-rejected) "</span>"))
                   (tr "human approvals granted"
                       (str "<span class=\"num ok\">"
                            (count (filter #(= :approved (:human %)) runs)) "</span>"))
                   (tr "maintenance drafts on file"
                       (str "<span class=\"num\">" (count (store/maintenance-history db)) "</span>"))
                   (tr "shipment drafts on file"
                       (str "<span class=\"num\">" (count (store/shipment-history db)) "</span>"))
                   (tr "safety concerns flagged"
                       (str "<span class=\"num\">" (count (store/safety-concerns db)) "</span>"))])
           "<p class=\"muted\"><code>:approval-granted</code> is emitted to the graph's in-memory "
           "<code>:audit</code> channel only — <code>nonferrousmfg.operation</code> never appends "
           "it to the store ledger, so it is not a fact this page counts. An approved request is "
           "visible as the <code>:committed</code> fact it produced.</p>"))))

(defn- verdict-cell [{:keys [verdict]}]
  (cond
    (nil? verdict) "<span class=\"muted\">—</span>"
    (:hard? verdict)
    (str "<span class=\"critical\">HARD</span> "
         (str/join " " (map code (map :rule (:violations verdict)))))
    (:escalate? verdict)
    (str "<span class=\"warn\">escalate</span>"
         (when (:high-stakes? verdict) " <span class=\"muted\">high-stakes</span>"))
    :else (str "<span class=\"ok\">clean</span> <span class=\"muted\">conf "
               (esc (:confidence verdict)) "</span>")))

(defn- human-cell [{:keys [approval human paused?]}]
  (cond
    (= :approved human) "<span class=\"ok\">approved</span>"
    (= :rejected human) "<span class=\"critical\">rejected</span>"
    (and approval (not paused?))
    "<span class=\"muted\">never offered (no interrupt)</span>"
    :else "<span class=\"muted\">—</span>"))

(defn- disposition-cell [{:keys [disposition]}]
  (case disposition
    :commit "<span class=\"ok\">commit</span>"
    :hold "<span class=\"critical\">hold</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    (str "<span class=\"muted\">" (fmt disposition) "</span>")))

(defn- timeline-section [runs]
  (card "Request timeline"
        (str "One row = one " (code "langgraph.graph/run*") " over the compiled actor. The "
             "governor column is the verdict map the governor itself returned; the human column "
             "is the decision handed back to the graph while it was paused at "
             (code ":request-approval") ".")
        (table ["Thread" "Op" "Subject" "Governor" "Human" "Final" "What this exercises"]
               (for [{:keys [tid request escalation exercises] :as r} runs]
                 (tr (code tid)
                     (code (:op request))
                     (code (:subject request))
                     (verdict-cell r)
                     (human-cell r)
                     (str (disposition-cell r)
                          (when-let [reason (:reason escalation)]
                            (str " <span class=\"muted\">after escalation "
                                 (code reason) "</span>")))
                     (str "<span class=\"muted\">" (esc exercises) "</span>"))))))

(defn- holds-section [db]
  (let [hs (holds db)]
    (card "Governor HARD holds"
          (str "Each row is one violation inside a " (code ":governor-hold") " fact on the "
               "append-only ledger. The rule name and the detail text are the governor's own "
               (code ":violations") " entries — this page holds no rule text of its own. A HARD "
               "hold is never overridable and never reaches a human.")
          (table ["Rule" "Op" "Subject" "Confidence" "Governor's own detail"]
                 (for [h hs
                       v (:violations h)]
                   (tr (str "<span class=\"critical\">" (esc (:rule v)) "</span>")
                       (code (:op h))
                       (code (:subject h))
                       (fmt (:confidence h))
                       (esc (:detail v))))))))

(defn- rejections-section [db]
  (let [rs (filterv #(= :approval-rejected (:t %)) (ledger-of db))]
    (when (seq rs)
      (card "Human rejections"
            (str "A governor-clean proposal a person declined. Written to the ledger by the same "
                 (code ":hold") " node, but with basis " (code ":approver-rejected") " — not a "
                 "compliance violation.")
            (table ["Op" "Subject" "Basis" "Confidence"]
                   (for [r rs]
                     (tr (code (:op r)) (code (:subject r))
                         (codes (:basis r)) (fmt (:confidence r)))))))))

;; STATIC CONTENT — this is the one hand-written block on the page: a
;; prose description of this actor's FIXED op-gate contract (README
;; `What this actor does` / `What this actor does NOT do`,
;; `nonferrousmfg.governor`, `nonferrousmfg.phase`). It documents
;; behaviour that cannot change between runs, so it is deliberately not
;; derived from run output. Everything else on the page IS derived.
(def ^:private op-gate-contract-rows
  [["<code>:log-production-batch</code>"
    "<span class=\"ok\">may auto-commit at phase 3 when governor-clean</span> · alloy grade and defect rate re-validated against the closed known sets"]
   ["<code>:schedule-maintenance</code>"
    "<span class=\"warn\">ALWAYS human approval · never in any phase's <code>:auto</code> set</span> · equipment verified?/registered? re-derived independently · double-schedule guarded · direct furnace/die-casting/pouring-line actuation permanently blocked"]
   ["<code>:flag-safety-concern</code>"
    "<span class=\"warn\">ALWAYS human approval · <code>:coordination/safety-concern</code> is always high-stakes</span> · never blocked on the referenced equipment being unverified — a hazard may be reported about anything"]
   ["<code>:coordinate-shipment</code>"
    "<span class=\"warn\">ALWAYS human approval · never in any phase's <code>:auto</code> set</span> · batch verified?/registered? re-derived independently · shipped weight recomputed from the batch's own logged production weight, never from the claim"]])

(defn- op-gate-section []
  (card "Op gate contract (fixed)"
        (str "Static description of this actor's closed four-op contract — the only hand-written "
             "content on this page. It states behaviour that is fixed in "
             (code "nonferrousmfg.governor") " and " (code "nonferrousmfg.phase")
             ", not anything measured in this run. The two tables below re-derive the same "
             "posture from those namespaces' own public vars, so the two can be compared.")
        (table ["Op" "Gate"]
               (for [[o gate] op-gate-contract-rows] (tr o gate)))))

(defn- phase-section []
  (let [ph phase/default-phase
        {:keys [label writes auto]} (get phase/phases ph)]
    (card (str "Rollout phase gate — phase " ph " (" label ")")
          (str "Derived from " (code "nonferrousmfg.phase/phases") " and "
               (code "nonferrousmfg.governor/allowed-ops") ". A governor HOLD always stays a HOLD; "
               "an op that may write but is not auto-eligible escalates to a human even when the "
               "governor is clean.")
          (table ["Op" "May write in this phase" "May auto-commit when governor-clean"]
                 (for [o (sort-by str governor/allowed-ops)]
                   (tr (code o)
                       (if (contains? writes o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"critical\">no — HOLD (:phase-disabled)</span>")
                       (if (contains? auto o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"warn\">no — always human approval</span>")))))))

(defn- governor-section []
  (card "Governor configuration"
        (str "Read straight off the public vars of " (code "nonferrousmfg.governor") ".")
        (table ["Setting" "Value"]
               [(tr "confidence floor" (code governor/confidence-floor))
                (tr "allowed ops" (kw-codes governor/allowed-ops))
                (tr "allowed proposal effects" (kw-codes governor/allowed-proposal-effects))
                (tr "always-human stakes" (kw-codes governor/high-stakes))])))

(defn- bounds-section []
  (card "Independent ground-truth bounds"
        (str "The values " (code "nonferrousmfg.registry") " uses to re-derive the truth itself, "
             "rather than believing the advisor's rationale.")
        (table ["Bound" "Value"]
               [(tr "valid alloy grades" (kw-codes registry/valid-alloy-grades))
                (tr "valid output forms" (kw-codes registry/valid-output-forms))
                (tr "defect rate (%)"
                    (str (code registry/defect-rate-min-percent) " … "
                         (code registry/defect-rate-max-percent)))])))

(defn- last-fact-for [led subject]
  (last (filter #(= subject (:subject %)) led)))

(defn- subject-status [led subject]
  (let [f (last-fact-for led subject)]
    (cond
      (nil? f) "<span class=\"muted\">no ledger activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"critical\">rejected by approver</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold</span> " (codes (:basis f)))
      :else (str "<span class=\"muted\">" (esc (:t f)) "</span>"))))

(defn- remaining-kg
  "Headroom left on a batch, computed the same way
  `nonferrousmfg.registry` recomputes it — from the batch's own two
  permanent fields, with the same `0.0` default for a batch that has
  shipped nothing. nil when it is not computable at all, which the
  governor treats as 'not headroom'."
  [b]
  (let [w (:weight-kg b) s (:shipped-weight-kg b 0.0)]
    (when (and (number? w) (number? s)) (- (double w) (double s)))))

(defn- batches-section [db]
  (let [led (ledger-of db)]
    (card "Production batches"
          (str "Read back from " (code "nonferrousmfg.store/all-batches") " after the run. All "
               "three batches are seeded by " (code "store/sample-data!") "; the shipped weights "
               "shown are whatever this run's committed shipments actually advanced them to. "
               (code "ready?") " is " (code "nonferrousmfg.registry/batch-ready?")
               " re-evaluated here, the same predicate the governor uses.")
          (table ["Batch" "Alloy grade" "Output form" "Material" "Production (kg)"
                  "Shipped (kg)" "Remaining (kg)" "Defect rate (%)" "verified?" "registered?"
                  "ready?" "Last assessed" "Ledger status"]
                 (for [b (store/all-batches db)]
                   (tr (code (:id b)) (fmt (:alloy-grade b)) (fmt (:output-form b))
                       (fmt (:material b))
                       (str "<span class=\"num\">" (fmt (:weight-kg b)) "</span>")
                       (str "<span class=\"num\">" (fmt (:shipped-weight-kg b)) "</span>")
                       (str "<span class=\"num\">" (fmt (remaining-kg b)) "</span>")
                       (str "<span class=\"num\">" (fmt (:defect-rate-percent b)) "</span>")
                       (flag (:verified? b)) (flag (:registered? b))
                       (if (registry/batch-ready? b)
                         "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
                       (fmt (:last-assessed b))
                       (subject-status led (:id b))))))))

(defn- equipment-section [db]
  (card "Furnace / die-casting equipment"
        (str "Read back from " (code "nonferrousmfg.store/all-equipment") ". Equipment ids are "
             "never a request " (code ":subject") " in this domain (a maintenance draft id is), "
             "so no ledger-status column is shown for them — "
             (code ":last-scheduled-maintenance-date") " is the field the commit path actually "
             "writes onto an equipment record.")
        (table ["Unit" "Kind" "verified?" "registered?" "ready?" "Last maintenance"
                "Last scheduled maintenance" "Maintenance drafts on file"]
               (for [e (store/all-equipment db)]
                 (tr (code (:id e)) (fmt (:kind e))
                     (flag (:verified? e)) (flag (:registered? e))
                     (if (registry/equipment-ready? e)
                       "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
                     (fmt (:last-maintenance-date e))
                     (fmt (:last-scheduled-maintenance-date e))
                     (str "<span class=\"num\">"
                          (count (filter #(= (:id e) (:equipment-id %))
                                         (store/all-maintenance db)))
                          "</span>"))))))

(defn- maintenance-section [db]
  (let [ms (store/all-maintenance db)]
    (card "Maintenance schedule drafts"
          (str "Committed drafts from " (code "nonferrousmfg.store/all-maintenance") ". The "
               "maintenance number is minted by "
               (code "nonferrousmfg.registry/register-maintenance")
               " at commit time. Nothing here actuates the furnace, the die-casting machine or "
               "the pouring line.")
          (if (seq ms)
            (table ["Draft" "Equipment" "Type" "Scheduled date" "actuate-furnace?"
                    "scheduled?" "Maintenance number"]
                   (for [m ms]
                     (tr (code (:id m)) (code (:equipment-id m)) (fmt (:maintenance-type m))
                         (fmt (:scheduled-date m)) (flag (:actuate-furnace? m))
                         (flag (:scheduled? m)) (fmt (:maintenance-number m)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- shipments-section [db]
  (let [hist (store/shipment-history db)
        ships (keep #(store/shipment db (get % "shipment_id")) hist)]
    (card "Shipment coordination drafts"
          (str "Committed drafts, joined from " (code "nonferrousmfg.store/shipment-history")
               " back to each stored shipment record. This is a draft a plant coordinator keeps "
               "— it dispatches no freight carrier.")
          (if (seq ships)
            (table ["Draft" "Batch" "Weight (kg)" "Destination" "Shipment number"]
                   (for [s ships]
                     (tr (code (:id s)) (code (:batch-id s))
                         (str "<span class=\"num\">" (fmt (:weight-kg s)) "</span>")
                         (fmt (:destination s)) (fmt (:shipment-number s)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- concerns-section [db]
  (let [cs (store/safety-concerns db)]
    (card "Safety concerns"
          (str "The append-only safety-concern log ("
               (code "nonferrousmfg.store/safety-concerns")
               "). A concern may be raised against any equipment, verified or not — it is never "
               "blocked on an administrative technicality.")
          (if (seq cs)
            (table ["Concern" "Equipment" "Severity" "Description"]
                   (for [c cs]
                     (tr (code (:id c)) (code (:equipment-id c)) (fmt (:severity c))
                         (fmt (:description c)))))
            "<p class=\"muted\">none flagged in this run</p>"))))

(defn- ledger-section [db]
  (card "Audit ledger (append-only)"
        (str "The full ledger, in append order, exactly as "
             (code "nonferrousmfg.store/ledger") " returns it.")
        (table ["#" "Fact" "Op" "Subject" "Actor" "Disposition" "Basis"]
               (map-indexed
                (fn [i f]
                  (tr (str "<span class=\"num\">" (esc (inc i)) "</span>")
                      (let [cls (case (:t f)
                                  :committed "ok"
                                  :governor-hold "critical"
                                  :approval-rejected "critical"
                                  "muted")]
                        (str "<span class=\"" cls "\">" (esc (:t f)) "</span>"))
                      (code (:op f)) (code (:subject f)) (fmt (:actor f))
                      (fmt (:disposition f)) (codes (:basis f))))
                (ledger-of db)))))

;; ----------------------------- page -----------------------------

(defn render
  "The whole page, from the post-run store and the run log. Every row,
  number and status below comes from `db` / `runs`; the only fixed text
  is `op-gate-contract-rows` (labelled where it is defined)."
  [{:keys [db runs]}]
  (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<meta name=\"color-scheme\" content=\"light\">"
       "<title>Operator console — cloud-itonami-isic-2432 (nonferrousmfg)</title>"
       "<style>" (jp-go-dds.skin/dds+skin) "</style></head>\n<body>\n"
       "<header class=\"bar\">"
       "<span class=\"badge\">ISIC 2432</span>"
       "<span class=\"badge\">nonferrousmfg</span>"
       "<span class=\"muted\">governor "
       (code "nonferrous-foundry-plant-operations-governor")
       " · actor " (esc (:actor-id coordinator))
       " · role " (esc (:actor-role coordinator))
       " · phase " (esc (:phase coordinator))
       "</span></header>\n"
       "<h1>Casting of non-ferrous metals — operator console</h1>"
       "<p class=\"subtitle\">Read-only sample. Generated at build time by driving the real "
       "governed actor; a repair to the melting furnace, the die-casting machine or the pouring "
       "line is never actuated from this page.</p>\n"
       "<main>\n"
       (str/join "\n"
                 (remove nil?
                         [(summary-section db runs)
                          (timeline-section runs)
                          (holds-section db)
                          (rejections-section db)
                          (op-gate-section)
                          (phase-section)
                          (governor-section)
                          (bounds-section)
                          (batches-section db)
                          (equipment-section db)
                          (maintenance-section db)
                          (shipments-section db)
                          (concerns-section db)
                          (ledger-section db)]))
       "\n</main>\n<footer>"
       "Generated at build time by <code>nonferrousmfg.render-html</code> "
       "(<code>clojure -M:dev:render-html</code>) by driving the real "
       "<code>nonferrousmfg.operation</code> actor graph over the real "
       "<code>nonferrousmfg.store</code> seed. Deterministic — no clock, no randomness, no "
       "network. No usage, revenue or performance metric is claimed anywhere on this page."
       "</footer>\n</body>\n</html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)]
    ;; A console that shows no real HARD hold is not evidence of a governor.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count runs) " requests)"))))
