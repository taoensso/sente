(defproject com.taoensso.examples/sente "1.8.1"
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
  [;; [org.clojure/clojure    "1.7.0"]
   [org.clojure/clojure       "1.8.0"]

   [org.clojure/clojurescript "1.7.170"]
   [org.clojure/core.async    "0.2.374"]
   [org.clojure/tools.nrepl   "0.2.12"] ; Optional, for Cider

   [com.taoensso/sente        "1.8.1"] ; <--- Sente
   [com.taoensso/timbre       "4.3.1"]

   ;;; ---> Choose (uncomment) a supported web server <---
   [http-kit                  "2.2.0-alpha1"]
   ;; [org.immutant/web       "2.1.0"] ; v2.1+ recommended
   ;; [nginx-clojure/nginx-clojure-embed "0.4.2"] ; Needs v0.4.2+

   [ring                      "1.4.0"]
   [ring/ring-defaults        "0.2.0"] ; Includes `ring-anti-forgery`, etc.
   ;; [ring-anti-forgery      "1.0.0"]

   [compojure                 "1.5.0"] ; Or routing lib of your choice
   [hiccup                    "1.0.5"] ; Optional, just for HTML

   ;;; Transit deps optional; may be used to aid perf. of larger data payloads
   ;;; (see reference example for details):
   [com.cognitect/transit-clj  "0.8.285"]
   [com.cognitect/transit-cljs "0.8.237"]]

  :plugins
  [[lein-pprint         "1.1.2"]
   [lein-ancient        "0.6.10"]
   [com.cemerick/austin "0.1.6"]
   [lein-cljsbuild      "1.1.3"]
   [cider/cider-nrepl   "0.11.0"] ; Optional, for use with Emacs
   ]

  :cljsbuild
  {:builds
   [{:id :cljs-client
     :source-paths ["src"]
     :compiler {:output-to "resources/public/main.js"
                :optimizations :whitespace #_:advanced
                :pretty-print true}}]}

  :main example.server

  ;; Call `lein start-repl` to get a (headless) development repl that you can
  ;; connect to with Cider+emacs or your IDE of choice:
  :aliases
  {"start-repl" ["do" "cljsbuild" "once," "repl" ":headless"]
   "start"      ["do" "cljsbuild" "once," "run"]}

  :repositories
  {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"})
