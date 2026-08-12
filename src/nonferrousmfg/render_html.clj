(ns nonferrousmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave 6): this repo previously had NO demo page and no generator at
  all. This namespace drives the REAL actor stack
  (`nonferrousmfg.operation` StateGraph -> `nonferrousmfg.advisor` ->
  `nonferrousmfg.governor` -> `nonferrousmfg.phase` ->
  `nonferrousmfg.store`) via `langgraph.graph/run*`, exactly as
  `clojure -M:dev:run` does, and renders the page from the resulting
  real store + append-only ledger.

  NOTHING on the generated page is hand-typed telemetry. Every batch
  weight, equipment flag, draft record number, disposition, hold rule
  and hold detail string is read back out of the store/ledger the run
  actually produced. Even the action-gate table is derived from the
  live `nonferrousmfg.governor/allowed-ops` and
  `nonferrousmfg.phase/phases` values rather than described in prose,
  so it cannot drift away from the code.

  Determinism: the whole stack is pure + offline (mock advisor, MemStore,
  sequence-numbered draft record ids -- `nonferrousmfg.registry` mints
  `MNT-000000`/`SHP-000000` from a counter, never from a clock), so no
  timestamp, random id or wall-clock value reaches the page. Two runs
  from the same seed are byte-identical (verify with
  `clojure -M:dev:render-html a.html && clojure -M:dev:render-html b.html
  && cmp a.html b.html`). This renderer therefore needs no injected
  clock; if a future backend does, pass an epoch-ms constant in from
  the caller rather than reading one here.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [nonferrousmfg.governor :as governor]
            [nonferrousmfg.operation :as op]
            [nonferrousmfg.phase :as phase]
            [nonferrousmfg.store :as store]))

