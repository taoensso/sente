(defproject com.taoensso.examples/sente "1.21.0-SNAPSHOT"
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
  [[org.clojure/clojure       "1.11.1"]
   [org.clojure/clojurescript "1.11.60"]
   [org.clojure/core.async    "1.6.673"]
   [nrepl                     "1.0.0"] ; Optional, for Cider

   [com.taoensso/sente  "1.21.0-SNAPSHOT"] ; <--- Sente
   [com.taoensso/timbre           "6.2.2"]

   ;;; TODO Choose (uncomment) a supported web server -----------------------
   [http-kit                             "2.7.0"] ; Default
   ;; [org.immutant/web                  "x.y.z"
   ;;  :exclusions [ring/ring-core]]
   ;; [nginx-clojure/nginx-clojure-embed "x.y.z"] ; Needs v0.4.2+
   ;; [aleph                             "x.y.z"]
   ;; [info.sunng/ring-jetty9-adapter    "x.y.z"]
   ;; -----------------------------------------------------------------------

   [ring                     "1.10.0"]
   [ring/ring-defaults        "0.3.4"] ; Includes `ring-anti-forgery`, etc.
   ;; [ring-anti-forgery      "x.y.z"]

   [compojure                 "1.7.0"] ; Or routing lib of your choice
   [hiccup                    "1.0.5"] ; Optional, just for HTML

   ;;; Transit deps optional; may be used to aid perf. of larger data payloads
   ;;; (see reference example for details):
   [com.cognitect/transit-clj  "1.0.333"]
   [com.cognitect/transit-cljs "0.8.280"]]

  :plugins
  [[lein-pprint       "1.3.2"]
   [lein-ancient      "0.7.0"]
   [lein-cljsbuild    "1.1.8"]
   [cider/cider-nrepl "0.31.0"]] ; Optional, for use with Emacs

  :cljsbuild
  {:builds
   [{:id :cljs-client
     :source-paths ["src"]
     :compiler {:output-to "resources/public/main.js"
                :optimizations :whitespace #_:advanced
                :pretty-print true}}]}

  :main example.server

  :clean-targets ^{:protect false} ["resources/public/main.js"]

  ;; Call `lein start-dev` to get a (headless) development repl that you can
  ;; connect to with Cider+emacs or your IDE of choice:
  :aliases
  {"start-dev" ["do" "clean," "cljsbuild" "once," "repl" ":headless"]
   "start"     ["do" "clean," "cljsbuild" "once," "run"]}

  :repositories
  {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"})
