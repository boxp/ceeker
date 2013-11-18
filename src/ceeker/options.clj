(ns ceeker.options
  (use [clojure.pprint]
       [clojure.java.shell]
       [ceeker.data])
  (require [clojure.string :as st])
  (:import (java.io File FileWriter)))

(defn current
  "現在のディレクトリを取得"
  []
  (System/getProperty "user.dir"))

(defn gen-gcc
  "Gen gcc commands"
  [compiler src lib inc]
  (apply sh
    (concat
      (list compiler "-c" src "-o"
        (st/replace
          (st/replace src #"src" "obj")
          #".cpp|.c"
          ".o"))
      (map #(str "-I./" %) inc)
      (vec (map #(str "-L./" %) lib)))))

(defmacro gen-link-gcc
  "Gen link gcc commands"
  [compiler lib inc args name]
  (concat
    (list 'sh compiler "-o" name) 
    (doall
      (map #(str "obj/" %) (. (File. "obj") list)))
    (doall
      (map #(str "-I" %) inc))
    (doall
      (map #(str "-L" %) lib))))
  

(defn ceeker-new
  [project]
  (do
    (println (str "Generating " project "..."))
    (doall
      (map #(.mkdir (File. %)) 
        [(str (current) "/" project)
         (str (current) "/" project "/src")
         (str (current) "/" project "/lib")
         (str (current) "/" project "/inc")]))
    (spit 
      (str (current) "/" project "/ceekerrc.clj")
      (str
        "{:name \"" project "\"\n"
        " :compiler \"gcc\" \n"
        " :version \"0.0.1\" \n"
        " :src-path \"src\" \n"
        " :lib-path [\"lib\"] \n"
        " :inc-path [\"inc\"]}"))
    (println "Finished!")))

(defn ceeker-build
  []
  (let [rc (try
             (load-file (str (current) "/ceekerrc.clj"))
             (catch Exception e 
               (do 
                 (println (.getMessage e))
                 (System/exit 0))))
        timestamp (ref
                    (try
                      (load-file (str (current) "/.tmp"))
                      (catch Exception e {})))]
     (do
       (.mkdir (File. "obj"))
       (println "building...")
       (loop [sc (vec (. (File. (:src-path rc)) list))]
         (if (= [] sc)
           nil
          (do
            ; 既に更新済みかつオブジェクトが存在するか.
            (if (and
                  (= 
                    ((keyword (first sc)) @timestamp)
                    (.lastModified (File. (str "src/" (first sc)))))
                  (some #(= (st/replace (first sc) #".c" ".o") %)
                    (.list (File. "obj"))))
              (println (first sc) "skip.")
              ; コンパイル＆タイムスタンプ更新
              (do
                (dosync
                  (alter timestamp merge 
                    {(keyword (first sc))
                     (.lastModified (File. (str "src/" (first sc))))}))
                (print (str "compiling " (first sc) "..."))
                (flush)
                (let [result
                  (gen-gcc 
                    (:compiler rc)
                    (str (:src-path rc) "/" (first sc))
                    (:lib-path rc)
                    (:inc-path rc))]
                (if (= (:exit result) 0)
                  (println "done!")
                  (do
                    (println (:out result))
                    (System/exit 0))))))
            (recur (drop 1 sc)))))
       (print "linking...")
       (flush)
       (let [result
         (gen-link-gcc 
           (:compiler rc)
           (:lib-path rc)
           (:inc-path rc)
           (:args rc)
           (:name rc))]
         (if (= (:exit result) 0)
           (println "done!")
           (do
             (println (:out result))
             (System/exit 0))))
       (spit ".tmp" @timestamp)
       (System/exit 0))))
       
(defn ceeker-run
  []
  (let [rc (try
             (load-file (str (current) "/ceekerrc.clj"))
             (catch Exception e 
               (.getMessage e)))]
  nil))
(defn ceeker-auto
  []
  (let [rc (try ; 設定ファイル読み込み
             (load-file (str (current) "/ceekerrc.clj"))
             (catch Exception e 
               (.getMessage e)))
        timestamp (ref ;タイムスタンプファイルの読み込み
                    (try
                      (load-file (str (current) "/.tmp"))
                      (catch Exception e {})))]
     (do
       ; objフォルダの生成
       (.mkdir (File. "obj"))
       (while true
         (do
           ; 待機時間
           (Thread/sleep 1000)
           ; 全てのファイルが更新済み且つオブジェクトが存在するか．
           (if ;(and
                 (every? 
                   #(= 
                     ((keyword %) @timestamp)
                     (.lastModified (File. (str "src/" %))))
                   (.list (File. "src")))
                 ;(=
                 ;  (set
                 ;    (doall
                 ;      (map #(st/replace % #".c" ".o")
                 ;        (.list (File. "src")))))
                 ;  (set (.list (File. "obj")))))
             nil        
             (do
               (println "building...")
               (loop [sc (vec 
                           (remove 
                             #(and
                               (not (= (seq ".c") (take-last 2 %)))
                               (not (= (seq ".cpp") (take-last 4 %))))
                             (. (File. (:src-path rc)) list)))]
                 ; ソースコードが存在するか
                 (if (= [] sc)
                   nil
                  (do
                    ; 既に更新済みかつオブジェクトが存在するか.
                    (if ;(and
                          (= 
                            ((keyword (first sc)) @timestamp)
                            (.lastModified (File. (str "src/" (first sc)))))
                          ;(some #(= (st/replace (first sc) #".c" ".o") %)
                          ;  (.list (File. "obj"))))
                      (println (first sc) "skip.")
                      ; コンパイル＆タイムスタンプ更新
                      (do
                        (dosync
                          (alter timestamp merge 
                            {(keyword (first sc))
                             (.lastModified (File. (str "src/" (first sc))))}))
                        (print (str "compiling " (first sc) "..."))
                        (flush)
                        (let [result
                          (gen-gcc 
                            (:compiler rc)
                            (str (:src-path rc) "/" (first sc))
                            (:lib-path rc)
                            (:inc-path rc))]
                          (if (= (:exit result) 0)
                            (println "done!")
                            (do
                              (println "failure!")
                              (println (:err result)))))
                        (dosync
                          (alter timestamp merge 
                            {(keyword (first sc))
                             (.lastModified (File. (str "src/" (first sc))))}))))
                    (recur (drop 1 sc)))))
               (print "linking...")
               (flush)
               ; アセンブリ
               (let [result
                 (gen-link-gcc 
                   (:compiler rc)
                   (:lib-path rc)
                   (:inc-path rc)
                   (:args rc)
                   (:name rc))]
                 (if (= (:exit result) 0)
                   (println "done!")
                   (do
                     (println "failure!")
                     (println (:err result)))))
               (spit ".tmp" @timestamp))))))))
