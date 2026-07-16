(ns dougaka-vector.scene
  "Storyboard → scenegraph compiler. Deterministic and LLM-free: the LLM's
   job ends at the storyboard EDN; everything below this line is a pure
   function, so re-rendering the same storyboard yields the same video.

   Language independence: scenes reference copy-ids, never literal strings.
   `compile-storyboard` resolves copy-ids for one locale at compile time —
   swapping :locale re-renders the identical animation with different text
   (and text is deliberately sparse; the vectors carry the story)."
  (:require [dougaka-vector.theme :as theme]))

(defn resolve-copy
  "copy-id → string for locale, falling back to :en. nil copy-id → nil."
  [copy locale cid]
  (when cid
    (let [entry (get copy cid)]
      (or (get entry locale) (get entry :en)))))

(defmulti compile-scene
  "(video-ctx, scene) → scenegraph {:sg/dur :sg/nodes :sg/tracks}"
  (fn [_ctx scene] (:scene/template scene)))

;; ---------------------------------------------------------------------------
;; :title-card — full-frame statement, center-anchored, accent underline.

(defmethod compile-scene :title-card
  [{:keys [size text]} {:scene/keys [dur] :as _scene}]
  (let [[w h] size
        cx (/ w 2)
        cy (/ h 2)]
    {:sg/dur dur
     :sg/nodes
     (into [{:node/id :title :node/kind :text
             :node/attrs {:x cx :y cy :text (or (:title text) "") :anchor "middle"
                          :size :xl-size :fill :ink :opacity 0}}
            {:node/id :underline :node/kind :line
             :node/attrs {:x1 cx :x2 cx :y1 (+ cy 48) :y2 (+ cy 48)
                          :stroke :accent :stroke-width 4}}]
           (when (:sub text)
             [{:node/id :sub :node/kind :text
               :node/attrs {:x cx :y (+ cy 110) :text (:sub text) :anchor "middle"
                            :size :sm-size :fill :ink-dim :opacity 0}}]))
     :sg/tracks
     (into [{:track/node :title :track/attr :opacity
             :track/keys [[0.0 0.0] [0.5 1.0]] :track/ease :out-cubic}
            {:track/node :title :track/attr :y
             :track/keys [[0.0 (+ cy 24)] [0.6 cy]] :track/ease :out-cubic}
            {:track/node :underline :track/attr :x1
             :track/keys [[0.3 cx] [0.9 (- cx 220)]] :track/ease :out-expo}
            {:track/node :underline :track/attr :x2
             :track/keys [[0.3 cx] [0.9 (+ cx 220)]] :track/ease :out-expo}]
           (when (:sub text)
             [{:track/node :sub :track/attr :opacity
               :track/keys [[0.7 0.0] [1.1 1.0]] :track/ease :out-cubic}]))}))

;; ---------------------------------------------------------------------------
;; :bar-chart — the workhorse: framed panel, staggered bars growing from a
;; baseline, optional highlight bars, badge, and an annotation arrow.

(defn- bar-parts
  "Per-bar {:nodes [...] :tracks [...]}: rects growing up from the baseline,
   staggered left→right."
  [{:keys [values highlight panel-x baseline bar-w gap max-h t0]}]
  (map-indexed
   (fn [i v]
     (let [x (+ panel-x (* i (+ bar-w gap)))
           hgt (* max-h v)
           color (get highlight i :warn)
           ts (+ t0 (* i 0.05))
           bar-id (keyword (str "bar-" i))]
       {:nodes [{:node/id bar-id :node/kind :rect
                 :node/attrs {:x x :y baseline :w bar-w :h 0 :rx 2 :fill color}}]
        :tracks [{:track/node bar-id :track/attr :h
                  :track/keys [[ts 0] [(+ ts 0.5) hgt]] :track/ease :out-cubic}
                 {:track/node bar-id :track/attr :y
                  :track/keys [[ts baseline] [(+ ts 0.5) (- baseline hgt)]] :track/ease :out-cubic}]}))
   values))

