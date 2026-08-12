# cloud-itonami-isic-2432: Casting of non-ferrous metals

Open Business Blueprint for **ISIC Rev.5 2432**: casting of non-ferrous metals — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **non-ferrous metal casting foundry plant operations**: production-batch data logging (alloy-grade/weight/defect-rate), melting-furnace/mold/shakeout/die-casting-equipment maintenance scheduling, safety-concern flagging, and outbound non-ferrous casting shipment coordination.

This repository designs a forkable OSS business for non-ferrous
metal casting foundry plant operations: run by a qualified operator so
a foundry keeps its own operating records instead of renting a closed
SaaS.

## Scope: non-ferrous casting foundry, not steelmaking, ferrous casting, or downstream machining

ISIC 2432 covers the **non-ferrous metal casting foundry** that melts
aluminum, copper, zinc, brass, bronze, or magnesium alloys in a
furnace (crucible, reverberatory, or induction), then either pours the
molten metal into sand or permanent molds or injects it under high
pressure into a die-casting machine, then cools and shakes out the
solidified castings — producing automotive, hardware, marine, and
industrial parts, ready to sell or ship, or to pass on to a downstream
machining/finishing operation. This is distinct from
`cloud-itonami-isic-2431` (Casting of iron and steel), the sibling
ferrous casting foundry vertical that melts and pours at a much higher
temperature and never runs high-pressure die-casting equipment, and
from any downstream machining/finishing actor that would consume this
foundry's castings as raw parts. This actor's own hazard profile is
centered on non-ferrous molten-metal handling: splash/burn risk at the
furnace and pour, furnace radiant-heat exposure, mold/core-binder fume
exposure, non-ferrous metal-fume exposure (zinc/brass/bronze vapor —
"metal fume fever"), high-pressure die-casting clamping/injection
hazards, and shakeout dust/noise.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — alloy-grade (aluminum/copper/zinc/brass/bronze/magnesium)/weight/defect-rate data logging (administrative, not an operational decision)
- `:schedule-maintenance` — furnace/mold/shakeout/die-casting-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a molten-metal-hazard (splash/burn, furnace radiant-heat, mold/core-binder fume exposure, non-ferrous metal-fume exposure, die-casting clamping/injection hazard)/equipment-safety concern (always escalates)
- `:coordinate-shipment` — outbound non-ferrous casting shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(melting furnace, molten-metal splash/burn hazard, furnace radiant-heat
exposure, mold/core-binder fume exposure, non-ferrous metal-fume
exposure, high-pressure die-casting hazard):

- Does NOT control the melting furnace, die-casting machine, or pouring line equipment directly
- Does NOT make plant-safety or molten-metal-safety decisions (that's the plant supervisor's exclusive human authority)
- Does NOT actuate the melting furnace, die-casting machine, or pouring line (human plant supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`nonferrousmfg.operation/build`, a langgraph-clj StateGraph):
1. **`nonferrousmfg.advisor`** (sealed intelligence node, `NonFerrousFoundryAdvisor`): proposes decisions only, never commits
2. **`nonferrousmfg.governor`** (independent, `Non-Ferrous Foundry Plant Operations Governor`): validates against domain rules, re-derived from `nonferrousmfg.registry`'s pure functions and `nonferrousmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Foundry/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct furnace/die-casting-machine/pouring-line-equipment control)
     - Directly actuating the melting furnace, die-casting machine, or pouring line (`:actuate-furnace? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped weight past its own logged production weight (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:alloy-grade` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`nonferrousmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`nonferrousmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Regenerate docs/samples/operator-console.html from a REAL actor run
clojure -M:dev:render-html

# Lint
clojure -M:lint
```

### Operator console (`docs/samples/operator-console.html`)

[`docs/samples/operator-console.html`](docs/samples/operator-console.html) is
**generated at build time by driving the real actor**, not hand-written.
`nonferrousmfg.render-html` seeds a real store, pushes 13 coordination requests
through the real `nonferrousmfg.operation` StateGraph (advisor → governor →
phase gate → commit/hold/approval, resuming the escalated ones as a human
approver would), and renders the page from the resulting store and append-only
ledger. Every number, id, disposition and hold reason on the page is read back
out of that run; even the action-gate table is derived from the live
`governor/allowed-ops` and `phase/phases` values, so it cannot drift from the
code.

The scenario reaches both dispositions on purpose: 4 commits (one phase-3
auto-commit plus three human-approved) and 9 HARD holds covering all ten
governor rules — holds that never reach a human. `-main` **refuses to write the
file** if the ledger contains zero `:governor-hold` facts, so "the console shows
a real hold" is a build-time invariant rather than a convention.

Output is deterministic — the stack is offline and pure, draft record numbers
come from a sequence rather than a clock, and no timestamp reaches the page, so
reruns from the same seed are byte-identical:

```bash
S=$(mktemp -d)
clojure -M:dev:render-html "$S/a.html"
clojure -M:dev:render-html "$S/b.html"
cmp "$S/a.html" "$S/b.html"   # byte-identical
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
