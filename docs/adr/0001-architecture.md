# ADR-0001: NonFerrousFoundryAdvisor ⊣ Non-Ferrous Foundry Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2432` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2432` publishes an OSS blueprint for non-ferrous
metal casting foundry **plant operations coordination**
(production-batch alloy-grade/weight/defect-rate data logging,
melting-furnace/mold/shakeout/die-casting-equipment maintenance
scheduling, safety-concern flagging, and outbound non-ferrous casting
shipment coordination). Like every actor in this fleet, the blueprint
alone is not an implementation: this ADR records the governed-actor
architecture that promotes it to real, tested code, following the same
langgraph StateGraph + independent Governor + Phase 0->3 rollout
pattern established across the cloud-itonami fleet.

The closest architectural analog is `cloud-itonami-isic-2431` (Casting
of iron and steel): both are back-office coordination actors for a
fixed processing PLANT with heavy manufacturing equipment and a real
physical safety dimension, and both share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`) and the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination). The two verticals are, however, distinct
plants with distinct hazard and process profiles: 2431 melts and pours
iron/steel at high temperature via cupola/electric-arc/induction
furnaces into sand/permanent molds only, while 2432 melts lower-
melting-point non-ferrous alloys (aluminum, copper, zinc, brass,
bronze, magnesium) via crucible/reverberatory/induction furnaces and
routes a large share of production through high-pressure DIE-CASTING
machines — production equipment and a clamping/injection hazard 2431's
ferrous foundry does not have. 2432's non-ferrous metal-fume exposure
(zinc/brass/bronze vapor — "metal fume fever") is also a distinct
occupational-health profile from 2431's cast-iron/cast-steel
particulate and radiant-heat profile. This build mirrors 2431's
architecture closely but adapts the hazard profile, equipment
vocabulary, and output-form set to the non-ferrous casting plant:
2432's permanent equipment-actuation block guards a melting FURNACE
**or die-casting machine** (`:actuate-furnace?`, kept as the same
field name as 2431 to preserve the governor-rule shape, documented to
cover both); 2432's production-batch record declares an
`:alloy-grade` spanning aluminum/copper/zinc/magnesium alloy families
(per ISIC 2432's own combined non-ferrous scope) rather than 2431's
cast-iron/cast-steel families; and 2432's `valid-output-forms` set
adds `:die-cast` (absent from 2431's set) alongside the shared
`:sand-cast`/`:permanent-mold-cast`/`:investment-cast`/
`:centrifugal-cast` forms.

This vertical has NO pre-existing `kotoba-lang/nonferrousmfg`-style
capability library to wrap (verified: `gh api search/repositories -f
q=nonferrousmfg` returns zero results). This build therefore uses
self-contained domain logic — pure functions in `nonferrousmfg.registry`
(equipment/batch verification, shipment-weight recompute, alloy-grade
validation, defect-rate plausibility validation) are re-verified
independently by the governor, the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-2431`'s `foundrymfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:nonferrous-foundry-plant-operations-governor`, is grep-verified
UNIQUE fleet-wide (`gh api "search/code?q=nonferrous-foundry-plant-
operations-governor"` returns `"total_count":0`, zero hits before this
repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external non-ferrous-casting-foundry capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
non-ferrous metal casting foundry vertical has NO pre-existing
capability library to wrap. The equipment/batch-verification /
shipment-weight / alloy-grade / defect-rate validation functions live
as pure functions in `nonferrousmfg.registry` and are re-verified
independently by `nonferrousmfg.governor` — the same "ground truth,
not self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2431`'s `foundrymfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of non-ferrous
metal casting foundry plant operations. It does NOT:
- Control the melting furnace, die-casting machine, or pouring line equipment directly
- Make plant-safety or molten-metal-safety decisions (exclusive to the human plant supervisor)
- Actuate the melting furnace, die-casting machine, or pouring line

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority —
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: non-ferrous metal casting foundry
manufacturing is a safety-critical domain (molten-metal splash/burn
risk, furnace radiant-heat exposure, mold/core-binder fume exposure,
non-ferrous metal-fume exposure, high-pressure die-casting clamping/
injection hazard, heavy material handling). Safety-concern flagging
NEVER auto-commits. All safety concerns escalate immediately to human
review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (molten-metal-hazard concern, splash/burn risk,
furnace radiant-heat exposure, mold/core-binder fume exposure,
non-ferrous metal-fume exposure, die-casting clamping/injection
hazard, equipment-safety concern, crew fatigue) ALWAYS escalates,
never auto-commits. This is not a "low-stakes proposal" — it is a
circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2431`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "foundry/batch record must be independently
verified/registered before any action" HARD invariant applied to the
two distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date weight plus the proposal's own
claimed weight would exceed the batch's own recorded production
weight — never taken on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into ten concrete checks in
`nonferrousmfg.governor`, mirroring `cloud-itonami-isic-2431`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Foundry/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's weight must independently recompute within the batch's own logged production weight
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct melting-furnace/die-casting-machine/pouring-line-equipment control or furnace actuation is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Non-ferrous metal casting foundry plant operations back-office now
has a documented, governed, auditable coordination layer that funnels
all decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into ten concrete governor checks) protect against scope creep into
unauthorized equipment operation or furnace/die-casting-machine
actuation. Safety concerns are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and furnace/die-casting-machine
operation remain human-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch) — this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-2432`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-weight-exceeded, furnace-actuate-
  blocked, already-scheduled, invalid-alloy-grade, invalid-defect-
  rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