(def ^:private coordinator
  "The same operator context this repo's own `nonferrousmfg.sim` demo
  driver uses -- phase 3 (`supervised-auto`)."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(def ^:private demo-phase (:phase coordinator))

;; ----------------------------- real actor driving -----------------------------

(defn- exec!
  "One coordination request = one real graph run."
  [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve!
  "Resume an interrupted (escalated) run as the human plant supervisor /
  shipping approver would."
  [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store (`nonferrousmfg.store/sample-data!`) through
  a scenario that reaches every disposition this actor can produce, and
  exercises all ten of `nonferrousmfg.governor`'s HARD rules plus its
  SOFT confidence/high-stakes gate.

  Clean / approved paths (four):
    - `:log-production-batch` on the verified, registered `batch-001`
      with a clean patch -- phase 3 has `:log-production-batch` in its
      `:auto` set, so this is the one op that AUTO-COMMITS with no human.
    - `:schedule-maintenance` `mnt-1` against the verified, registered
      `furnace-001` -- governor-clean, but `:schedule-maintenance` is
      deliberately absent from every phase's `:auto` set, so the phase
      gate escalates it (`:phase-approval`) and a human approves.
    - `:flag-safety-concern` `concern-1` -- ALWAYS high-stakes
      (`:coordination/safety-concern`), so the governor itself escalates
      regardless of confidence; a human approves.
    - `:coordinate-shipment` `ship-1` of 5000 kg against `batch-001`
      (20000 kg logged, 4000 kg already shipped -- real headroom);
      escalated by the phase gate, a human approves.

  HARD holds (nine, none of which ever reaches a human):
    - `:not-propose-effect`      -- a mis-wired caller whose own request
                                    `:effect` is not `:propose`.
    - `:unknown-op`              -- an op outside the closed allowlist
                                    (also trips `:furnace-control-blocked`,
                                    since the advisor answers `:noop`).
    - `:equipment-not-verified`  -- maintenance against the UNVERIFIED,
                                    unregistered `diecast-002`.
    - `:batch-not-verified`      -- a shipment against the UNVERIFIED,
                                    unregistered `batch-003`.
    - `:shipment-weight-exceeded`-- 1000 kg against `batch-002`, whose own
                                    record says 3000 kg produced and
                                    2800 kg already shipped; recomputed
                                    from the batch, never from the claim.
    - `:furnace-actuate-blocked` -- a maintenance proposal declaring
                                    `:actuate-furnace? true`. PERMANENT.
    - `:already-scheduled`       -- re-scheduling `mnt-1`, off the
                                    dedicated `:scheduled?` fact.
    - `:invalid-alloy-grade`     -- a fabricated `:unobtainium` grade.
    - `:invalid-defect-rate`     -- a physically impossible 999.0%.

  Returns `{:db <store> :runs [<run record> ..]}`. Every field the
  renderer reads comes out of this -- there are no hand-typed results."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        runs (volatile! [])
        record! (fn [label tid request result approved?]
                  (vswap! runs conj
                          {:label label :thread-id tid :request request
                           :status (:status result)
                           :disposition (get-in result [:state :disposition])
                           :audit (get-in result [:state :audit])
                           :approved? approved?}))
        run! (fn [label tid request]
               (record! label tid request (exec! actor tid request) false))
        run-approve! (fn [label tid request]
                       (exec! actor tid request)
                       (record! label tid request (approve! actor tid) true))]

    ;; --- clean / approved -------------------------------------------------
    (run! "生産バッチ記録 (clean patch -> phase-3 auto-commit)"
          "t1" {:op :log-production-batch :effect :propose :subject "batch-001"
                :patch {:alloy-grade :aluminum-silicon :last-assessed "2026-07-14"}})

    (run-approve! "保守作業予定 furnace-001 (検証済 -> 人間承認)"
                  "t2" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                        :value {:equipment-id "furnace-001"
                                :maintenance-type :refractory-inspection
                                :scheduled-date "2026-08-01" :actuate-furnace? false}})

    (run-approve! "安全懸念報告 (常に high-stakes -> 人間承認)"
                  "t3" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                        :value {:equipment-id "furnace-001" :severity :moderate
                                :description "溶解炉周辺の輻射熱上昇、湯漏れの兆候"}})

    (run-approve! "出荷調整 batch-001 5000kg (空き容量内 -> 人間承認)"
                  "t4" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                        :value {:batch-id "batch-001" :weight-kg 5000.0
                                :destination "buyer-yard-north"}})

    ;; --- HARD holds (never reach a human) ---------------------------------
    (run! "request :effect が :propose でない (配線不正)"
          "t5" {:op :log-production-batch :effect :direct-write :subject "batch-001"
                :patch {:alloy-grade :aluminum-silicon}})

    (run! "許可リスト外の操作"
          "t6" {:op :actuate-melting-furnace :effect :propose :subject "batch-001"})

    (run! "保守作業予定 diecast-002 (未検証・未登録設備)"
          "t7" {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                :value {:equipment-id "diecast-002" :maintenance-type :tooling-inspection
                        :scheduled-date "2026-08-01" :actuate-furnace? false}})

    (run! "出荷調整 batch-003 (未検証・未登録バッチ)"
          "t8" {:op :coordinate-shipment :effect :propose :subject "ship-2"
                :value {:batch-id "batch-003" :weight-kg 1000.0
                        :destination "buyer-yard-south"}})

    (run! "出荷調整 batch-002 1000kg (記録済生産量を超過)"
          "t9" {:op :coordinate-shipment :effect :propose :subject "ship-3"
                :value {:batch-id "batch-002" :weight-kg 1000.0
                        :destination "buyer-yard-east"}})

    (run! "保守作業予定 mnt-3 :actuate-furnace? true (恒久禁止)"
          "t10" {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                 :value {:equipment-id "furnace-001" :maintenance-type :force-run
                         :scheduled-date "2026-09-01" :actuate-furnace? true}})

    (run! "保守作業予定 mnt-1 再提案 (二重スケジュール)"
          "t11" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                 :value {:equipment-id "furnace-001"
                         :maintenance-type :refractory-inspection
                         :scheduled-date "2026-08-01" :actuate-furnace? false}})

    (run! "生産バッチ記録 (捏造された alloy-grade)"
          "t12" {:op :log-production-batch :effect :propose :subject "batch-001"
                 :patch {:alloy-grade :unobtainium}})

    (run! "生産バッチ記録 (物理的に不能な不良率)"
          "t13" {:op :log-production-batch :effect :propose :subject "batch-001"
                 :patch {:defect-rate-percent 999.0}})

    {:db db :runs @runs}))

