(defproject com.taoensso.examples/sente "1.11.0-SNAPSHOT"
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
  [[org.clojure/clojure       "1.8.0"]
   [org.clojure/clojurescript "1.9.225"]
   [org.clojure/core.async    "0.2.385"]
   [org.clojure/tools.nrepl   "0.2.12"] ; Optional, for Cider

   [com.taoensso/sente        "1.11.0-SNAPSHOT"] ; <--- Sente
   [com.taoensso/timbre       "4.7.4"]

   ;;; TODO Choose (uncomment) a supported web server -----------------------
   [http-kit                             "2.2.0"] ; Default
   ;; [org.immutant/web                  "2.1.4"]
   ;; [nginx-clojure/nginx-clojure-embed "0.4.4"] ; Needs v0.4.2+
   ;; [aleph                             "0.4.1"]
   ;; -----------------------------------------------------------------------

   [ring                      "1.5.0"]
   [ring/ring-defaults        "0.2.1"] ; Includes `ring-anti-forgery`, etc.
   ;; [ring-anti-forgery      "1.0.0"]

   [compojure                 "1.5.1"] ; Or routing lib of your choice
   [hiccup                    "1.0.5"] ; Optional, just for HTML

   ;;; Transit deps optional; may be used to aid perf. of larger data payloads
   ;;; (see reference example for details):
   [com.cognitect/transit-clj  "0.8.288"]
   [com.cognitect/transit-cljs "0.8.239"]]

  :plugins
  [[lein-pprint         "1.1.2"]
   [lein-ancient        "0.6.10"]
   [com.cemerick/austin "0.1.6"]
   [lein-cljsbuild      "1.1.4"]
   [cider/cider-nrepl   "0.12.0"] ; Optional, for use with Emacs
   ]

  :cljsbuild
  {:builds
   [{:id :cljs-client
     :source-paths ["src"]
     :compiler {:output-to "target/main.js"
                :optimizations :whitespace #_:advanced
                :pretty-print true}}]}

  :main example.server

  ;; Call `lein start-repl` to get a (headless) development repl that you can
  ;; connect to with Cider+emacs or your IDE of choice:
  :aliases
  {"start-repl" ["do" "clean," "cljsbuild" "once," "repl" ":headless"]
   "start"      ["do" "clean," "cljsbuild" "once," "run"]}

  :repositories
  {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"})
