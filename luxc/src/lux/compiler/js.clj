(ns lux.compiler.js
  (:refer-clojure :exclude [compile])
  (:require (clojure [string :as string]
                     [set :as set]
                     [template :refer [do-template]])
            clojure.core.match
            clojure.core.match.array
            (lux [base :as & :refer [|let |do return* return |case]]
                 [type :as &type]
                 [reader :as &reader]
                 [lexer :as &lexer]
                 [parser :as &parser]
                 [analyser :as &analyser]
                 [optimizer :as &optimizer])
            [lux.optimizer :as &o]
            [lux.analyser.base :as &a]
            [lux.analyser.module :as &a-module]
            (lux.compiler [core :as &&core]
                          [io :as &&io]
                          [parallel :as &&parallel])
            (lux.compiler.js [base :as &&]
                             ;; [cache :as &&cache]
                             [lux :as &&lux]
                             [rt :as &&rt]
                             )
            (lux.compiler.js.proc [common :as &&common])
            )
  (:import (jdk.nashorn.api.scripting NashornScriptEngineFactory
                                      NashornScriptEngine
                                      ScriptObjectMirror)
           (jdk.nashorn.internal.runtime Undefined))
  )

;; [Resources]
(defn ^:private compile-expression [syntax]
  (|let [[[?type [_file-name _line _]] ?form] syntax]
    (|case ?form
      (&o/$bool ?value)
      (&&lux/compile-bool ?value)

      (&o/$nat ?value)
      (&&lux/compile-nat ?value)

      (&o/$int ?value)
      (&&lux/compile-int ?value)

      (&o/$deg ?value)
      (&&lux/compile-deg ?value)

      (&o/$real ?value)
      (&&lux/compile-real ?value)

      (&o/$char ?value)
      (&&lux/compile-char ?value)

      (&o/$text ?value)
      (&&lux/compile-text ?value)

      (&o/$tuple ?elems)
      (&&lux/compile-tuple compile-expression ?elems)

      (&o/$var (&/$Local ?idx))
      (&&lux/compile-local compile-expression ?idx)

      (&o/$captured ?scope ?captured-id ?source)
      (&&lux/compile-captured compile-expression ?scope ?captured-id ?source)

      (&o/$var (&/$Global ?module ?name))
      (&&lux/compile-global ?module ?name)

      (&o/$apply ?fn ?args)
      (&&lux/compile-apply compile-expression ?fn ?args)

      (&o/$loop _register-offset _inits _body)
      (&&lux/compile-loop compile-expression _register-offset _inits _body)

      (&o/$iter _register-offset ?args)
      (&&lux/compile-iter compile-expression _register-offset ?args)

      (&o/$variant ?tag ?tail ?members)
      (&&lux/compile-variant compile-expression ?tag ?tail ?members)

      (&o/$case ?value [?pm ?bodies])
      (&&lux/compile-case compile-expression ?value ?pm ?bodies)

      (&o/$let _value _register _body)
      (&&lux/compile-let compile-expression _value _register _body)

      (&o/$record-get _value _path)
      (&&lux/compile-record-get compile-expression _value _path)

      (&o/$if _test _then _else)
      (&&lux/compile-if compile-expression _test _then _else)

      (&o/$function _register-offset ?arity ?scope ?env ?body)
      (&&lux/compile-function compile-expression ?arity ?scope ?env ?body)

      (&o/$ann ?value-ex ?type-ex)
      (compile-expression ?value-ex)

      (&o/$proc [?proc-category ?proc-name] ?args special-args)
      (case ?proc-category
        ;; "js" ...
        ;; common
        (&&common/compile-proc compile-expression ?proc-category ?proc-name ?args special-args))

      _
      (assert false (prn-str 'JS=compile-expression (&/adt->text syntax))))
    ))

(defn init!
  "(-> (List Text) Null)"
  [resources-dirs ^String target-dir]
  nil)

(defn eval! [expr]
  (&/with-eval
    (|do [compiled-expr (compile-expression expr)
          js-output (&&/run-js! compiled-expr)]
      (return (&&/js-to-lux js-output)))))

(def all-compilers
  (&/T [(partial &&lux/compile-def compile-expression)
        (partial &&lux/compile-program compile-expression)
        (fn [^ScriptObjectMirror macro args state]
          (&&/js-to-lux (.call macro nil (to-array [(&&/wrap-lux-obj args)
                                                    (&&/wrap-lux-obj state)]))))]))

(defn compile-module [source-dirs name]
  (let [file-name (str name ".lux")]
    (|do [file-content (&&io/read-file source-dirs file-name)
          :let [file-hash (hash file-content)
                compile-module!! (&&parallel/parallel-compilation (partial compile-module source-dirs))]]
      ;; (&/|eitherL (&&cache/load name))
      (let [compiler-step (&analyser/analyse &optimizer/optimize eval! compile-module!! all-compilers)]
        (|do [module-exists? (&a-module/exists? name)]
          (if module-exists?
            (&/fail-with-loc (str "[Compiler Error] Can't re-define a module: " name))
            (|do [;; _ (&&cache/delete name)
                  _ (&a-module/create-module name file-hash)
                  _ (&a-module/flag-active-module name)
                  _ (if (= "lux" name)
                      &&rt/compile-LuxRT
                      (return nil))
                  ]
              (fn [state]
                (|case ((&/exhaust% compiler-step)
                        ;; (&/with-writer =class
                        ;;   (&/exhaust% compiler-step))
                        (&/set$ &/$source (&reader/from name file-content) state))
                  (&/$Right ?state _)
                  (&/run-state (|do [_ (&a-module/flag-compiled-module name)
                                     ;; _ (&&/save-class! &/module-class-name (.toByteArray =class))
                                     module-descriptor &&core/generate-module-descriptor
                                     _ (&&core/write-module-descriptor! name module-descriptor)]
                                 (return file-hash))
                               ?state)

                  (&/$Left ?message)
                  (&/fail* ?message))))))))
    ))

(let [!err! *err*]
  (defn compile-program [mode program-module resources-dir source-dirs target-dir]
    (do (init! resources-dir target-dir)
      (let [m-action (|do [;; _ (&&cache/pre-load-cache! source-dirs)
                           _ (compile-module source-dirs "lux")]
                       (compile-module source-dirs program-module))]
        (|case (m-action (&/init-state mode (&&/js-host)))
          (&/$Right ?state _)
          (do (println "Compilation complete!")
            ;; (&&cache/clean ?state)
            )

          (&/$Left ?message)
          (binding [*out* !err!]
            (do (println (str "Compilation failed:\n" ?message))
              (flush)
              (System/exit 1)
              ))
          )))))