;; ----------------------------- ledger queries -----------------------------

(defn holds
  "Every `:governor-hold` fact the run actually wrote to the ledger."
  [db]
  (filterv #(= :governor-hold (:t %)) (store/ledger db)))

(defn commits
  "Every `:committed` fact the run actually wrote to the ledger."
  [db]
  (filterv #(= :committed (:t %)) (store/ledger db)))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- yes-no [b]
  (if b "<span class=\"ok\">はい</span>" "<span class=\"critical\">いいえ</span>"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>"
       (str/join (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows) (str (str/join "\n" rows) "\n") "")
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       (if lead (str "    <p class=\"muted\">" lead "</p>\n") "")
       body
       "  </section>\n"))

(defn- last-fact-for
  "The last ledger fact this subject produced, whatever its kind."
  [ledger subject]
  (last (filter #(= (:subject %) subject) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (case (:t f)
      :committed "<span class=\"ok\">committed</span>"
      :governor-hold (str "<span class=\"critical\">HARD hold · "
                          (esc (kw (-> f :violations first :rule))) "</span>")
      nil "<span class=\"muted\">この run では未操作</span>"
      (str "<span class=\"warn\">" (esc (kw (:t f))) "</span>"))))

;; --- batches / equipment ----------------------------------------------------

(defn- batch-rows [ledger batches]
  (for [{:keys [id alloy-grade output-form material weight-kg shipped-weight-kg
                defect-rate-percent verified? registered?]} batches]
    (row (str "<code>" (esc id) "</code>")
         (esc material)
         (str "<code>" (esc (kw alloy-grade)) "</code>")
         (str "<code>" (esc (kw output-form)) "</code>")
         (esc weight-kg)
         (esc shipped-weight-kg)
         (esc defect-rate-percent)
         (yes-no verified?)
         (yes-no registered?)
         (status-cell ledger id))))

(defn- equipment-rows [ledger equipment]
  (for [{:keys [id kind verified? registered? last-maintenance-date
                last-scheduled-maintenance-date]} equipment]
    (row (str "<code>" (esc id) "</code>")
         (str "<code>" (esc (kw kind)) "</code>")
         (yes-no verified?)
         (yes-no registered?)
         (if last-maintenance-date (esc last-maintenance-date)
             "<span class=\"muted\">記録なし</span>")
         (if last-scheduled-maintenance-date
           (str "<span class=\"ok\">" (esc last-scheduled-maintenance-date) "</span>")
           "<span class=\"muted\">なし</span>")
         (status-cell ledger id))))

;; --- action gate (derived from the live governor/phase values) --------------

(defn- gate-rows
  "Derived from `nonferrousmfg.governor/allowed-ops` and
  `nonferrousmfg.phase/phases` at the demo phase -- not prose. If the
  code's allowlist or auto-set changes, this table changes with it."
  [ledger]
  (let [{:keys [writes auto]} (get phase/phases demo-phase)
        by-op (group-by :op (store/ledger ledger))]
    (for [o (sort-by kw governor/allowed-ops)]
      (let [facts (get by-op o [])
            n-commit (count (filter #(= :committed (:t %)) facts))
            n-hold (count (filter #(= :governor-hold (:t %)) facts))]
        (row (str "<code>:" (esc (kw o)) "</code>")
             (cond
               (not (contains? writes o))
               "<span class=\"critical\">この phase では書き込み不可</span>"
               (contains? auto o)
               "<span class=\"ok\">governor が clean なら自動 commit</span>"
               :else
               "<span class=\"warn\">常に人間の承認が必要 (どの phase でも auto にならない)</span>")
             (str n-commit)
             (str n-hold))))))

;; --- holds ------------------------------------------------------------------

(defn- hold-rows [hs]
  (for [{:keys [op subject violations]} hs
        {:keys [rule detail]} violations]
    (row (str "<code>:" (esc (kw op)) "</code>")
         (str "<code>" (esc subject) "</code>")
         (str "<span class=\"critical\">" (esc (kw rule)) "</span>")
         (esc detail))))

;; --- runs (human-in-the-loop) -----------------------------------------------

(defn- run-rows [runs]
  (for [{:keys [label thread-id request disposition status approved?]} runs]
    (row (str "<code>" (esc thread-id) "</code>")
         (esc label)
         (str "<code>:" (esc (kw (:op request))) "</code>")
         (str "<code>" (esc (:subject request)) "</code>")
         (case disposition
           :commit (if approved?
                     "<span class=\"warn\">escalate → 人間が承認 → commit</span>"
                     "<span class=\"ok\">自動 commit (人間の介在なし)</span>")
           :hold "<span class=\"critical\">HARD hold (人間には届かない)</span>"
           :escalate "<span class=\"warn\">人間の承認待ち</span>"
           (str "<span class=\"muted\">" (esc (kw disposition)) "</span>"))
         (str "<code>" (esc (kw status)) "</code>"))))

;; --- ledger -----------------------------------------------------------------

(defn- ledger-rows [ledger]
  (map-indexed
   (fn [i {:keys [t op subject disposition basis summary]}]
     (row (str (inc i))
          (str "<code>" (esc (kw t)) "</code>")
          (str "<code>:" (esc (kw (or op :n-a))) "</code>")
          (str "<code>" (esc subject) "</code>")
          (esc (kw disposition))
          (esc (or (some->> basis (map kw) (str/join ", ")) ""))
          (esc (or summary ""))))
   ledger))

;; --- draft records ----------------------------------------------------------

(defn- draft-rows [records]
  (for [r records]
    (row (str "<code>" (esc (get r "record_id")) "</code>")
         (str "<code>" (esc (get r "kind")) "</code>")
         (esc (or (get r "maintenance_id") (get r "shipment_id")))
         (esc (or (get r "equipment_id") "—"))
         (yes-no (get r "immutable")))))

(defn- concern-rows [concerns]
  (for [{:keys [id equipment-id severity description approved-by]} concerns]
    (row (str "<code>" (esc id) "</code>")
         (str "<code>" (esc equipment-id) "</code>")
         (str "<span class=\"warn\">" (esc (kw severity)) "</span>")
         (esc description)
         (if approved-by (str "<span class=\"ok\">" (esc approved-by) "</span>")
             "<span class=\"muted\">—</span>"))))

;; --- document ---------------------------------------------------------------

(defn render
  "Renders the whole operator-console document from the `{:db :runs}`
  map `run-demo!` returned. Every value below is read back out of the
  real store/ledger."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        hs (holds db)
        cs (commits db)
        {:keys [label]} (get phase/phases demo-phase)]
    (str
     "<!doctype html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-2432 · 非鉄金属鋳造 プラント運用 Operator Console</title>\n"
     "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style>\n"
     "</head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>非鉄金属鋳造フォンドリー プラント運用 (ISIC 2432) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · 溶解炉/ダイカストマシン/注湯ラインの直接操作は恒久的に禁止</span>\n"
     "</header>\n"
     "<main>\n"

     (section "この run の要約"
              (str "phase " demo-phase " (<code>" (esc label) "</code>) の実 actor 実行 "
                   (count runs) " 件から生成。commit " (count cs) " 件、HARD hold "
                   (count hs) " 件、台帳 " (count ledger) " fact。"
                   "ページ上の数値・ID・判定はすべて実行結果の読み出しで、手書きの値は 1 つも無い。")
              (table ["指標" "値"]
                     [(row "実行した調整リクエスト" (count runs))
                      (row "台帳 fact 総数" (count ledger))
                      (row "commit された操作" (str "<span class=\"ok\">" (count cs) "</span>"))
                      (row "HARD hold (人間に届かない)"
                           (str "<span class=\"critical\">" (count hs) "</span>"))
                      (row "起票された保守作業予定ドラフト"
                           (count (store/maintenance-history db)))
                      (row "起票された出荷調整ドラフト"
                           (count (store/shipment-history db)))
                      (row "記録された安全懸念" (count (store/safety-concerns db)))]))

     (section "生産バッチ (production batches)"
              (str "SSoT の実データ。<code>出荷済</code> は "
                   "<code>:shipment/propose</code> が commit されるたびにバッチ自身の"
                   "累積出荷量へ実際に加算された結果で、提案の自己申告ではない。")
              (table ["バッチ" "材料" "合金" "鋳造形態" "生産量(kg)" "出荷済(kg)"
                      "不良率(%)" "検証済" "登録済" "この run の最終状態"]
                     (batch-rows ledger (store/all-batches db))))

     (section "設備 (equipment)"
              (str "保守作業予定は、設備自身の <code>:verified?</code> と "
                   "<code>:registered?</code> を governor が独立に再確認して初めて通る。"
                   "advisor の説明は根拠として一切使われない。")
              (table ["設備" "種別" "検証済" "登録済" "前回保守" "今回予定された保守日"
                      "この run の最終状態"]
                     (equipment-rows ledger (store/all-equipment db))))

     (section "Action gate (Non-Ferrous Foundry Plant Operations Governor)"
              (str "この表は散文ではなく <code>nonferrousmfg.governor/allowed-ops</code> と "
                   "<code>nonferrousmfg.phase/phases</code> の実値から生成している。"
                   "許可リストや auto 集合をコード側で変えれば、この表も一緒に変わる。")
              (table ["操作" (str "phase " demo-phase " のゲート")
                      "この run の commit 数" "この run の hold 数"]
                     (gate-rows db)))

     (section "HARD hold (この run で実際に発火したもの)"
              (str "HARD hold は上書きできず、人間の承認画面にも到達しない。"
                   "下の理由文字列は governor が生成したものをそのまま出している。")
              (table ["操作" "対象" "違反ルール" "governor の判定理由"]
                     (hold-rows hs)))

     (section "リクエストごとの処理経路"
              (str "<code>langgraph.graph/run*</code> の実行結果。"
                   "<code>interrupt-before #{:request-approval}</code> により、"
                   "escalate したリクエストは実際に一時停止し、"
                   "人間が <code>:approved</code> で resume して初めて commit する。")
              (table ["thread" "シナリオ" "操作" "対象" "処理経路" "graph status"]
                     (run-rows runs)))

     (section "監査台帳 (append-only)"
              "この run が生成した全 decision fact。追記のみで、更新も削除も無い。"
              (table ["#" "fact" "操作" "対象" "判定" "根拠 (basis)" "要約"]
                     (ledger-rows ledger)))

     (section "起票されたドラフト記録"
              (str "この actor が作るのは記録のドラフトだけで、"
                   "溶解炉やダイカストマシンの実操作でも、実際の運送手配でもない。"
                   "記録番号は時計ではなく連番から採番されるため、再実行しても同じになる。")
              (table ["記録番号" "種別" "対象 ID" "設備 ID" "immutable"]
                     (concat (draft-rows (store/maintenance-history db))
                             (draft-rows (store/shipment-history db)))))

     (section "安全懸念 (safety concerns)"
              (str "安全懸念は常に <code>:coordination/safety-concern</code> の high-stakes "
                   "扱いで、confidence がいくら高くても自動 commit されない。"
                   "承認者名は実際の承認 resume から来ている。")
              (table ["ID" "設備" "深刻度" "内容" "承認者"]
                     (concern-rows (store/safety-concerns db))))

     "</main>\n"
     "<footer>\n"
     "  <p class=\"muted\">Generated at build time by <code>nonferrousmfg.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) from a real "
     "<code>nonferrousmfg.operation</code> actor run. Deterministic: no timestamps, "
     "no random ids — reruns from the same seed are byte-identical.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)]
    ;; A console that shows no real HARD hold is not evidence of a
    ;; governor -- it is a screenshot of a happy path. Refuse to write
    ;; one, so the requirement is a build-time invariant and not a
    ;; convention someone can quietly drop.
    (when (empty? hs)
      (throw (ex-info (str "no :governor-hold fact on the ledger — refusing to write a "
                           "console that shows no real hold")
                      {:ledger-facts (count (store/ledger db))
                       :runs (count runs)})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count (commits db)) " commits, "
                  (count runs) " requests)"))))
