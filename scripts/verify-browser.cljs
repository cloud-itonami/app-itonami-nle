(ns verify-browser
  "Drive the built NLE in headless Chromium and assert what the JVM tests and
  the compiler cannot.

  This migration swapped 30 raw `<button>` elements for `ui/button`, which
  builds its own attribute map. If `:attrs` did not reach the element, every
  one of them would render and do nothing — the app would look finished and
  be inert. That is the failure this checks for.

  It also drives `user-test-dashboard.html`, whose failure mode was the
  opposite: that page mounted fine and was **unstyled**, because it linked a
  stylesheet the migration had deleted and then replaced `<head>` wholesale on
  mount. Neither the compiler nor a JVM test can see either of those — the first
  is a 404, the second is a DOM mutation. Both are checked here.

  Run (after `shadow-cljs release app dashboard` + `nbb scripts/gen-page.cljs`,
  with public/ served):
    NLE_URL=… npx nbb --classpath \"$(clojure -Spath)\" scripts/verify-browser.cljs"
  (:require ["node:process" :as process]
            ["playwright-core$default" :as pw]
            [clojure.string :as str]
            [promesa.core :as p]))

(def url (or (.. process -env -NLE_URL) "http://localhost:8736/"))

(def dashboard-url
  (str (if (str/ends-with? url "/") url (str url "/")) "user-test-dashboard.html"))

(def session-fixture
  "One exported session, in the JSON shape `bench/export-json!` writes: two
  passes, one failure, four errors. The dashboard has to agree with those
  numbers — it used to print 0 / 0 / 0 for every session, because it matched
  `:kind` against a keyword and JSON carries a string."
  (js/JSON.stringify
   #js {:schema "kami.user-test/v2"
        :session-id "nle-fixture"
        :actor-id "solo-creator"
        :build "fixture"
        :source "synthetic"
        :events #js [#js {:kind "task-result" :task "Import media"
                          :success? true :errors 0 :duration-ms 1000}
                     #js {:kind "task-result" :task "Make a rough cut"
                          :success? true :errors 1 :duration-ms 2000}
                     #js {:kind "task-result" :task "Export a review"
                          :success? false :errors 3 :duration-ms 3000}]}))

(defonce results (atom []))

(defn- check! [label ok? detail]
  (swap! results conj {:label label :ok? (boolean ok?) :detail detail})
  (println (if ok? "  PASS" "  FAIL") label (if ok? "" (str "-- " detail))))

(defn- settle [page] (.waitForTimeout page 250))

(def launch-opts
  "Playwright's own Chromium is the default. `PW_CHANNEL=chrome` runs the
  installed Google Chrome instead — every assertion below is about the page, not
  about which build of Chromium renders it, and a machine whose ms-playwright
  cache is unavailable should still be able to run this."
  (let [o #js {:headless true}
        channel (str (or (.. process -env -PW_CHANNEL) ""))]
    (when (seq channel) (aset o "channel" channel))
    o))

(defn- run-all
  "Run the thunks in order — each check depends on the DOM the previous one left."
  [thunks]
  (reduce (fn [acc thunk] (p/then acc (fn [_] (thunk)))) (p/resolved nil) thunks))

