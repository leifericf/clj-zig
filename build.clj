(ns build
  "Release build for clj-zig itself. `jar` packages the library; `install`
  is the local dry-run that puts the jar in the local Maven repository
  without publishing; `deploy` publishes to Clojars and needs
  CLOJARS_USERNAME and CLOJARS_PASSWORD, run by the maintainer.

  Releases are dated, not numbered. The version and the matching git tag are
  both today's date as `YYYY.MM.DD`, a form Maven orders correctly. Set
  CLJ_ZIG_VERSION to override, for a second release in one day or to match an
  existing tag. The Clojars coordinate is `com.leifericf/clj-zig`, the
  verified group for the leifericf.com domain; the git-dep coordinate
  `io.github.leifericf/clj-zig` keeps working for contributors regardless."
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.leifericf/clj-zig)

(defn- date-version []
  (let [d (java.time.LocalDate/now)]
    (format "%04d.%02d.%02d" (.getYear d) (.getMonthValue d) (.getDayOfMonth d))))

(def version (or (System/getenv "CLJ_ZIG_VERSION") (date-version)))
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean
  "Remove build outputs."
  [_]
  (b/delete {:path "target"}))

(defn jar
  "Write the pom and package the sources into a jar."
  [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/leifericf/clj-zig"
                      :connection "scm:git:git://github.com/leifericf/clj-zig.git"
                      :developerConnection "scm:git:ssh://git@github.com/leifericf/clj-zig.git"
                      :tag version}
                ;; Clojars rejects a deploy whose pom carries no license.
                :pom-data [[:licenses
                            [:license
                             [:name "MIT License"]
                             [:url "https://opensource.org/license/mit"]]]]})
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file}))

(defn install
  "Build the jar and install it into the local Maven repository. The local
  dry-run that proves the artifact builds and resolves without publishing."
  [_]
  (clean nil)
  (jar nil)
  (b/install {:basis @basis :lib lib :version version
              :class-dir class-dir :jar-file jar-file}))

(defn deploy
  "Build the jar and publish it to Clojars. Needs CLOJARS_USERNAME and
  CLOJARS_PASSWORD in the environment; run by the maintainer."
  [_]
  (clean nil)
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
