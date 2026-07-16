(ns dougaka-vector.audio
  "cues.edn → audio-plan.edn. Pure planning only: which SFX kind fires when,
   and what to ask ongakuka (BGM) / murakumo audio (SFX assets) for. Actual
   audio synthesis and the final mux stay outside this repo (ongakuka /
   cloud-murakumo / ai-gftd-dougaka)."
  )

(def default-sfx-rules
  "cue attr → sfx kind. Attrs that represent motion get sound; opacity fades
   are mostly silent (only panel reveals tick)."
  {:h        :tick      ; bar growth
   :value    :tick      ; counter run-up
   :progress :draw      ; line drawing itself
   :x2       :whoosh    ; arrows extending
   :x1       :whoosh})

(def sfx-catalog
  "What each kind means when asking murakumo audio (sfx-mode) to synthesize
   the pack once; assets are then reused across videos."
  {:tick   {:prompt "short dry digital tick, 60ms, no reverb" :gain-db -14}
   :draw   {:prompt "soft rising synth swipe, 400ms" :gain-db -16}
   :whoosh {:prompt "quick airy whoosh, 250ms" :gain-db -12}
   :boom   {:prompt "low sub impact, 500ms" :gain-db -10}})

(defn- coalesce
  "Collapse events closer than window seconds (staggered bars would otherwise
   fire dozens of ticks); keeps the first of each cluster."
  [events window]
  (reduce (fn [acc e]
            (if (and (peek acc)
                     (= (:sfx/kind e) (:sfx/kind (peek acc)))
                     (< (- (:sfx/t e) (:sfx/t (peek acc))) window))
              acc
              (conj acc e)))
          []
          (sort-by :sfx/t events)))

(defn plan
  "manifest + cues → audio plan.
   opts: {:rules {...} :window 0.25 :max-sfx 24 :bgm {...overrides}}"
  [{:keys [fps] :as manifest} cues & [{:keys [rules window max-sfx bgm]}]]
  (let [rules (or rules default-sfx-rules)
        dur (/ (:frame/count manifest) (double fps))
        events (->> cues
                    (keep (fn [{:cue/keys [t attr node]}]
                            (when-let [kind (get rules attr)]
                              {:sfx/t t :sfx/kind kind :sfx/node node})))
                    (#(coalesce % (or window 0.25)))
                    (take (or max-sfx 24))
                    vec)]
    {:audio/version 1
     :video/id (:video/id manifest)
     :audio/duration dur
     :audio/bgm (merge {:engine :ongakuka
                        :request {:dur dur
                                  :mood "dark minimal electronic, sparse, tense"
                                  :bpm 96
                                  :loudness-lufs -18}}
                       bgm)
     :audio/sfx events
     :audio/sfx-catalog (select-keys sfx-catalog (set (map :sfx/kind events)))}))