(defn- checks [page]
  [(fn [] (p/let [t (.textContent (.locator page "h1"))]
            (check! "app mounted" (= "KAMI NLE" (str t)) t)))

   (fn [] (p/let [dads (.count (.locator page "button.dads-button"))
                  other (.count (.locator page "button:not(.dads-button)"))]
            (check! "every button is a DADS button"
                    (and (> dads 15) (zero? other)) (str "dads=" dads " other=" other))))

   ;; `:disabled (not decoded?)` travels through :attrs. Before shitsuke's
   ;; merge became nil-aware, the component's own `:disabled nil` beat the
   ;; consumer's value — this button would have been clickable with no media
   ;; decoded. Exactly the regression the mechanical transform would have
   ;; introduced, silently, across 66 buttons in the two apps.
   (fn [] (p/let [dis (.isDisabled (.first (.locator page "button[data-type='solid-fill']")))]
            (check! "ui/button :attrs {:disabled …} actually disables"
                    dis (str "disabled=" dis))))

   ;; An enabled ui/button must reach the app: select a clip, park the
   ;; playhead inside it, split.
   ;;
   ;; Order matters, and both orders were wrong before this one. split-clip
   ;; only cuts when the frame falls STRICTLY inside the selected clip, and
   ;; selecting a clip moves the playhead to that clip's start — so
   ;; "scrub then select" lands the playhead back at the head, where the
   ;; offset is 0 and the split is a no-op. Twice this read 4 -> 4 as a dead
   ;; button while the app was behaving exactly as written.
   ;; "Wide shot" runs 150 frames from 0; 60 is inside it.
   (fn [] (p/let [before (.count (.locator page ".nle-clip"))
                  _ (.click (.locator page ".nle-clip:has-text('Wide shot')"))
                  _ (settle page)
                  _ (.fill (.locator page ".nle-scrub") "60")
                  _ (settle page)
                  _ (.click (.first (.locator page "button:has-text('Split')")))
                  _ (settle page)
                  after (.count (.locator page ".nle-clip"))]
            (check! "ui/button :on-click reaches the app (split adds a clip)"
                    (> after before) (str before " -> " after))))

   ;; Clip selection is a timeline interaction, not a button — it must still
   ;; work after the class rename.
   (fn [] (p/let [_ (.click (.nth (.locator page ".nle-clip") 1))
                  _ (settle page)
                  n (.count (.locator page ".nle-clip.selected"))]
            (check! "clip selection survives the rename" (= 1 n) n)))

   (fn [] (p/let [tracks (.count (.locator page ".nle-track"))
                  clips (.count (.locator page ".nle-clip"))]
            (check! "timeline renders tracks and clips"
                    (and (>= tracks 3) (>= clips 3)) (str "tracks=" tracks " clips=" clips))))

   (fn [] (p/let [bg (.evaluate page "getComputedStyle(document.querySelector('.nle-clip')).backgroundColor")]
            (check! "clip color resolves from the HIG palette"
                    (and (seq (str bg)) (not= "rgba(0, 0, 0, 0)" (str bg))) bg)))

   (fn [] (p/let [tint (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--hig-color-tint').trim()")
                  key (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--color-key-900').trim()")
                  gap (.evaluate page "getComputedStyle(document.querySelector('.nle-toolbar')).gap")]
            (check! "the --hig-* contract resolves onto DADS primitives"
                    (and (= (str tint) (str key)) (seq (str key)) (re-find #"^\d" (str gap)))
                    (str "tint=" tint " key=" key " gap=" gap))))

   (fn [] (p/let [label (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--hig-color-label').trim()")
                  mono (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--hig-font-mono').trim()")]
            (check! "DADS is the base and the bridge sits on it"
                    (and (seq (str label)) (seq (str mono))) (str label " | " mono))))

   ])

(defn- dashboard-checks
  "The second document of the same app. Everything here is a DADS component or a
  `dds-ext-*` helper, so the page ships no CSS of its own — which makes these
  checks about whether the design system *arrives and survives*."
  [page]
  [(fn [] (p/let [t (.textContent (.locator page "main h1"))]
            (check! "dashboard mounted" (= "KAMI NLE · User-test dashboard" (str t)) t)))

   ;; The page carried `<link href="liquid-glass.css">` to a file the DADS
   ;; migration deleted, and rendered `liquid-glass__*` classes no stylesheet
   ;; defines. A 404 stylesheet leaves a page that looks broken but reports
   ;; nothing; the response listener above turns it into a failure.
   (fn [] (p/let [n (.count (.locator page "[class*='liquid-glass']"))
                  links (.count (.locator page "link[href*='liquid-glass']"))]
            (check! "nothing left of the liquid-glass stack"
                    (and (zero? n) (zero? links)) (str "classes=" n " links=" links))))

   ;; The regression that made this page unstyleable: `init!` assigned
   ;; `head.innerHTML`, and a DADS page carries its stylesheet *inline*, so the
   ;; first thing the app did on mount was delete the design system. Asserting
   ;; the tokens resolve *after* mount is what catches it.
   (fn [] (p/let [styles (.count (.locator page "head style"))
                  tint (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--hig-color-tint').trim()")
                  key (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--color-key-900').trim()")]
            (check! "the design system survives mount"
                    (and (pos? styles) (seq (str key)) (= (str tint) (str key)))
                    (str "styles=" styles " tint=" tint " key=" key))))

   (fn [] (p/let [meta (.count (.locator page "head meta[name='kotoba:app-shell']"))]
            (check! "app-shell contract is in the document" (= 1 meta) meta)))

   ;; It used to be injected by JavaScript, i.e. never shown to a reader without
   ;; JavaScript. `content` rather than `textContent`: browsers do not parse
   ;; <noscript> children when scripting is enabled.
   (fn [] (p/let [html (.evaluate page "document.querySelector('noscript')?.textContent ?? ''")]
            (check! "noscript is served, not injected"
                    (str/includes? (str html) "JavaScript") (str html))))

   (fn [] (p/let [n (.count (.locator page "input[type='file'].dads-input-text__input"))
                  label (.count (.locator page ".dads-form-control-label__label"))]
            (check! "the file control is a DADS form field"
                    (and (= 1 n) (pos? label)) (str "input=" n " label=" label))))

   ;; Import a real artifact and read the tally back out of the rendered table.
   (fn [] (p/let [_ (.setInputFiles (.locator page "input[type='file']")
                                    #js {:name "nle-fixture.json"
                                         :mimeType "application/json"
                                         :buffer (js/Buffer.from session-fixture "utf8")})
                  _ (.waitForSelector page ".dads-table__table tbody tr" #js {:timeout 10000})
                  rows (.count (.locator page ".dads-table__table tbody tr"))
                  cells (.allTextContents (.locator page ".dads-table__table tbody tr td"))
                  head (.textContent (.locator page ".dads-table__table tbody tr th"))]
            (check! "an imported session tallies its task results"
                    (and (= 1 rows) (= ["synthetic" "fixture" "2" "1" "4"] (js->clj cells))
                         (= "nle-fixture" (str head)))
                    (str head " " (pr-str (js->clj cells))))))])

(defn -main []
  (p/let [browser (.launch (.-chromium pw) launch-opts)
          ctx (.newContext browser #js {:viewport #js {:width 1440 :height 900}})
          page (.newPage ctx)
          errors (atom [])
          noise? (fn [t] (re-find #"cdn-cgi" (str t)))
          _ (.on page "pageerror" (fn [e] (swap! errors conj (str e))))
          _ (.on page "console" (fn [m]
                                  (let [loc (some-> (.location m) (aget "url"))]
                                    (when (and (= "error" (.type m))
                                               (not (noise? (.text m)))
                                               (not (noise? loc)))
                                      (swap! errors conj (str (.text m) " @ " loc))))))
          _ (.on page "response" (fn [r] (when (and (>= (.status r) 400) (not (noise? (.url r))))
                                           (swap! errors conj (str (.status r) " " (.url r))))))
          _ (.goto page url #js {:waitUntil "networkidle"})
          _ (.waitForSelector page ".nle-track" #js {:timeout 15000})
          _ (run-all (checks page))
          _ (.goto page dashboard-url #js {:waitUntil "networkidle"})
          _ (.waitForSelector page "main h1" #js {:timeout 15000})
          _ (run-all (dashboard-checks page))
          ;; Last, so it covers both documents — including the 404 the deleted
          ;; liquid-glass.css link produced on the dashboard.
          _ (p/resolved (check! "no page errors, console errors or 4xx responses"
                                (empty? @errors) (pr-str @errors)))
          _ (.close browser)]
    (let [failed (remove :ok? @results)]
      (println)
      (println (str (count (filter :ok? @results)) "/" (count @results) " checks passed"))
      (doseq [f failed] (println " FAILED:" (:label f) "--" (:detail f)))
      (process/exit (if (seq failed) 1 0)))))

(-> (-main) (p/catch (fn [e] (println "harness error:" (str e)) (process/exit 1))))
