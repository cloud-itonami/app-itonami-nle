(ns verify-browser
  "Drive the built NLE in headless Chromium and assert what the JVM tests and
  the compiler cannot.

  This migration swapped 30 raw `<button>` elements for `ui/button`, which
  builds its own attribute map. If `:attrs` did not reach the element, every
  one of them would render and do nothing — the app would look finished and
  be inert. That is the failure this checks for.

  Run (after `shadow-cljs release app` + `nbb scripts/gen-page.cljs`, with
  public/ served):
    NLE_URL=… npx nbb --classpath \"$(clojure -Spath)\" scripts/verify-browser.cljs"
  (:require ["node:process" :as process]
            ["playwright-core$default" :as pw]
            [promesa.core :as p]))

(def url (or (.. process -env -NLE_URL) "http://localhost:8733/"))

(defonce results (atom []))

(defn- check! [label ok? detail]
  (swap! results conj {:label label :ok? (boolean ok?) :detail detail})
  (println (if ok? "  PASS" "  FAIL") label (if ok? "" (str "-- " detail))))

(defn- settle [page] (.waitForTimeout page 250))

(defn- checks [page errors]
  [(fn [] (p/let [t (.textContent (.locator page "h1"))]
            (check! "app mounted" (= "KAMI NLE" (str t)) t)))

   (fn [] (p/let [raw (.count (.locator page "button:not(.shitsuke__button)"))
                  glass (.count (.locator page "button.liquid-glass__button"))]
            (check! "buttons are ui/button, not hand-rolled"
                    (and (zero? raw) (> glass 15)) (str "raw=" raw " glass=" glass))))

   ;; `:disabled (not decoded?)` travels through :attrs. Before shitsuke's
   ;; merge became nil-aware, the component's own `:disabled nil` beat the
   ;; consumer's value — this button would have been clickable with no media
   ;; decoded. Exactly the regression the mechanical transform would have
   ;; introduced, silently, across 66 buttons in the two apps.
   (fn [] (p/let [dis (.isDisabled (.locator page ".nle-primary"))]
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
                  app (.getAttribute (.locator page "html") "data-appearance")]
            (check! "theme accent + appearance are live"
                    (and (= "#38bdf8" (str tint)) (= "dark" (str app)))
                    (str tint " / " app))))

   (fn [] (p/let [label (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--hig-color-label').trim()")
                  mono (.evaluate page "getComputedStyle(document.documentElement).getPropertyValue('--hig-font-mono').trim()")]
            (check! "HIG tokens present (the frozen artifact had none)"
                    (and (seq (str label)) (seq (str mono))) (str label " | " mono))))

   (fn [] (p/resolved (check! "no page errors or console errors"
                              (empty? @errors) (pr-str @errors))))])

(defn -main []
  (p/let [browser (.launch (.-chromium pw) #js {:headless true})
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
          _ (reduce (fn [acc thunk] (p/then acc (fn [_] (thunk))))
                    (p/resolved nil) (checks page errors))
          _ (.close browser)]
    (let [failed (remove :ok? @results)]
      (println)
      (println (str (count (filter :ok? @results)) "/" (count @results) " checks passed"))
      (doseq [f failed] (println " FAILED:" (:label f) "--" (:detail f)))
      (process/exit (if (seq failed) 1 0)))))

(-> (-main) (p/catch (fn [e] (println "harness error:" (str e)) (process/exit 1))))
