(ns strojure.parsesso.state
  (:require [strojure.parsesso.core :as p]
            [strojure.parsesso.impl.reply :as reply]
            [strojure.parsesso.impl.state :as state]))

#?(:clj  (set! *warn-on-reflection* true)
   :cljs (set! *warn-on-infer* true))

;;,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,

(defn do-update-parser-state
  "This parser applies function `f` to the parser state and returns `nil`."
  ([f]
   (p/parser
     (fn [state context]
       (let [s (f state)]
         (reply/e-ok context s nil)))))
  ([f arg]
   (do-update-parser-state #(f % arg))))

(defn update-parser-state
  "This parser applies function `f` to the parser state and returns modified
  parser state."
  ([f]
   (p/parser
     (fn [state context]
       (let [s (f state)]
         (reply/e-ok context s s)))))
  ([f arg]
   (update-parser-state #(f % arg)))
  ([f arg & args]
   (update-parser-state #(apply f % arg args))))

(def get-parser-state
  "This parser returns the full parser state as a 'State' record."
  (update-parser-state identity))

(def ^{:doc "This parser set the full parser state to `state`."
       :arglists '([state])}
  set-parser-state
  (comp do-update-parser-state constantly))

;;,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,

(def get-input
  "This parser returns the current input."
  (p/fmap state/input get-parser-state))

(def ^{:doc "This parser continues parsing with `input`."
       :arglists '([input])}
  set-input
  (partial do-update-parser-state state/set-input))

;;,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,

(def get-user-state
  "This parser returns the current user state."
  (p/fmap state/user get-parser-state))

(def ^{:docs "This parser sets the user state to `u`"
       :arglists '([u])}
  set-user-state
  (partial do-update-parser-state state/set-user-state))

(defn update-user-state
  "This parser applies function `f` to the user state. Suppose that we want to
  count identifiers in a source, we could use the user state as:

      (when-let [x identifier
                 _ (update-user-state inc)]
        (result x))
  "
  ([f]
   (do-update-parser-state state/update-user-state f))
  ([f & args]
   (do-update-parser-state state/update-user-state #(apply f % args))))

;;,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,
