(ns kami.app-nle.theme-test
  "Locks the property the design-system migration bought: the app states its
  colors and type once, in a theme map, and everything else references tokens."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.text :as str]
            [jp-go-dds.tokens :as dds-tokens]
            [kami.app-nle.theme :as theme]))

(deftest app-css-has-no-raw-values-test
  (testing "no raw hex color in the app stylesheet"
    ;; public/style.css carried 18 of them, plus its own font stack and its
    ;; own dark palette. The accent below is the only hex the app may state.
    (is (theme/hex-free? theme/app-css)
        (str "leaked: " (re-find #"#[0-9a-fA-F]{3,8}" theme/app-css))))
  (testing "no hand-written font stack: type comes from the HIG tokens"
    ;; The monitor timecode is deliberately monospace — but through
    ;; --hig-font-mono, not a stack this app picked. What the old style.css
    ;; had, and what this forbids, is `font-family:Inter,system-ui,sans-serif`.
    (doseq [[_ v] (re-seq #"font-family\s*:\s*([^;}]+)" theme/app-css)]
      (is (str/includes? v "var(--hig-font-") (str "hand-written font stack: " v))))
  (testing "no hardcoded color-scheme: appearance comes from the theme map"
    (is (not (str/includes? theme/app-css "color-scheme"))))
  (testing "every color reference is a token"
    (let [colors (re-seq #"(?:color|background)\s*:\s*([^;}]+)" theme/app-css)]
      (is (seq colors))
      (doseq [[_ v] colors]
        (is (or (str/includes? v "var(--hig-")
                (str/includes? v "transparent"))
            (str "non-token color value: " v))))))

(deftest the-app-no-longer-states-an-accent-test
  (testing "the theme map is gone — DADS ships the palette"
    (is (theme/hex-free? theme/app-css))
    (is (not (str/includes? theme/app-css "38bdf8")))))

(deftest track-colors-come-from-the-system-palette-test
  (testing "clip color is content, but sourced from the shared palette"
    (is (every? #(str/starts-with? % "var(--hig-palette-") theme/track-palette))
    (is (theme/hex-free? (str/join " " theme/track-palette))))
  (testing "and every one of them is bridged onto a DADS primitive"
    ;; A half-mapped palette is worse than none: the mapped members follow
    ;; DADS and the rest fall back to Apple's hues, so one sequence ends up in
    ;; two design languages.
    (doseq [v theme/track-palette]
      (let [token (second (re-find #"var\((--hig-palette-[a-z0-9]+)\)" v))]
        (is (contains? dds-tokens/hig->dads token)
            (str token " is not bridged — it would fall back to a HIG hue")))))
  (testing "it cycles rather than running out"
    (is (= (theme/track-color 0) (theme/track-color (count theme/track-palette))))
    (is (apply distinct? (map theme/track-color (range (count theme/track-palette)))))))

(deftest stylesheet-is-the-live-library-test
  (testing "the bundle is composed from the pinned jp-go-dds, not a frozen copy"
    (let [css (theme/stylesheet "/*dds*/")]
      (is (str/includes? css "/*dds*/"))
      (is (str/includes? css "dds-ext-"))
      (is (str/includes? css "--hig-color-label"))
      (is (str/includes? css (str/trim theme/app-css)))))
  (testing "the bridge is present, so --hig-* resolves onto DADS"
    (is (str/includes? theme/app-css* "--hig-color-tint"))
    (is (str/includes? theme/app-css* "--color-key-900"))))
