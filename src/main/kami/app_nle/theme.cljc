(ns kami.app-nle.theme
  "KAMI NLE's theme and its stylesheet, on the kotoba-lang design system
  (skill `kotoba-uiux`, ADR-2607122200).

  ── what this replaces ──────────────────────────────────────────────────────
  The app shipped two stylesheets of its own:

  * `public/style.css` — 28 hand-written rules carrying 21 hex literals, its
    own font stack, its own `color-scheme: dark`, its own button styling and
    its own focus ring. All decisions the design system had already made, and
    made better: the tokens carry a light appearance and are contrast-checked.
  * `public/liquid-glass.css` — a 39 KB artifact generated from
    `liquid-glass-ui` and then frozen into the repo, byte-identical to the one
    in kami-app-daw. It carried the glass material and **zero `--hig-*`
    tokens**, so the app had the look of the design system and none of its
    semantics, and could not follow the library forward.

  Both are gone; `stylesheet` composes the live bundle from the pinned
  `kotoba-ui`.

  ── what remains app-specific ───────────────────────────────────────────────
  An editor's timeline and program monitor are genuinely its own: the fixed
  track-name column, the frame grid, tick-positioned clips with trim handles,
  a 16:9 monitor. Those rules stay — with token colors and type, so they flip
  with the appearance like everything else."
  (:require [kotoba-ui.core :as ui]))

(def theme
  "`#38bdf8` is the sky blue the app already used for its focus ring, its
  eyebrow label, its primary button and its selected-clip ring — four
  hardcoded copies of one intention, now a single accent everything derives
  from.

  `:appearance :dark`: a video editor grades against a dark surround, and
  this one always did. The difference is that it used to be asserted as
  `color-scheme: dark` over a hand-picked palette; now it is one key over the
  contrast-checked HIG set."
  {:accent "#38bdf8"
   :appearance :dark})

(def track-palette
  "Clip colors from the HIG system palette rather than invented per track.

  The sample sequence shipped `#fbbf24`, `#34d399`, `#a78bfa` — an amber, a
  mint and a lavender that appear nowhere else in the workspace and have no
  dark-appearance counterpart. A clip color is content, but it should come
  from somewhere."
  ["var(--hig-palette-yellow)"
   "var(--hig-palette-green)"
   "var(--hig-palette-purple)"
   "var(--hig-palette-cyan)"
   "var(--hig-palette-pink)"
   "var(--hig-palette-orange)"])

(defn track-color
  "Nth clip color, cycling, so a sequence reads as a set rather than as
  unrelated hues."
  [i]
  (nth track-palette (mod i (count track-palette))))