(defmethod compile-scene :bar-chart
  [{:keys [size text]} {:scene/keys [dur args] :as _scene}]
  (let [[w h] size
        {:keys [values highlight annotation axis-labels]} args
        n (count values)
        panel-w (* w 0.62)
        panel-h (* h 0.42)
        panel-x (/ (- w panel-w) 2)
        panel-y (/ (- h panel-h) 2)
        pad 48
        plot-x (+ panel-x pad)
        plot-w (- panel-w (* 2 pad))
        baseline (+ panel-y panel-h (- pad))
        max-h (- panel-h (* 2.4 pad))
        gap 10
        bar-w (max 4 (- (/ plot-w (max n 1)) gap))
        parts (bar-parts {:values values :highlight highlight
                          :panel-x plot-x :baseline baseline
                          :bar-w bar-w :gap gap :max-h max-h :t0 0.5})
        bar-nodes (into [] (mapcat :nodes) parts)
        bar-tracks (into [] (mapcat :tracks) parts)
        bar-x #(+ plot-x (* % (+ bar-w gap)) (/ bar-w 2))
        anno-y (- baseline max-h -10)]
    {:sg/dur dur
     :sg/nodes
     (-> [{:node/id :kicker :node/kind :text
           :node/attrs {:x (* w 0.08) :y (* h 0.12) :text (or (:kicker text) "")
                        :size :xs-size :fill :alert :spacing 4 :opacity 0}}
          {:node/id :panel :node/kind :rect
           :node/attrs {:x panel-x :y panel-y :w panel-w :h panel-h :rx 12
                        :fill :panel :stroke :panel-border :stroke-width 1.5 :opacity 0}}
          {:node/id :panel-title :node/kind :text
           :node/attrs {:x plot-x :y (+ panel-y 44) :text (or (:panel-title text) "")
                        :size :xs-size :fill :accent :spacing 3 :opacity 0}}
          {:node/id :baseline :node/kind :line
           :node/attrs {:x1 plot-x :y1 baseline :x2 (+ plot-x plot-w) :y2 baseline
                        :stroke :ink-dim :stroke-width 1 :opacity 0}}
          {:node/id :caption :node/kind :text
           :node/attrs {:x (/ w 2) :y (+ baseline 34) :text (or (:caption text) "")
                        :size :xs-size :fill :ink-dim :anchor "middle" :spacing 2 :opacity 0}}]
         (into bar-nodes)
         (cond->
          (:badge text)
           (into [{:node/id :badge-box :node/kind :rect
                   :node/attrs {:x (+ panel-x panel-w -170) :y (+ panel-y 22) :w 120 :h 44 :rx 6
                                :fill :panel :stroke :warn :stroke-width 1.5 :opacity 0}}
                  {:node/id :badge :node/kind :text
                   :node/attrs {:x (+ panel-x panel-w -110) :y (+ panel-y 52) :text (:badge text)
                                :size :sm-size :fill :warn :anchor "middle" :opacity 0}}])
           annotation
           (into (let [{:keys [from to]} annotation
                       x1 (bar-x from) x2 (bar-x to)]
                   [{:node/id :anno-line :node/kind :line
                     :node/attrs {:x1 x1 :y1 anno-y :x2 x1 :y2 anno-y
                                  :stroke :alert :stroke-width 2}}
                    {:node/id :anno-head :node/kind :path
                     :node/attrs {:d (str "M " x2 " " anno-y " l -14 -7 l 0 14 z")
                                  :fill :alert :opacity 0}}
                    {:node/id :anno-label :node/kind :text
                     :node/attrs {:x (/ (+ x1 x2) 2) :y (- anno-y 14)
                                  :text (or (:annotation text) "") :anchor "middle"
                                  :size :xs-size :fill :alert :opacity 0}}]))
           axis-labels
           (into [{:node/id :axis-min :node/kind :text
                   :node/attrs {:x plot-x :y (+ baseline 34) :text (first axis-labels)
                                :size :xs-size :fill :ink-dim :opacity 0}}
                  {:node/id :axis-max :node/kind :text
                   :node/attrs {:x (+ plot-x plot-w) :y (+ baseline 34) :text (second axis-labels)
                                :size :xs-size :fill :ink-dim :anchor "end" :opacity 0}}])))
     :sg/tracks
     (-> [{:track/node :kicker :track/attr :opacity
           :track/keys [[0.0 0.0] [0.4 1.0]] :track/ease :out-cubic}
          {:track/node :panel :track/attr :opacity
           :track/keys [[0.1 0.0] [0.5 1.0]] :track/ease :out-cubic}
          {:track/node :panel-title :track/attr :opacity
           :track/keys [[0.3 0.0] [0.7 1.0]] :track/ease :out-cubic}
          {:track/node :baseline :track/attr :opacity
           :track/keys [[0.3 0.0] [0.6 0.8]] :track/ease :out-cubic}
          {:track/node :caption :track/attr :opacity
           :track/keys [[0.6 0.0] [1.0 1.0]] :track/ease :out-cubic}]
         (into bar-tracks)
         (cond->
          (:badge text)
           (into [{:track/node :badge-box :track/attr :opacity
                   :track/keys [[0.8 0.0] [1.2 1.0]] :track/ease :out-cubic}
                  {:track/node :badge :track/attr :opacity
                   :track/keys [[0.8 0.0] [1.2 1.0]] :track/ease :out-cubic}])
           annotation
           (into (let [{:keys [from to]} annotation
                       x2 (bar-x to)
                       t0 (+ 0.6 (* 0.05 (count values)))]
                   [{:track/node :anno-line :track/attr :x2
                     :track/keys [[t0 (bar-x from)] [(+ t0 0.6) (- x2 16)]] :track/ease :out-cubic}
                    {:track/node :anno-head :track/attr :opacity
                     :track/keys [[(+ t0 0.5) 0.0] [(+ t0 0.7) 1.0]] :track/ease :out-cubic}
                    {:track/node :anno-label :track/attr :opacity
                     :track/keys [[(+ t0 0.3) 0.0] [(+ t0 0.7) 1.0]] :track/ease :out-cubic}]))
           axis-labels
           (into [{:track/node :axis-min :track/attr :opacity
                   :track/keys [[0.5 0.0] [0.9 0.7]] :track/ease :out-cubic}
                  {:track/node :axis-max :track/attr :opacity
                   :track/keys [[0.5 0.0] [0.9 0.7]] :track/ease :out-cubic}])))}))

