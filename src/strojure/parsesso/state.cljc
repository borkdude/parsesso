(ns strojure.parsesso.state
  "Parser combinators to work with parser state."
  (:require [strojure.parsesso.impl.reply :as reply]
            [strojure.parsesso.impl.state :as impl]
            [strojure.parsesso.parser :as p]))

#?(:clj  (set! *warn-on-reflection* true)
   :cljs (set! *warn-on-infer* true))

;;,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,

(defn do-update-state
  "This parser applies function `f` to the parser state and returns `nil`."
  ([f]
   (fn [state context]
     (let [s (f state)]
       (reply/e-ok context s nil))))
  ([f arg]
   (do-update-state #(f % arg))))

(defn update-parser-state
  "This parser applies function `f` to the parser state and returns modified
  parser state."
  [f]
  (fn [state context]
    (let [s (f state)]
      (reply/e-ok context s s))))

(def get-parser-state
  "This parser returns the full parser state as a 'State' record."
  (update-parser-state identity))

(def ^{:arglists '([state])}
  set-parser-state
  "This parser set the full parser state to `state`."
  (comp do-update-state constantly))

;;,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,

(def get-input
  "This parser returns the current input."
  (p/using get-parser-state impl/input))

(def ^{:arglists '([input])}
  set-input
  "This parser continues parsing with `input`."
  (partial do-update-state impl/set-input))

;;,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,

(def get-user-state
  "This parser returns the current user state."
  (p/using get-parser-state impl/user))

(def ^{:arglists '([u])}
  set-user-state
  "This parser sets the user state to `u`"
  (partial do-update-state impl/set-user-state))

(defn update-user-state
  "This parser applies function `f` to the user state. Suppose that we want to
  count identifiers in a source, we could use the user state as:

      (bind-let [x identifier
                 _ (update-user-state inc)]
        (result x))
  "
  [f]
  (do-update-state impl/update-user-state f))

;;,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,