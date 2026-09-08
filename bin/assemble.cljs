(ns assemble
  "nbb dev assembler — NON-AUTHORITATIVE. Production mux is owned by
   ai-gftd-dougaka (ffmpeg assembler, ADR-2605312355); this driver exists so a
   single machine can go storyboard→mp4 without the fleet. It issues exactly
   one ffmpeg invocation and adds no assembly logic of its own.

     nbb --classpath src bin/assemble.cljs <render-out-dir> --out video.mp4 \\
         [--bgm bgm.mp3] [--sfx-dir sfx/]     ; sfx-dir holds <kind>.wav files

   SFX events come from audio-plan.edn (run bin/audio_plan.cljs first).
   NOTE: --bgm must be at least as long as the video — amix uses
   duration=first (the video-derived track), but if the bgm is shorter the
   mixed audio ends early; keep bgm ≥ manifest duration (fps * frame/count)."
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.edn :as edn]
            [kotoba.lang.text :as str]))

(defn- parse-args [argv]
  (loop [args argv opts {}]
    (if (empty? args)
      opts
      (let [[a & more] args]
        (case a
          "--out"     (recur (rest more) (assoc opts :out (first more)))
          "--bgm"     (recur (rest more) (assoc opts :bgm (first more)))
          "--sfx-dir" (recur (rest more) (assoc opts :sfx-dir (first more)))
          (recur more (assoc opts :dir a)))))))

(let [{:keys [dir out bgm sfx-dir]} (parse-args *command-line-args*)]
  (when-not (and dir out)
    (println "usage: nbb --classpath src bin/assemble.cljs <render-out-dir> --out <video.mp4> [--bgm <file>] [--sfx-dir <dir>]")
    (js/process.exit 2))
  (let [manifest (edn/read-string (fs/readFileSync (path/join dir "manifest.edn") "utf8"))
        plan-file (path/join dir "audio-plan.edn")
        plan (when (fs/existsSync plan-file)
               (edn/read-string (fs/readFileSync plan-file "utf8")))
        fps (:fps manifest)
        pattern (path/join dir (:frame/pattern manifest))
        _ (when (str/ends-with? pattern ".svg")
            (println "frames are SVG-only; re-render with --png first")
            (js/process.exit 1))
        sfx (when (and sfx-dir plan)
              (->> (:audio/sfx plan)
                   (keep (fn [{:sfx/keys [t kind]}]
                           (let [f (path/join sfx-dir (str (name kind) ".wav"))]
                             (when (fs/existsSync f) {:t t :file f}))))
                   vec))
        audio-inputs (cond-> [] bgm (conj bgm) (seq sfx) (into (map :file sfx)))
        ;; one input per audio file; sfx delayed to its cue time, all mixed
        filter-str
        (when (seq audio-inputs)
          (let [sfx-labels (map-indexed
                            (fn [i {:keys [t]}]
                              (let [in (+ i (if bgm 2 1))
                                    ms (js/Math.round (* t 1000))]
                                (str "[" in ":a]adelay=" ms "|" ms "[s" i "]")))
                            (or sfx []))
                mix-ins (str (when bgm "[1:a]")
                             (str/join "" (map-indexed (fn [i _] (str "[s" i "]")) (or sfx []))))
                n (+ (if bgm 1 0) (count (or sfx [])))]
            (str (str/join ";" sfx-labels)
                 (when (seq sfx-labels) ";")
                 mix-ins "amix=inputs=" n ":duration=first:normalize=0[aout]")))
        args (-> ["-y" "-loglevel" "error"
                  "-framerate" (str fps) "-i" pattern]
                 (into (mapcat (fn [f] ["-i" f]) audio-inputs))
                 (into (if filter-str
                         ["-filter_complex" filter-str
                          "-map" "0:v" "-map" "[aout]" "-shortest"]
                         []))
                 (into ["-pix_fmt" "yuv420p" out]))
        r (cp/spawnSync "ffmpeg" (clj->js args) #js {:stdio "inherit"})]
    (if (zero? (.-status r))
      (println "assembled →" out (if filter-str "(with audio)" "(video only)"))
      (js/process.exit (.-status r)))))
