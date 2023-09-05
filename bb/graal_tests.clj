#!/usr/bin/env bb

(ns graal-tests
  (:require
   [clojure.string :as str]
   [babashka.fs    :as fs]
   [babashka.process :refer [shell]]))

(defn uberjar []
  (let [command "lein with-profiles +graal-tests uberjar"
        command
        (if (fs/windows?)
          (if (fs/which "lein")
            command
            ;; Assume PowerShell powershell module
            (str "powershell.exe -command " (pr-str command)))
          command)]

    (shell command)))

(defn executable [dir name]
  (-> (fs/glob dir (if (fs/windows?) (str name ".{exe,bat,cmd}") name))
      first
      fs/canonicalize
      str))

(defn native-image []
  (let [graalvm-home (System/getenv "GRAALVM_HOME")
        bin-dir (str (fs/file graalvm-home "bin"))]
    (shell (executable bin-dir "gu") "install" "native-image")
    (shell (executable bin-dir "native-image")
      "--features=clj_easy.graal_build_time.InitClojureClasses"
      "--no-fallback" "-jar" "target/graal-tests.jar" "graal_tests")))

(defn run-tests []
  (let [{:keys [out]} (shell {:out :string} (executable "." "graal_tests"))]
    (assert (str/includes? out "loaded") out)
    (println "Native image works!")))
