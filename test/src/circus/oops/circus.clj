(ns oops.circus
  (:require [clojure.test :refer :all]
            [cljs.build.api :as compiler]
            [cljs.util :as cljs-util]
            [environ.core :refer [env]]
            [clj-logging-config.log4j :as config]
            [clojure.tools.logging :as log]
            [oops.tools :as tools :refer [get-arena-separator]]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [cuerdas.core :as cuerdas]
            [clojure.java.shell :as shell]
            [clojure.stacktrace :as stacktrace]
            [clojure.pprint :refer [pprint]]
            [clansi])
  (:import (org.apache.log4j Level)
           (java.io StringWriter)))

(def log-level (or (env :oops-log-level) "INFO"))                                                                             ; INFO, DEBUG, TRACE, ALL

(defn setup-logging! []
  (config/set-loggers! :root {:out   :console
                              :level (Level/toLevel log-level Level/INFO)}))

(defn build-options [main variant config & [overrides]]
  (assert main (str "main must be specified!"))
  (let [out (str (last (string/split main #"\.")) "-" variant)
        compiler-config (merge {:pseudo-names  true
                                :elide-asserts true
                                :optimizations :advanced
                                :main          (symbol main)
                                :output-dir    (str "test/resources/_compiled/" out "/_workdir")
                                :output-to     (str "test/resources/_compiled/" out "/main.js")}
                               (if-not (empty? config)
                                 {:external-config {:oops/config config}}))]
    (merge compiler-config overrides)))

(defn get-main-from-source [source]
  (if-let [m (re-matches #"test\/src\/arena\/(.*?)\.cljs" source)]
    (-> (second m)
        (string/replace #"\/" ".")
        (string/replace #"_" "-"))))

(defn make-build [file variant & [config overrides]]
  (let [source (str "test/src/arena/oops/arena/" file)]
    {:source  source
     :variant variant
     :options (build-options (get-main-from-source source) variant config overrides)}))

(defn get-key-mode-options [mode]
  {:key-set mode
   :key-get mode})

(defn get-build-variants [file]
  [(make-build file "default")
   (make-build file "goog" (get-key-mode-options :goog))])

(def builds
  (concat
    (get-build-variants "basic_oget.cljs")
    (get-build-variants "dynamic_oget.cljs")
    [(make-build "warnings.cljs" "dev" {} {:optimizations :whitespace})]))

(defn get-build-name [build]
  (let [{:keys [source variant]} build]
    (str (string/replace source #"test\/src\/arena\/oops\/" "") " [" variant "]")))

(defn exctract-filename-from-source-path [source]
  (let [group (re-find #"\/([^/]*)\.cljs$" source)]
    (assert group (str "unable to parse script name from '" source "'"))
    (second group)))

(defn get-transcript-path [kind build]
  (let [{:keys [source variant]} build
        filename (exctract-filename-from-source-path source)]
    (str "test/transcripts/" kind "/" filename "_" variant ".js")))

(defn get-actual-transcript-path [build]
  (get-transcript-path "actual" build))

(defn get-expected-transcript-path [build]
  (get-transcript-path "expected" build))

(defn produce-diff [path1 path2]
  (let [options-args ["-U" "5"]
        paths-args [path1 path2]]
    (try
      (let [result (apply shell/sh "colordiff" (concat options-args paths-args))]
        (if-not (empty? (:err result))
          (clansi/style (str "! " (:err result)) :red)
          (:out result)))
      (catch Throwable e
        (clansi/style (str "! " (.getMessage e)) :red)))))

(defn get-canonical-line [line]
  (string/trimr line))

(defn append-nl [text]
  (str text "\n"))

(defn get-canonical-transcript [transcript]
  (let [s (->> transcript
               (cuerdas/lines)
               (map get-canonical-line)
               (cuerdas/unlines)
               (append-nl))]                                                                                                  ; we want to be compatible with "copy transcript!" button which copies to clipboard with extra new-line
    (string/replace s #"\n\n+" "\n\n")))

(defn read-build-output [build]
  (let [output-path (get-in build [:options :output-to])]
    (try
      (slurp output-path)
      (catch Exception e
        (str "Error reading output: " (.getMessage e))))))

(defn extract-relevant-output [content]
  (let [separator (get-arena-separator)]
    (if-let [separator-index (string/last-index-of content separator)]
      (let [semicolon-index (or (string/index-of content ";" separator-index) separator-index)
            relevant-content (.substring content (+ semicolon-index 1))]
        relevant-content))))

(defn normalize-identifiers [[content starting-counter]]
  "The goal here is to rename all generated $<number>$ identifiers with stable numbering."
  (let [* (fn [[content counter] match]
            (let [needle (first match)
                  replacement (str counter (nth match 2))]
              [(string/replace content needle replacement) (inc counter)]))]
    (reduce * [content starting-counter] (re-seq #"(\d+)(\$|__)" content))))

(defn normalize-gensyms [[content starting-counter]]
  "The goal here is to rename all generated name<NUM> identifiers with stable numbering."
  (let [* (fn [[content counter] match]
            (if (> (Long/parseLong (first match)) 1000)
              (let [needle (first match)
                    stable-replacement (str counter)]
                [(string/replace content needle stable-replacement) (inc counter)])
              [content counter]))]
    (reduce * [content starting-counter] (re-seq #"(\d+)" content))))

(defn normalize-twins [[content starting-counter]]
  (let [counter (volatile! starting-counter)
        * (fn [_match]
            (vswap! counter inc)
            (str @counter))]
    [(string/replace content #"(\d+)_(\d+)" *) @counter]))

(defn safe-spit [path content]
  (io/make-parents path)
  (spit path content))

(defn print-banner! []
  (println)
  (println (str "Running compiler output tests under "
                "Clojure v" (clojure-version) " and "
                "ClojureScript v" (cljs-util/clojurescript-version)))
  (println "===================================================================================================="))

(defn beautify-js! [path]
  (log/debug (str "Beautify JS at '" path "'"))
  (let [options-args ["-n" "-s" "2"]
        paths-args ["-f" path "-o" path]]
    (try
      (let [result (apply shell/sh "js-beautify" (concat options-args paths-args))]
        (if-not (empty? (:err result))
          (log/error (str "! " (:err result)))))
      (catch Throwable e
        (log/error (str "! " (.getMessage e)))))))

(defn comment-out-text [s & [stuffer]]
  (->> s
       (cuerdas/lines)
       (map #(str "// " stuffer %))
       (cuerdas/unlines)))

; -- building ---------------------------------------------------------------------------------------------------------------

(defn pprint-str [v]
  (with-out-str
    (binding [*print-level* 5
              *print-length* 10]
      (pprint v))))

(defn build! [build]
  (log/info (str "Building '" (get-build-name build) "'"))
  (let [captured-out (new StringWriter)
        captured-err (new StringWriter)]
    (binding [*out* captured-out
              *err* captured-err]
      (try
        (compiler/build (:source build) (:options build))
        (catch Throwable e
          (.write *err* (str "THROWN: " (.getMessage e))))))
    {:build build
     :out   (str captured-out)
     :err   (str captured-err)
     :code  (or (extract-relevant-output (read-build-output build)) "NO GENERATED CODE")}))

(defn prepare-build-info [build]
  (str (get-build-name build) "\n"
       (pprint-str (into (sorted-map) (:options build)))))

(defn unwrap-snippets [content]
  (let [counter (volatile! 0)
        * (fn [match]
            (vswap! counter inc)
            (let [raw (string/replace (tools/decode (second match)) "\\n" "\n")
                  commented (comment-out-text raw "  ")]
              (str "\n"
                   "\n"
                   "// SNIPPET #" @counter ":\n"
                   commented "\n"
                   "// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n"
                   "\n"
                   "\n")))]
    (string/replace content #"console\.log\(['\"]-12345-SNIPPET:(.*?)-54321-['\"]\);" *)))

(defn replace-tagged-literals [content]
  (let [counter (volatile! 0)
        * (fn [match]
            (vswap! counter inc)
            (let [name (nth match 1)]
              (str "<" name "#" @counter ">")))]
    (string/replace content #"#object\[cljs\.tagged_literals\.(.*?) 0x(.*?) \"cljs\.tagged_literals\.(.*?)@(.*?)\"]" *)))

(defn post-process-code [code]
  (let [unwrapped-code (-> code
                           (unwrap-snippets)
                           (replace-tagged-literals))
        [stabilized-code] (-> [unwrapped-code 1]
                              (normalize-identifiers)
                              (normalize-gensyms)
                              (normalize-twins))]
    stabilized-code))

(defn write-build-transcript! [build-result]
  (let [{:keys [build code out err]} build-result
        separator "----------------------------------------------------------------------------------------------------------"
        actual-transcript-path (get-actual-transcript-path build)
        post-processed-code (post-process-code code)
        build-info (prepare-build-info build)
        parts [(str "// COMPILER CONFIG:\n" (comment-out-text build-info "  "))
               (if-not (empty? out) (str "// COMPILER STDOUT:\n" (comment-out-text out "  ")))
               (if-not (empty? err) (str "// COMPILER STDERR:\n" (comment-out-text err "  ")))
               post-processed-code]
        transcript (string/join "\n" (interpose (comment-out-text separator) (remove nil? parts)))
        canonical-transcript (get-canonical-transcript transcript)]
    (log/debug (str "Writing build transcript to '" actual-transcript-path "' (" (count canonical-transcript) " chars)"))
    (safe-spit actual-transcript-path canonical-transcript)
    (beautify-js! actual-transcript-path)))

(defn silent-slurp [path]
  (try
    (slurp path)
    (catch Throwable _
      "")))

(defn compare-transcripts! [build]
  (log/debug (str "Comparing build transcript with expected output..."))
  (try
    (let [expected-path (get-expected-transcript-path build)
          expected-transcript (silent-slurp expected-path)
          actual-path (get-actual-transcript-path build)
          actual-transcript (silent-slurp actual-path)]
      (when-not (= actual-transcript expected-transcript)
        (println)
        (println "-----------------------------------------------------------------------------------------------------")
        (println (str "! actual transcript differs for '" (get-build-name build) "' build:"))
        (println)
        (println (produce-diff expected-path actual-path))
        (println "-----------------------------------------------------------------------------------------------------")
        (println (str "> cat " actual-path))
        (println)
        (println actual-transcript)
        (do-report {:type     :fail
                    :message  (str "Build '" (get-build-name build) "' failed to match expected transcript.")
                    :expected (str "to match expected transcript " expected-path)
                    :actual   (str "didn't match, see " actual-path)}))
      (do-report {:type    :pass
                  :message (str "Build '" (get-build-name build) "' passed.")}))
    (catch Throwable e
      (do-report {:type     :fail
                  :message  (str "Build '" (get-build-name build) "' failed with an exception.")
                  :expected "no exception"
                  :actual   (str e)})
      (stacktrace/print-stack-trace e))))

(defn make-build-filter [filter]
  (if (empty? filter)
    (constantly false)
    (fn [name]
      (let [parts (string/split filter #"\s")
            * (fn [part]
                (re-find (re-pattern part) name))]
        (not (some * parts))))))

(def get-build-filter (memoize make-build-filter))

(defn skip-build? [build]
  (let [build-name (get-build-name build)
        filter-str (:oops-ft-filter env)
        filter-fn (get-build-filter filter-str)]
    (if (filter-fn build-name)
      (str "env OOPS_FT_FILTER='" filter-str "'"))))

(defn exercise-build! [build]
  (if-let [reason (skip-build? build)]
    (log/info (str "Skipping '" (get-build-name build) "' because of " reason))
    (let [build-result (build! build)]
      (write-build-transcript! build-result)
      (compare-transcripts! build))))

(defn exercise-builds! [builds]
  (doseq [build builds]
    (exercise-build! build)))

; -- tests ------------------------------------------------------------------------------------------------------------------

(deftest exercise-all-builds
  (exercise-builds! builds))

; -- main entry point -------------------------------------------------------------------------------------------------------

(defn -main []
  (setup-logging!)
  (print-banner!)

  (let [summary (run-tests 'oops.circus)]
    (if-not (successful? summary)
      (System/exit 1)
      (System/exit 0))))
