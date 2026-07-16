(ns dougaka-vector.storyboard
  "topic → storyboard EDN generation: the ONLY stage where an LLM is involved.
   This ns holds the pure parts (prompt construction, response extraction,
   validation feedback); the HTTP loop lives in bin/storyboard.cljs so these
   functions stay testable offline on every runtime."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dougaka-vector.spec :as spec]))

(def template-catalog
  "What the LLM is allowed to use, kept in sync with spec/templates and
   scene.cljc. Descriptions are contract, not prose — the model copies shapes.
   (Optional roles/args are marked 'opt', never with a ? in the key itself.)"
  "- :title-card   copy roles: :title, :sub(opt)               full-frame statement
- :bar-chart    copy roles: :kicker :panel-title :badge :caption :annotation (all opt)
                args: {:values [0..1 ...], :highlight {idx color-kw} opt,
                       :annotation {:from idx :to idx} opt, :axis-labels [min max] opt}
- :line-chart   copy roles: :kicker :panel-title :caption :label (all opt)
                args: {:values [0..1 ...], :axis-labels [min max] opt, :line-color kw opt}
- :flow         copy roles: :kicker(opt), :steps = VECTOR of copy-ids   boxed pipeline L→R
- :big-number   copy roles: :kicker :label (opt)
                args: {:from n, :to n, :decimals n opt, :prefix s opt, :suffix s opt}
- :callout      copy roles: :title, :lines = VECTOR of copy-ids         takeaway panel
Color keywords: :accent (cyan, the signal) :alert (red, failure/outlier)
:warn (amber, default bars) :ok (green) :ink :ink-dim")

(def mini-example
  "A compact worked example embedded in the system prompt — models copy
   shapes far more reliably than they follow prose."
  "{:video/id \"example\"
 :video/fps 30
 :video/size [1920 1080]
 :video/theme :cyber-dark
 :video/copy {:hook {:en \"WHERE IT BREAKS\" :ja \"どこで壊れるか\"}
              :s1 {:en \"INPUT\" :ja \"入力\"}
              :s2 {:en \"QUERY\" :ja \"クエリ\"}}
 :video/scenes
 [{:scene/id :hook :scene/dur 3.0 :scene/template :title-card
   :scene/copy {:title :hook}}
  {:scene/id :pipe :scene/dur 4.0 :scene/template :flow
   :scene/copy {:kicker :hook :steps [:s1 :s2]}}]}")

(defn system-prompt [{:keys [locales max-scenes max-dur]}]
  (str
   "You write storyboards for short technical explainer videos in a dark,
minimal motion-graphics style (animated vector charts carry the story; on-screen
text is sparse and always ALL-CAPS-short or one crisp sentence).

Output EXACTLY ONE EDN map, no markdown fences, no commentary. Contract:

{:video/id \"kebab-case-id\"
 :video/fps 30
 :video/size [1920 1080]
 :video/theme :cyber-dark
 :video/copy {:copy-id {" (str/join " " (map #(str % " \"...\"") locales)) "} ...}
 :video/scenes [{:scene/id :kw :scene/dur seconds :scene/template :kw
                 :scene/copy {role :copy-id ...} :scene/args {...}} ...]}

Rules:
- :video/copy maps copy-id → locale map, e.g. {:hook {:en \"...\" :ja \"...\"}}.
  NEVER locale → strings. Scenes reference copy-ids (keywords), NEVER literal
  strings. Every copy-id used must exist in :video/copy with every locale: "
   (pr-str locales) ".
- Templates available:\n" template-catalog "
- At most " (or max-scenes 8) " scenes; total duration ≤ " (or max-dur 60) "s;
  each scene 2.5–8s. Numbers in :values are normalized 0..1.
- The visuals must make the argument by themselves; captions only anchor them.
- No characters, no dialogue, no emoji.

Minimal shape example (copy the SHAPES, invent the content):\n" mini-example))

(defn user-prompt [topic]
  (str "Topic: " topic "\nReturn the storyboard EDN map now."))

(defn messages [topic opts]
  [{:role "system" :content (system-prompt opts)}
   {:role "user" :content (user-prompt topic)}])

(defn extract-edn
  "LLM output → storyboard map or nil. Tolerates markdown fences and prose
   before/after; takes the first top-level map form."
  [s]
  (let [s (-> (str s)
              (str/replace #"(?s)```(?:edn|clojure)?" "")
              str/trim)
        start (str/index-of s "{")]
    (when start
      (try
        (let [v (edn/read-string (subs s start))]
          (when (map? v) v))
        (catch #?(:clj Exception :cljs :default) _ nil)))))

(defn check
  "Full validation for a generated storyboard: spec + locale completeness +
   duration budget. → {:ok? bool :errors [...]}"
  [sb {:keys [locales max-dur]}]
  (if-not (map? sb)
    {:ok? false :errors ["output was not a parseable EDN map"]}
    (let [base (spec/validate-storyboard sb)
          have (spec/locales sb)
          missing (remove have (or locales [:en]))
          total (reduce + 0 (keep :scene/dur (:video/scenes sb)))
          errors (cond-> (:errors base)
                   (seq missing)
                   (conj (str "locales " (vec missing) " missing from some copy entries"))
                   (> total (or max-dur 60))
                   (conj (str "total duration " total "s exceeds budget " (or max-dur 60) "s")))]
      {:ok? (empty? errors) :errors errors})))

(defn feedback
  "Errors → the retry message sent back to the model."
  [errors]
  (str "Your storyboard EDN was rejected:\n"
       (str/join "\n" (map #(str "- " %) errors))
       "\nReturn a corrected EDN map only."))
