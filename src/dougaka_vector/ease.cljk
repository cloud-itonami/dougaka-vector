(ns dougaka-vector.ease
  "Easing functions. Pure, portable: t ∈ [0,1] → [0,1] (out-back may overshoot).
   First-class runtimes: ClojureScript / nbb (repo-wide runtime priority);
   :clj branch kept only for compat suites.")

(defn clamp01 [t] (max 0.0 (min 1.0 (double t))))

(defn linear [t] (clamp01 t))

(defn in-cubic [t]
  (let [t (clamp01 t)] (* t t t)))

(defn out-cubic [t]
  (let [t (- 1.0 (clamp01 t))] (- 1.0 (* t t t))))

(defn in-out-cubic [t]
  (let [t (clamp01 t)]
    (if (< t 0.5)
      (* 4.0 t t t)
      (let [u (- (* 2.0 t) 2.0)] (+ 1.0 (* 0.5 u u u))))))

(defn out-expo [t]
  (let [t (clamp01 t)]
    (if (>= t 1.0) 1.0 (- 1.0 (Math/pow 2.0 (* -10.0 t))))))

(defn out-back
  "Slight overshoot (s=1.70158). Not clamped to 1.0 on purpose, but endpoints
   are exact so keyframe boundaries land on their target values."
  [t]
  (let [t (clamp01 t)]
    (cond
      (<= t 0.0) 0.0
      (>= t 1.0) 1.0
      :else (let [s 1.70158
                  u (- t 1.0)]
              (+ 1.0 (* u u (+ (* (+ s 1.0) u) s)))))))

(defn step
  "Hard cut at t=1 (holds 0 until the end). Useful for discrete counters."
  [t]
  (if (>= (clamp01 t) 1.0) 1.0 0.0))

(def eases
  {:linear linear
   :in-cubic in-cubic
   :out-cubic out-cubic
   :in-out-cubic in-out-cubic
   :out-expo out-expo
   :out-back out-back
   :step step})

(defn ease-fn
  "Resolve an ease keyword to a function; unknown keywords fall back to :linear."
  [k]
  (get eases k linear))
