(ns dougaka-vector.timeline
  "Keyframe tracks and sampling. A scenegraph is pure data:

     {:sg/dur    <seconds>
      :sg/nodes  [{:node/id :bar-3 :node/kind :rect :node/attrs {...}} ...]
      :sg/tracks [{:track/node :bar-3
                   :track/attr :height
                   :track/keys [[t0 v0] [t1 v1] ...]   ; t in seconds, ascending
                   :track/ease :out-cubic} ...]}

   `sample` evaluates every track at time t and returns the nodes with
   animated attrs merged in. Deterministic: same (sg, t) → same result."
  (:require [dougaka-vector.ease :as ease]))

(defn- lerp [a b u]
  (if (and (number? a) (number? b))
    (+ a (* (- b a) u))
    ;; non-numeric values (colors, strings, booleans) switch at u >= 1
    (if (>= u 1.0) b a)))

(defn sample-keys
  "Sample a keyframe vector [[t v] ...] at time t with ease fn ef applied
   per segment. Holds first/last value outside the key range."
  [keys ef t]
  (let [keys (vec keys)]
    (cond
      (empty? keys) nil
      (<= t (ffirst keys)) (second (first keys))
      (>= t (first (peek keys))) (second (peek keys))
      :else
      (loop [i 0]
        (let [[t0 v0] (nth keys i)
              [t1 v1] (nth keys (inc i))]
          (if (<= t0 t t1)
            (let [span (- t1 t0)
                  u (if (pos? span) (/ (- t t0) span) 1.0)]
              (lerp v0 v1 (ef u)))
            (recur (inc i))))))))

(defn sample-track [track t]
  (sample-keys (:track/keys track) (ease/ease-fn (:track/ease track)) t))

(defn sample
  "Evaluate scenegraph sg at time t (seconds) → seq of nodes with animated
   attrs merged over the static :node/attrs."
  [sg t]
  (let [by-node (group-by :track/node (:sg/tracks sg))]
    (mapv (fn [node]
            (let [animated (into {}
                                 (map (fn [trk] [(:track/attr trk) (sample-track trk t)]))
                                 (get by-node (:node/id node)))]
              (update node :node/attrs merge animated)))
          (:sg/nodes sg))))

(defn track-events
  "Cue events for SFX/audio alignment: one event per track start.
   → [{:cue/t <sec> :cue/node <id> :cue/attr <attr>} ...] sorted by t."
  [sg]
  (->> (:sg/tracks sg)
       (keep (fn [trk]
               (when-let [[t _] (first (:track/keys trk))]
                 {:cue/t t :cue/node (:track/node trk) :cue/attr (:track/attr trk)})))
       (sort-by :cue/t)
       vec))

(defn frame-times
  "Frame sample times for a scenegraph at fps. Includes t=0, excludes t=dur
   (the next scene owns that instant)."
  [sg fps]
  (let [n (long (Math/ceil (* (:sg/dur sg) fps)))]
    (mapv #(/ % (double fps)) (range n))))
