(ns strojure.parsesso.core
  (:refer-clojure :exclude [sequence when-let])
  (:require [strojure.parsesso.impl.core :as impl #?@(:cljs (:refer [Parser]))]
            [strojure.parsesso.impl.error :as e]
            [strojure.parsesso.impl.pos :as pos]
            [strojure.parsesso.impl.reply :as r #?@(:cljs (:refer [Failure]))])
  #?(:clj  (:import (strojure.parsesso.impl.core Parser)
                    (strojure.parsesso.impl.reply Failure))
     :cljs (:require-macros [strojure.parsesso.core :refer [defer when-let]])))

#?(:clj  (set! *warn-on-reflection* true)
   :cljs (set! *warn-on-infer* true))

;;,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,

(defn parser
  "Wraps function `(fn [state context])` in the instance of `Parser`."
  [f]
  (impl/->Parser f))

(defn parser? [p] (instance? Parser p))

;;,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,

;;; parsers

(defn result
  "This parser always succeeds with value `x` without consuming any input."
  [x]
  (parser
    (fn [state context]
      (r/e-ok context x state (e/new-empty (:pos state))))))

(defn fail
  "This parser always fails with message `msg` without consuming any input."
  [msg]
  (parser
    (fn [state context]
      (r/e-err context (e/new-message ::e/message msg (:pos state))))))

(defn label
  "This parser behaves as parser `p`, but whenever the parser `p` fails /without
  consuming any input/, it replaces expect error messages with the expect error
  message `msg`.

  This is normally used at the end of a set alternatives where we want to return
  an error message in terms of a higher level construct rather than returning
  all possible characters. For example, if the `expr` parser from the 'try'
  example would fail, the error message is: '...: expecting expression'. Without
  the `label` combinator, the message would be like '...: expecting \"let\" or
  letter', which is less friendly."
  [p msg]
  (parser
    (fn [state context]
      (letfn [(set-expect-message [e msg] (e/set-message e ::e/expect (or msg "")))
              (e-ok [x s e] (r/e-ok context x s (cond-> e (not (e/empty? e))
                                                          (set-expect-message msg))))
              (e-err [e] (r/e-err context (set-expect-message e msg)))]
        (p state (-> context
                     (r/set-e-ok e-ok)
                     (r/set-e-err e-err)))))))

(defn unexpected
  "This parser always fails with an unexpected error message `msg` without
  consuming any input.

  The parsers 'fail', 'label' and `unexpected` are the three parsers used to
  generate error messages. Of these, only `label` is commonly used. For an
  example of the use of `unexpected`, see the definition of `not-followed-by`."
  [msg]
  (parser
    (fn [state context]
      (r/e-err context (e/new-message ::e/un-expect msg (:pos state))))))

(defn escape
  "This parser behaves like parser `p`, except that it pretends that it hasn't
  consumed any input when an error occurs.

  This combinator is used whenever arbitrary look ahead is needed. Since it
  pretends that it hasn't consumed any input when `p` fails, the `choice`
  combinator will try its second alternative even when the first parser failed
  while consuming input."
  [p]
  (parser
    (fn [state context]
      (p state (-> context (r/set-c-err (partial r/e-err context)))))))

(defn look-ahead
  "This parser parses `p` without consuming any input. If `p` fails and consumes
  some input, so does `look-ahead`. Combine with `accept` if this is
  undesirable."
  [p]
  (parser
    (fn [state context]
      (letfn [(e-ok [x _ _] (r/e-ok context x state (e/new-empty (:pos state))))]
        (p state (-> context
                     (r/set-c-ok e-ok)
                     (r/set-e-ok e-ok)))))))

