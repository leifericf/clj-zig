(ns build
  "An example release build for a library of clj-zig functions. It bakes the
  namespace's native code into the resource tree, jars it with that code
  included, and deploys to Clojars. Copy it into your own project's
  `build.clj` and adjust the coordinate and namespace.

  Run the steps with tools.build:

      clojure -T:build bake
      clojure -T:build jar
      clojure -T:build deploy"
  (:require [clojure.tools.build.api :as b]
            [clj-zig.bake :as bake]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.example/widgets)
(def version "1.0.0")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn bake
  "Cross-compile every defnz in the library's native namespace into the
  classpath resource tree, so the published jar carries precompiled code for
  the whole target matrix and consumers never compile."
  [_]
  (bake/bake! {:ns 'com.example.widgets/native :out class-dir}))

(defn jar
  "Package the sources and the baked native libraries into a jar."
  [_]
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]})
  (b/jar {:class-dir class-dir :jar-file jar-file}))

(defn deploy
  "Publish the jar to Clojars. Needs CLOJARS_USERNAME and CLOJARS_PASSWORD
  in the environment."
  [_]
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