(def app-css
  "The NLE's own CSS — unlayered, so it wins over the library layers without a
  single compound selector (agent-guide rule 3).

  Only the editor surfaces. Buttons, inputs, typography, focus rings and the
  page background come from the design system; what is left is the geometry a
  non-linear editor needs and no token expresses."
  (str
   ;; --- frame -------------------------------------------------------------
   ".nle-main{display:flex;flex-direction:column;min-height:100dvh}\n"
   ".nle-footer{margin-top:auto;padding:var(--hig-spacing-4) var(--hig-spacing-content-margin);"
   "color:var(--hig-color-secondary-label)}\n"
   ".nle-eyebrow{color:var(--hig-color-tint);letter-spacing:.14em}\n"
   ".nle-toolbar h1{margin:0;font-size:var(--hig-text-title3-font-size)}\n"
   ;; The transport's primary action: filled with the theme's own accent
   ;; rather than a second value invented for it.
   ".nle-primary{background:var(--hig-color-tint);color:var(--hig-color-system-background);"
   "font-weight:700}\n"
   ".nle-timecode{font-family:var(--hig-font-mono);letter-spacing:.08em;"
   "margin-left:var(--hig-spacing-3)}\n"
   ".nle-row{display:flex;align-items:center;gap:var(--hig-spacing-3);flex-wrap:wrap}\n"
   ".nle-meta{display:flex;align-items:center;gap:var(--hig-spacing-3);flex-wrap:wrap;"
   "padding:var(--hig-spacing-3) var(--hig-spacing-content-margin);"
   "border-bottom:var(--hig-hairline) solid var(--hig-color-separator)}\n"
   ;; --- workspace: bin + program monitor -----------------------------------
   ;; 240px is the bin column — a layout constant of this app, not a token.
   ;; Bounded, and the bin scrolls inside it. The bin is a long list of
   ;; import/colour/caption fields — laid out as readable label+control pairs
   ;; it runs well past a screen, and an unbounded column pushed the program
   ;; monitor and the whole timeline below the fold. A monitor you have to
   ;; scroll to is not a monitor.
   ".nle-workspace{display:grid;grid-template-columns:240px 1fr;"
   "height:min(62vh,560px);min-height:340px}\n"
   ".nle-bin{overflow:auto;min-height:0;padding:var(--hig-spacing-4);"
   "border-right:var(--hig-hairline) solid var(--hig-color-separator);"
   "background:var(--hig-color-secondary-system-background)}\n"
   ".nle-bin h2{font-size:var(--hig-text-footnote-font-size);"
   "color:var(--hig-color-secondary-label)}\n"
   ;; Label + control read as a pair, not as two runs of text. Without this
   ;; the bin rendered "Input colorMedia metadataOutput color" — the old
   ;; stylesheet had no rule for these either, but its `aside` padding hid it.
   ".nle-bin label,.nle-field{display:flex;flex-direction:column;"
   "gap:var(--hig-spacing-1);margin:var(--hig-spacing-3) 0;"
   "color:var(--hig-color-secondary-label)}\n"
   ".nle-bin label input,.nle-bin label select,.nle-bin label textarea{width:100%}\n"
   ".nle-asset{padding:var(--hig-spacing-3);margin:var(--hig-spacing-1) 0;"
   "background:var(--hig-color-tertiary-system-background);"
   "border-radius:var(--hig-radius-md)}\n"
   ;; The program monitor sits on the darkest surface the appearance has:
   ;; picture is graded against it, so nothing else may tint it.
   ".nle-monitor{display:grid;place-items:center;overflow:auto;min-height:0;padding:var(--hig-spacing-4);"
   "background:var(--hig-color-system-background)}\n"
   ".nle-frame{aspect-ratio:16/9;width:min(70vw,620px);position:relative;"
   "display:grid;place-items:center;"
   "background:var(--hig-color-secondary-system-background)}\n"
   ".nle-frame span,.nle-frame strong{position:absolute;top:var(--hig-spacing-4)}\n"
   ".nle-frame span{left:var(--hig-spacing-4);color:var(--hig-color-tint)}\n"
   ".nle-frame strong{right:var(--hig-spacing-4);font-family:var(--hig-font-mono)}\n"
   ".nle-scene{font-size:clamp(2rem,6vw,5rem);font-weight:900;letter-spacing:-.06em}\n"
   ;; --- timeline ------------------------------------------------------------
   ".nle-timeline{margin:0 var(--hig-spacing-4) var(--hig-spacing-4);"
   "border:var(--hig-hairline) solid var(--hig-color-separator);"
   "border-radius:var(--hig-radius-large);overflow:hidden;"
   "background:var(--hig-color-secondary-system-background)}\n"
   ".nle-scrub{width:100%;display:block}\n"
   ".nle-track{display:grid;grid-template-columns:160px 1fr;min-height:84px;"
   "border-top:var(--hig-hairline) solid var(--hig-color-separator)}\n"
   ".nle-track-name{padding:var(--hig-spacing-4);"
   "background:var(--hig-color-tertiary-system-background);"
   "color:var(--hig-color-secondary-label)}\n"
   ".nle-lane{position:relative;background:repeating-linear-gradient(90deg,"
   "transparent 0,transparent calc(10% - 1px),var(--hig-color-separator) 10%)}\n"
   ".nle-clip{position:absolute;height:58px;top:12px;overflow:hidden;text-align:left;"
   "color:var(--hig-color-system-background);font-weight:700;"
   "border:2px solid transparent;border-radius:var(--hig-radius-md);cursor:pointer;"
   "display:flex;align-items:center}\n"
   ;; Selection is a ring in the accent — the same signal the focus ring uses,
   ;; which is deliberate: both mean "this is the thing you are acting on".
   ".nle-clip.selected{border-color:var(--hig-color-label);"
   "box-shadow:0 0 0 2px var(--hig-color-tint)}\n"
   ".nle-clip-name{padding:0 var(--hig-spacing-4);white-space:nowrap;overflow:hidden;"
   "text-overflow:ellipsis}\n"
   ".nle-trim{position:absolute;top:0;bottom:0;width:12px;cursor:ew-resize;"
   "touch-action:none;z-index:2;background:var(--hig-color-label);opacity:.7}\n"
   ".nle-trim.left{left:0}.nle-trim.right{right:0}\n"
   ;; --- narrow --------------------------------------------------------------
   "@media(max-width:720px){"
   ".nle-workspace{grid-template-columns:1fr}"
   ".nle-bin{display:none}"
   ".nle-track{grid-template-columns:110px 1fr}"
   ".nle-frame{width:92vw}"
   "}\n"))

(defn stylesheet
  "The complete CSS for the NLE page: the design system's bundle for `theme`
  followed by the app's own unlayered rules."
  ([] (stylesheet theme))
  ([t] (str (ui/theme-css t) "\n" app-css)))

(defn hex-free?
  "True when `s` contains no raw hex color."
  [s]
  (nil? (re-find #"#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3}(?:[0-9a-fA-F]{2})?)?\b" (str s))))
