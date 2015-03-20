(defproject com.taoensso.examples/sente "1.4.1"
  :description "Sente, reference web-app example project"
  :url "https://github.com/ptaoussanis/sente"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "Same as Clojure"}
  :min-lein-version "2.3.3"
  :global-vars {*warn-on-reflection* true
                *assert* true}

  :dependencies
  [[org.clojure/clojure       "1.7.0-beta1"] ; May use any v1.5.1+
   ;; [org.clojure/clojure    "1.6.0"]

   ;; NB Have seen a number of cases of newer versions of Cljs causing issues
   ;; with incompatible dependencies, etc. Haven't gotten around to testing
   ;; specific newer versions of Cljs yet:
   ;; [org.clojure/clojurescript "0.0-3196"] ; TODO
   [org.clojure/clojurescript    "0.0-2411"]

   [org.clojure/core.async    "0.1.346.0-17112a-alpha"]

   [com.taoensso/sente        "1.4.1"] ; <--- Sente
   [com.taoensso/timbre       "3.4.0"]

   ;;; ---> Choose (uncomment) a supported web server <---
   [http-kit                  "2.1.19"]
   ;; [org.immutant/web       "2.0.0-beta2"]

   [ring                      "1.3.2"]
   [ring/ring-defaults        "0.1.4"] ; Includes `ring-anti-forgery`, etc.
   ;; [ring-anti-forgery      "1.0.0"]

   [compojure                 "1.3.3"] ; Or routing lib of your choice
   [hiccup                    "1.0.5"] ; Optional, just for HTML

   ;;; Transit deps optional; may be used to aid perf. of larger data payloads
   ;;; (see reference example for details):
   [com.cognitect/transit-clj  "0.8.271"]
   [com.cognitect/transit-cljs "0.8.207"]]

  :plugins
  [[lein-pprint         "1.1.2"]
   [lein-ancient        "0.6.4"] ; Seem to be having issues with 0.6.5+
   [com.cemerick/austin "0.1.6"]
   [com.keminglabs/cljx "0.6.0"]
   [lein-cljsbuild      "1.0.5"]
   [cider/cider-nrepl   "0.8.2"] ; Optional, for use with Emacs
   ]

  :prep-tasks [["cljx" "once"] "javac" "compile"]
  :cljx
  {:builds
   [{:source-paths ["src"] :rules :clj  :output-path "target/classes"}
    {:source-paths ["src"] :rules :cljs :output-path "target/classes"}]}

  :cljsbuild
  {:builds ; Compiled in parallel
   [{:id :main
     :source-paths ["src" "target/classes"]
     :compiler     {:output-to "resources/public/main.js"
                    :optimizations :whitespace #_:advanced
                    :pretty-print true}}]}

  ;; Call `lein start-dev` to get a (headless) development repl that you can
  ;; connect to with Cider+emacs or your IDE of choice:
  :aliases
  {"build-once" ["do" "cljx" "once," "cljsbuild" "once"]
   "start-dev"  ["repl" ":headless"]}

  :repositories
  {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"})