;; ---------------------------------------------------------------------------
;; :callout — a framed panel of short text lines (definitions, takeaways).

(defmethod compile-scene :callout
  [{:keys [size text]} {:scene/keys [dur] :as _scene}]
  (let [[w h] size
        lines (:lines text [])
        panel-w (* w 0.5)
        panel-h (+ 120 (* 64 (count lines)))
        panel-x (/ (- w panel-w) 2)
        panel-y (/ (- h panel-h) 2)]
    {:sg/dur dur
     :sg/nodes
     (into [{:node/id :panel :node/kind :rect
             :node/attrs {:x panel-x :y panel-y :w panel-w :h panel-h :rx 12
                          :fill :panel :stroke :panel-border :stroke-width 1.5 :opacity 0}}
            {:node/id :title :node/kind :text
             :node/attrs {:x (+ panel-x 48) :y (+ panel-y 64) :text (or (:title text) "")
                          :size :sm-size :fill :accent :spacing 3 :opacity 0}}]
           (map-indexed
            (fn [i line]
              {:node/id (keyword (str "line-" i)) :node/kind :text
               :node/attrs {:x (+ panel-x 48) :y (+ panel-y 128 (* 64 i)) :text line
                            :size :sm-size :fill :ink :opacity 0}})
            lines))
     :sg/tracks
     (into [{:track/node :panel :track/attr :opacity
             :track/keys [[0.0 0.0] [0.4 1.0]] :track/ease :out-cubic}
            {:track/node :title :track/attr :opacity
             :track/keys [[0.2 0.0] [0.6 1.0]] :track/ease :out-cubic}]
           (map-indexed
            (fn [i _]
              {:track/node (keyword (str "line-" i)) :track/attr :opacity
               :track/keys [[(+ 0.5 (* i 0.25)) 0.0] [(+ 0.9 (* i 0.25)) 1.0]]
               :track/ease :out-cubic})
            lines))}))

;; ---------------------------------------------------------------------------
;; :custom — inline scenegraph passthrough (escape hatch for one-off scenes).

(defmethod compile-scene :custom
  [_ctx {:scene/keys [dur sg]}]
  (assoc sg :sg/dur (or (:sg/dur sg) dur)))

;; ---------------------------------------------------------------------------

(defn- size-token
  "Resolve :xs-size style size keywords against the theme's :font-size map so
   templates never hard-code pixel sizes."
  [th v]
  (if (keyword? v)
    (get-in th [:font-size (keyword (first (re-seq #"[a-z]+" (name v))))] v)
    v))

(defn- resolve-sizes [th sg]
  (update sg :sg/nodes
          (partial mapv #(update % :node/attrs
                                 (fn [a] (if (contains? a :size)
                                           (update a :size (partial size-token th))
                                           a))))))

(defn compile-storyboard
  "storyboard + locale → {:video/id :fps :size :theme
                          :scenes [{:scene/id :sg} ...]}
   Copy is resolved here; the result contains no copy-ids."
  [{:video/keys [id fps size theme scenes copy] :as _sb} locale]
  (let [th (theme/theme theme)
        ctx {:size size :theme th}]
    {:video/id id
     :fps fps
     :size size
     :theme (or theme :cyber-dark)
     :scenes (mapv (fn [scene]
                     (let [text (into {}
                                      (map (fn [[role cid]]
                                             [role (if (vector? cid)
                                                     (mapv #(resolve-copy copy locale %) cid)
                                                     (resolve-copy copy locale cid))]))
                                      (:scene/copy scene))]
                       {:scene/id (:scene/id scene)
                        :sg (resolve-sizes th (compile-scene (assoc ctx :text text) scene))}))
                   scenes)}))
