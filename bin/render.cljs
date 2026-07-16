(ns render
  "nbb render CLI: storyboard EDN → per-frame SVG (+ optional PNG via
   @resvg/resvg-js when installed) + manifest.edn/cues.edn for the
   ai-gftd-dougaka ffmpeg assembler.

     nbb --classpath src bin/render.cljs examples/quantization.edn \\
         --out /tmp/dougaka-vector/quantization --locale en [--png]

   Frames are numbered globally across scenes (%06d) so ffmpeg can consume
   the directory directly:
     ffmpeg -framerate <fps> -i frames/%06d.png -pix_fmt yuv420p out.mp4"
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [dougaka-vector.spec :as spec]
            [dougaka-vector.scene :as scene]
            [dougaka-vector.svg :as svg]
            [dougaka-vector.theme :as theme]
            [dougaka-vector.timeline :as timeline]))

(defn- parse-args [argv]
  (loop [args argv opts {:locale :en :png? false}]
    (if (empty? args)
      opts
      (let [[a & more] args]
        (case a
          "--out"    (recur (rest more) (assoc opts :out (first more)))
          "--locale" (recur (rest more) (assoc opts :locale (keyword (first more))))
          "--fps"    (recur (rest more) (assoc opts :fps (js/parseFloat (first more))))
          "--png"    (recur more (assoc opts :png? true))
          (recur more (assoc opts :storyboard a)))))))

(defn- load-resvg []
  (try (js/require "@resvg/resvg-js")
       (catch :default _ nil)))

(defn- pad6 [n] (.padStart (str n) 6 "0"))

(defn -main [& argv]
  (let [{:keys [storyboard out locale fps png?]} (parse-args argv)]
    (when-not (and storyboard out)
      (println "usage: nbb --classpath src bin/render.cljs <storyboard.edn> --out <dir> [--locale en] [--fps 30] [--png]")
      (js/process.exit 2))
    (let [sb (edn/read-string (fs/readFileSync storyboard "utf8"))
          {:keys [ok? errors]} (spec/validate-storyboard sb)]
      (when-not ok?
        (println "storyboard invalid:")
        (doseq [e errors] (println "  -" e))
        (js/process.exit 1))
      (let [fps (or fps (:video/fps sb))
            compiled (scene/compile-storyboard sb locale)
            th (theme/theme (:theme compiled))
            size (:size compiled)
            frames-dir (path/join out "frames")
            resvg (when png? (load-resvg))
            _ (when (and png? (not resvg))
                (println "WARN: --png requested but @resvg/resvg-js is not installed;")
                (println "      emitting SVG only. npm install @resvg/resvg-js to rasterize."))
            _ (fs/mkdirSync frames-dir #js {:recursive true})
            result
            (reduce
             (fn [{:keys [frame cues scenes]} {:scene/keys [id] :keys [sg]}]
               (let [times (timeline/frame-times sg fps)
                     scene-t0 (/ frame (double fps))]
                 (doseq [[i t] (map-indexed vector times)]
                   (let [doc (svg/frame->svg th size (timeline/sample sg t))
                         base (path/join frames-dir (pad6 (+ frame i)))]
                     (fs/writeFileSync (str base ".svg") doc)
                     (when resvg
                       (let [r (new (.-Resvg resvg) doc)]
                         (fs/writeFileSync (str base ".png") (.asPng (.render r)))))))
                 {:frame (+ frame (count times))
                  :cues (into cues (map #(update % :cue/t + scene-t0))
                              (timeline/track-events sg))
                  :scenes (conj scenes {:scene/id id
                                        :frame/from frame
                                        :frame/to (dec (+ frame (count times)))})}))
             {:frame 0 :cues [] :scenes []}
             (:scenes compiled))]
        (fs/writeFileSync (path/join out "manifest.edn")
                          (pr-str {:video/id (:video/id compiled)
                                   :fps fps
                                   :size size
                                   :locale locale
                                   :frame/count (:frame result)
                                   :frame/pattern (if resvg "frames/%06d.png" "frames/%06d.svg")
                                   :scenes (:scenes result)}))
        (fs/writeFileSync (path/join out "cues.edn") (pr-str (:cues result)))
        (println "rendered" (:frame result) "frames →" frames-dir
                 (str "(" (if resvg "svg+png" "svg only") ", locale " (name locale) ")"))))))

(apply -main *command-line-args*)
