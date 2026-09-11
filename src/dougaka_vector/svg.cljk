(ns dougaka-vector.svg
  "Vector primitives → SVG string. Pure function of (sampled nodes, theme,
   size); no DOM, no browser — frames are rasterized offline (resvg) and
   assembled by ai-gftd-dougaka (ffmpeg). 2D vector output only; 3D is out of
   scope for this repo (kami-engine owns 3D repo-wide)."
  (:require [kotoba.lang.text :as str]
            [dougaka-vector.theme :as theme]))

(defn escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fmt-num [v]
  (if (number? v)
    (let [d (double v)]
      (if (== d (Math/floor d))
        (str (long d))
        ;; 3 decimals is plenty for 1080p+ coordinates
        (str (/ (Math/round (* d 1000.0)) 1000.0))))
    (str v)))

(defn attrs->str [m]
  (->> m
       (keep (fn [[k v]]
               (when (some? v)
                 (str (name k) "=\"" (escape (fmt-num v)) "\""))))
       (str/join " ")))

(defmulti node->svg
  "Render one sampled node to an SVG element string."
  (fn [_th node] (:node/kind node)))

(defmethod node->svg :rect [th {:keys [node/attrs]}]
  (let [{:keys [x y w h fill rx opacity stroke stroke-width]} attrs]
    (str "<rect " (attrs->str {:x x :y y :width (max 0 (or w 0)) :height (max 0 (or h 0))
                               :rx rx :fill (theme/color th fill)
                               :stroke (some->> stroke (theme/color th))
                               :stroke-width stroke-width
                               :opacity opacity}) "/>")))

(defmethod node->svg :line [th {:keys [node/attrs]}]
  (let [{:keys [x1 y1 x2 y2 stroke stroke-width opacity dash]} attrs]
    (str "<line " (attrs->str {:x1 x1 :y1 y1 :x2 x2 :y2 y2
                               :stroke (theme/color th (or stroke :ink))
                               :stroke-width (or stroke-width 2)
                               :stroke-dasharray dash
                               :opacity opacity}) "/>")))

(defmethod node->svg :circle [th {:keys [node/attrs]}]
  (let [{:keys [cx cy r fill opacity]} attrs]
    (str "<circle " (attrs->str {:cx cx :cy cy :r (max 0 (or r 0))
                                 :fill (theme/color th fill)
                                 :opacity opacity}) "/>")))

(defmethod node->svg :text [th {:keys [node/attrs]}]
  (let [{:keys [x y text fill size anchor opacity weight spacing]} attrs]
    (str "<text " (attrs->str {:x x :y y
                               :fill (theme/color th (or fill :ink))
                               :font-family (:font-mono th)
                               :font-size (or size (get-in th [:font-size :md]))
                               :font-weight weight
                               :letter-spacing spacing
                               :text-anchor (or anchor "start")
                               :opacity opacity})
         ">" (escape text) "</text>")))

(defmethod node->svg :path [th {:keys [node/attrs]}]
  (let [{:keys [d fill stroke stroke-width opacity]} attrs]
    (str "<path " (attrs->str {:d d
                               :fill (if fill (theme/color th fill) "none")
                               :stroke (some->> stroke (theme/color th))
                               :stroke-width stroke-width
                               :opacity opacity}) "/>")))

(defn- partial-points
  "First `progress` (0..1) portion of a polyline's points, interpolating the
   last visible segment so the line 'draws' smoothly. Points: [[x y] ...]."
  [pts progress]
  (let [pts (vec pts)
        n (count pts)
        p (max 0.0 (min 1.0 (double (or progress 1.0))))]
    (cond
      (< n 2) pts
      (>= p 1.0) pts
      :else
      (let [total (dec n)
            fpos (* p total)
            i (long (Math/floor fpos))
            u (- fpos i)]
        (if (>= i total)
          pts
          (let [[x0 y0] (nth pts i)
                [x1 y1] (nth pts (inc i))]
            (conj (subvec pts 0 (inc i))
                  [(+ x0 (* (- x1 x0) u)) (+ y0 (* (- y1 y0) u))])))))))

(defmethod node->svg :polyline [th {:keys [node/attrs]}]
  (let [{:keys [points progress stroke stroke-width opacity]} attrs
        pts (partial-points points progress)]
    (if (< (count pts) 2)
      ""
      (str "<polyline " (attrs->str {:points (str/join " " (map (fn [[x y]] (str (fmt-num x) "," (fmt-num y))) pts))
                                     :fill "none"
                                     :stroke (theme/color th (or stroke :accent))
                                     :stroke-width (or stroke-width 3)
                                     :stroke-linejoin "round"
                                     :stroke-linecap "round"
                                     :opacity opacity}) "/>"))))

(defmethod node->svg :counter [th {:keys [node/attrs]}]
  (let [{:keys [x y value decimals prefix suffix fill size anchor opacity weight]} attrs
        d (long (or decimals 0))
        v (double (or value 0))
        shown #?(:clj (format (str "%." d "f") v)
                 :cljs (.toFixed v d))]
    (node->svg th {:node/kind :text
                   :node/attrs {:x x :y y :text (str prefix shown suffix)
                                :fill fill :size size :anchor anchor
                                :opacity opacity :weight weight}})))

(defmethod node->svg :default [_th node]
  (str "<!-- unknown node kind " (escape (:node/kind node)) " -->"))

(defn frame->svg
  "Sampled nodes → complete SVG document string of size [w h]."
  [th [w h] nodes]
  (str "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" w "\" height=\"" h
       "\" viewBox=\"0 0 " w " " h "\">"
       "<rect x=\"0\" y=\"0\" width=\"" w "\" height=\"" h "\" fill=\"" (:bg th) "\"/>"
       (apply str (map #(node->svg th %) nodes))
       "</svg>"))
