(ns dev.spec-main
  (:require
    [clojure.java.io :as io]
    [speclj.main :as speclj]))

(defn -main [& args]
  (let [spec-dir (.getAbsolutePath (io/file "spec"))]
    (if (seq args)
      (apply speclj/-main "-c" "-D" spec-dir args)
      (apply speclj/-main "-c" "-D" spec-dir))))