(defn token
  "This parser accepts a token when `(pred token)` returns logical true. The
  token can be shown in error message using `(msg-fn token)`."
  ([pred],,,,,,,,,,,,,,,, (token pred pos/next-pos (partial str "token: ") nil))
  ([pred, msg-fn],,,,,,,, (token pred pos/next-pos msg-fn nil))
  ;; TODO: split to two versions for get-next-user like in haskell (for performance?)
  ([pred, pos-fn, msg-fn] (token pred pos-fn msg-fn nil))
  ([pred, pos-fn, msg-fn, user-fn]
   (parser
     (fn [state context]
       (if-let [input (seq (:input state))]
         (let [tok (first input)]
           (if (pred tok)
             (let [pos (:pos state)
                   new-input (rest input)
                   new-pos (pos-fn pos tok new-input)
                   new-state (impl/->State new-input new-pos (cond->> (:user state)
                                                               user-fn (user-fn pos tok new-input)))]
               (r/c-ok context tok new-state (e/new-empty new-pos)))
             (r/e-err context (e/new-message ::e/sys-unexpect (delay (msg-fn tok)) (:pos state)))))
         (r/e-err context (e/new-message ::e/sys-unexpect "" (:pos state))))))))

(defn many*
  "This parser applies the parser `p` zero or more times. Returns a sequence of
  the returned values or `p`."
  [p]
  (parser
    (fn [state context]
      (let [my-context (-> context (r/set-e-ok (impl/e-ok-throw-empty 'many*)))
            walk (fn walk [xs x s _e]
                   (let [xs (conj! xs x)]
                     (p s (-> my-context
                              (r/set-c-ok (partial walk xs))
                              (r/set-e-err (fn [e] (r/c-ok context (seq (persistent! xs)) s e)))))))]
        (p state (-> my-context
                     (r/set-c-ok (partial walk (transient [])))
                     (r/set-e-err (partial r/e-ok context nil state))))))))

(defn skip*
  "This parser applies the parser `p` zero or more times, skipping its result."
  [p]
  (parser
    (fn [state context]
      (let [my-context (-> context (r/set-e-ok (impl/e-ok-throw-empty 'skip*)))
            walk (fn walk [_x s _e]
                   (p s (-> my-context
                            (r/set-c-ok walk)
                            (r/set-e-err (partial r/c-ok context nil s)))))]
        (p state (-> my-context
                     (r/set-c-ok walk)
                     (r/set-e-err (partial r/e-ok context nil state))))))))

;;,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,

;;; combinators

(defn bind
  "m - parser, f - (fn [x] parser), returns parser"
  [p f]
  (parser
    (fn [state context]
      (letfn [(c-ok-p [x s e]
                ;; - if (f x) doesn't consume input, but is okay, we still return in the consumed
                ;; continuation
                ;; - if (f x) doesn't consume input, but errors, we return the error in the
                ;; 'consumed-err' continuation
                (letfn [(c-ok-fx [x s ee] (r/c-ok context x s (if (e/empty? e) ee (e/merge-error e ee))))
                        (c-err-fx [ee] (r/c-err context (if (e/empty? e) ee (e/merge-error e ee))))]
                  ((f x) s (-> context
                               (r/set-e-ok c-ok-fx)
                               (r/set-e-err c-err-fx)))))
              (e-ok-p [x s e]
                (if (e/empty? e)
                  ((f x) s context)
                  ;; - in these cases, (f x) can return as empty
                  (letfn [(e-ok-fx [x s ee] (r/e-ok context x s (e/merge-error e ee)))
                          (e-err-fx [ee] (r/e-err context (e/merge-error e ee)))]
                    ((f x) s (-> context
                                 (r/set-e-ok e-ok-fx)
                                 (r/set-e-err e-err-fx))))))]
        (p state (-> context
                     (r/set-c-ok c-ok-p)
                     (r/set-e-ok e-ok-p)))))))

(defmacro defer
  [& body]
  (let [state (gensym) context (gensym)]
    `(parser
       (fn [~state ~context]
         ((do ~@body) ~state ~context)))))

(defmacro when-let
  [[& bindings] & body]
  ;; TODO: validate macro arguments
  (let [[sym p] (take 2 bindings)]
    (if (= 2 (count bindings))
      `(bind ~p (fn [~sym] ~@body))
      `(bind ~p (fn [~sym] (when-let ~(drop 2 bindings) ~@body))))))

(defn >>
  "This parser tries to apply the parsers in order, until last of them succeeds.
  Returns the value of the last parser, discards result of all preceding
  parsers."
  ([p pp]
   (bind p (fn [_] pp)))
  ([p pp ppp]
   (-> p (>> pp) (>> ppp)))
  ([p pp ppp & more]
   (reduce >> (list* p pp ppp more))))

(defn alt
  "This parser tries to apply the parsers in order, until one of them succeeds.
  Returns the value of the succeeding parser."
  ([p pp]
   (parser
     (fn [state context]
       (letfn [(e-err-p [e]
                 (letfn [(e-ok-pp [x s ee] (r/e-ok context x s (e/merge-error e ee)))
                         (e-err-pp [ee] (r/e-err context (e/merge-error e ee)))]
                   (pp state (-> context
                                 (r/set-e-ok e-ok-pp)
                                 (r/set-e-err e-err-pp)))))]
         (p state (-> context
                      (r/set-e-err e-err-p)))))))
  ([p pp ppp]
   (-> p (alt pp) (alt ppp)))
  ([p pp ppp & more]
   (reduce alt (list* p pp ppp more))))

(defn fmap
  "This parser applies function `f` to result of the parser `p`."
  [f p]
  (when-let [x p]
    (result (f x))))

(defn sequence
  "This parser tries to apply parsers in order until all of them succeeds.
  Returns a sequence of values returned by every parser."
  [ps]
  (if-let [p (first ps)]
    (when-let [x p, xs (sequence (rest ps))]
      (result (cons x xs)))
    (result nil)))

;; TODO: Consider removing optional from API
(defn optional
  "This parser tries to apply parser `p`. If `p` fails without consuming input,
  it returns the value `x` (or `nil`), otherwise the value returned by `p`.
  Unlike Haskell's `optional` combinator it does not discard the result of `p`
  and behaves like `option` combinator."
  ([p] (optional p nil))
  ([p x]
   (alt p (result x))))

(defn between
  "This parser parses `open`, followed by `p` and `close`. Returns the value
  returned by `p`."
  ([p around] (between p around around))
  ([p open close]
   (when-let [_ open, x p, _ close]
     (result x))))

(defn skip+
  "This parser applies the parser `p` /one/ or more times, skipping its result."
  [p]
  (>> p (skip* p)))

(defn many+
  "This parser applies the parser `p` /one/ or more times. Returns a sequence of
  the returned values of `p`."
  [p]
  (when-let [x p, xs (many* p)]
    (result (cons x xs))))

;; TODO: argument order
;; TODO: Check if it should be consumed or not if n > length.
;; TODO: Rewrite similar to haskell?
(defn many-count
  "This parser parses `n` occurrences of `p`. If `n` is smaller or equal to
  zero, the parser equals to `(return nil)`. Returns a sequence of `n` values
  returned by `p`."
  [n p]
  (if (pos? n) (when-let [x p, xs (many-count (dec n) p)]
                 (result (cons x xs)))
               (result nil)))

(defn sep-by+
  "This parser parses /one/ or more occurrences of `p`, separated by `sep`.
  Returns a sequence of values returned by `p`."
  [p sep]
  (when-let [x p, xs (many* (>> sep p))]
    (result (cons x xs))))

(defn sep-by*
  "This parser parses /zero/ or more occurrences of `p`, separated by `sep`.
  Returns a sequence of values returned by `p`."
  [p sep]
  (alt (sep-by+ p sep)
       (result nil)))

(defn sep-by-end+
  "This parser parses /one/ or more occurrences of `p`, separated and ended by
  `sep`. Returns a sequence of values returned by `p`."
  [p sep]
  (many+ (when-let [x p, _ sep]
           (result x))))

(defn sep-by-end*
  "This parser parses /zero/ or more occurrences of `p`, separated and ended by
  `sep`. Returns a sequence of values returned by `p`."
  [p sep]
  (alt (sep-by-end+ p sep)
       (result nil)))

(declare sep-by-end-opt*)

(defn sep-by-end-opt+
  "This parser parses /one/ or more occurrences of `p`, separated and optionally
  ended by `sep`. Returns a sequence of values returned by `p`."
  [p sep]
  (when-let [x p]
    (alt (when-let [_ sep, xs (sep-by-end-opt* p sep)]
           (result (cons x xs)))
         (result [x]))))

(defn sep-by-end-opt*
  "This parser parses /zero/ or more occurrences of `p`, separated and optionally
  ended by `sep`. Returns a sequence of values returned by `p`."
  [p sep]
  (alt (sep-by-end-opt+ p sep)
       (result nil)))

;; TODO: Consider moving chains to separate namespace like kern

(defn chain-left+
  "This parser parses /one/ or more occurrences of `p`, separated by `op`
  Returns a value obtained by a /left/ associative application of all functions
  returned by `op` to the values returned by `p`. This parser can for example be
  used to eliminate left recursion which typically occurs in expression
  grammars."
  [p op]
  (letfn [(more [x] (alt (when-let [f op, y p]
                           (more (f x y)))
                         (result x)))]
    (when-let [x p]
      (more x))))

(defn chain-left*
  "This parser parses /zero/ or more occurrences of `p`, separated by `op`.
  Returns a value obtained by a /left/ associative application of all functions
  returned by `op` to the values returned by `p`. If there are zero occurrences
  of `p`, the value `x` is returned."
  [p op x]
  (alt (chain-left+ p op)
       (result x)))

(defn chain-right+
  "This parser parses /one/ or more occurrences of `p`, separated by `op`.
  Returns a value obtained by a /right/ associative application of all functions
  returned by `op` to the values returned by `p`."
  [p op]
  (letfn [(scan [] (when-let [x p]
                     (more x)))
          (more [x] (alt (when-let [f op, y (scan)]
                           (result (f x y)))
                         (result x)))]
    (scan)))

(defn chain-right*
  "Parses /zero/ or more occurrences of `p`, separated by `op`. Returns a value
  obtained by a /right/ associative application of all functions returned by
  `op` to the values returned by `p`. If there are no occurrences of `p`, the
  value `x` is returned."
  [p op x]
  (alt (chain-right+ p op)
       (result x)))

;;; Tricky combinators

(def any-token
  "This parser accepts any kind of token. It is for example used to implement
  'eof'. Returns the accepted token."
  (token any? (fn [pos _ _] pos)))

(defn not-followed-by
  "This parser only succeeds when parser `p` fails. This parser does not consume
  any input. This parser can be used to implement the 'longest match' rule. For
  example, when recognizing keywords (for example `let`), we want to make sure
  that a keyword is not followed by a legal identifier character, in which case
  the keyword is actually an identifier (for example `lets`)."
  [p]
  (escape (alt (when-let [c (escape p)]
                 (unexpected (delay (str c))))
               (result nil))))

(def eof
  "This parser only succeeds at the end of the input. This is not a primitive
  parser but it is defined using 'not-followed-by'."
  ;; TODO: Implement using direct access to input for performance?
  (-> (not-followed-by any-token)
      (label "end of input")))

(defn many-till
  "This parser applies parser `p` /zero/ or more times until parser `end`
  succeeds. Returns a sequence of values returned by `p`."
  [p end]
  (letfn [(scan [] (alt (>> end (result nil))
                        (when-let [x p, xs (scan)]
                          (result (cons x xs)))))]
    (scan)))

(defn debug-state
  "This parser prints the remaining parser state at the time it is invoked. It
  is intended to be used for debugging parsers by inspecting their intermediate
  states."
  [label]
  (alt (escape (when-let [x (escape (many+ any-token))
                          _ (defer (println (str label ": " x))
                                   (escape eof))]
                 (fail x)))
       (result nil)))

(defn debug-parser
  "This parser prints to the console the remaining parser state at the time it
  is invoked. It then continues to apply parser `p`, and if `p` fails will
  indicate that the label has been backtracked. It is intended to be used for
  debugging parsers by inspecting their intermediate states."
  [label p]
  (>> (debug-state label)
      (alt p, (defer (println (str label "  backtracked"))
                     (fail label)))))

;;,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,

(defn parse
  [p input]
  ;; TODO: Initialize source pos
  (p (impl/->State (seq input) 1 nil)))

(defn error? [reply] (instance? Failure reply))

;;,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,
