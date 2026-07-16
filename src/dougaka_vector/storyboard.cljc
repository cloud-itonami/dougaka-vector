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
   scene.cljc. Descriptions are contract, not prose — the model copies shapes."
  "- :title-card   copy{:title :sub?}                          full-frame statement
- :bar-chart    copy{:kicker? :panel-title? :badge? :caption? :annotation?}
                args{:values [0..1 ...] :highlight {idx color-kw}
                     :annotation {:from idx :to idx}? :axis-labels [min max]?}
- :line-chart   copy{:kicker? :panel-title? :caption? :label?}
                args{:values [0..1 ...] :axis-labels [min max]? :line-color kw?}
- :flow         copy{:kicker? :steps [copy-id ...]}            boxed pipeline L→R
- :big-number   copy{:kicker? :label?}
                args{:from n :to n :decimals n? :prefix s? :suffix s?}
- :callout      copy{:title :lines [copy-id ...]}              takeaway panel
Color keywords: :accent (cyan, the signal) :alert (red, failure/outlier)
:warn (amber, default bars) :ok (green) :ink :ink-dim")

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
- Scenes reference copy-ids (keywords), NEVER literal strings. Every copy-id
  used must exist in :video/copy with every locale: " (pr-str locales) ".
- Templates available:\n" template-catalog "
- At most " (or max-scenes 8) " scenes; total duration ≤ " (or max-dur 60) "s;
  each scene 2.5–8s. Numbers in :values are normalized 0..1.
- The visuals must make the argument by themselves; captions only anchor them.
- No characters, no dialogue, no emoji."))

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
