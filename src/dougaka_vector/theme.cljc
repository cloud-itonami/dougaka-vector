(ns dougaka-vector.theme
  "Design tokens for the channel's visual identity. Apps must take colors and
   type from here — no raw hex in scene templates or storyboards (same
   discipline as the kotoba-lang design-system stack; this is a video-frame
   theme, not a DOM UI, so tokens live locally in this repo).")

(def themes
  {:cyber-dark
   {:bg        "#050507"
    :panel     "#0b0f14"
    :panel-border "#1e2a36"
    :grid      "#16202b"
    :ink       "#c9d4e0"          ; primary text
    :ink-dim   "#5b6b7c"          ; secondary text / ticks
    :accent    "#28d7e6"          ; cyan — the "signal" color
    :alert     "#ff4d5e"          ; red — outliers / failure
    :warn      "#f5b942"          ; amber — badges / highlights
    :ok        "#3ddc84"
    :font-mono "JetBrains Mono, IBM Plex Mono, Menlo, monospace"
    :font-size {:xs 18 :sm 24 :md 34 :lg 52 :xl 84}
    :stroke    {:hair 1 :thin 2 :bold 4}
    :radius    10}})

(defn theme [k]
  (or (get themes k)
      (get themes :cyber-dark)))

(defn color
  "Look up a color token; pass through raw non-keyword values so compiled
   scenegraphs stay self-contained."
  [th v]
  (if (keyword? v) (get th v v) v))
