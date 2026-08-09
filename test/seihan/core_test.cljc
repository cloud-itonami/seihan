(ns seihan.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [seihan.core :as seihan]))

(def ^:private a4-cmyk
  {:pages 16
   :colors #{:c :m :y :k}
   :paper-mm [210 297]
   :bleed-mm 3
   :trap-mm 0.1})

;; ---------------------------------------------------------------- happy path

(deftest plan-is-deterministic
  (let [a (seihan/plan a4-cmyk)
        b (seihan/plan a4-cmyk)]
    (is (= a b) "同じ job からは必ず同じ計画")))

(deftest happy-path-cmyk-a4
  (let [{:keys [plates findings imposition ok? spec]} (seihan/plan a4-cmyk)]
    (is (true? ok?))
    (is (empty? (filter seihan/blocking? findings)))
    (is (= 4 (count plates)))
    (is (= [:c :m :y :k] (mapv :id plates)))
    (is (= (range 4) (mapv :order plates)))
    (is (every? #(= 0.1 (:trap-mm %)) plates))
    (is (= [210.0 297.0] (:trim-mm imposition)))
    (is (= [216.0 303.0] (:with-bleed-mm imposition)))
    (is (= 16 (:pages imposition)))
    (is (= 4 (:signature-size imposition)))
    (is (= 4 (:signatures imposition)))
    (is (= :saddle-stitch (:binding spec)))))

(deftest process-color-aliases
  (let [job (seihan/plan {:pages 4
                          :colors #{:cyan :magenta :yellow :black}
                          :paper-mm [100 100]
                          :bleed-mm 0
                          :trap-mm 0})]
    (is (true? (:ok? job)))
    (is (= [:c :m :y :k] (mapv :id (:plates job))))))

(deftest spot-colors-follow-process
  (let [job (seihan/plan {:pages 4
                          :colors #{:k :spot/gold :c {:spot "Pantone 185 C"}}
                          :paper-mm [210 297]
                          :bleed-mm 3
                          :trap-mm 0.15})
        ids (mapv :id (:plates job))]
    (is (true? (:ok? job)))
    (is (= :c (first ids)))
    (is (= :k (second ids)))
    (is (= 4 (count ids)))
    (is (every? #(or (= :process (:kind %)) (= :spot (:kind %)))
                (:plates job)))
    (is (= 2 (count (filter :spot? (:plates job)))))))

(deftest press-sheet-n-up
  (let [job (seihan/plan {:pages 8
                          :colors #{:k}
                          :paper-mm [100 100]
                          :bleed-mm 0
                          :trap-mm 0
                          :binding :flat
                          :press-sheet-mm [220 220]})
        imp (:imposition job)]
    (is (true? (:ok? job)))
    (is (= :sheet (:mode imp)))
    (is (= 4 (:pages-per-sheet imp)))
    (is (= 2 (:sheets imp)))
    (is (= 2 (get-in imp [:n-up :across])))
    (is (= 2 (get-in imp [:n-up :down])))))

(deftest summary-shape
  (let [s (seihan/summary (seihan/plan a4-cmyk))]
    (is (true? (:ok? s)))
    (is (= 4 (:plate-count s)))
    (is (= 0 (:blocking s)))
    (is (= 16 (:pages s)))))

;; ---------------------------------------------------------------- holds / blocking findings

(deftest zero-pages-blocks
  (let [job (seihan/plan (assoc a4-cmyk :pages 0))]
    (is (false? (:ok? job)))
    (is (some #{:zero-pages} (map :kind (:findings job))))
    (is (every? seihan/blocking?
                (filter #(= :zero-pages (:kind %)) (:findings job))))))

(deftest missing-pages-blocks
  (let [job (seihan/plan (dissoc a4-cmyk :pages))]
    (is (false? (:ok? job)))
    (is (some #{:missing-pages} (map :kind (:findings job))))))

(deftest negative-bleed-blocks
  (let [job (seihan/plan (assoc a4-cmyk :bleed-mm -1))]
    (is (false? (:ok? job)))
    (is (some #{:negative-bleed} (map :kind (:findings job))))
    ;; pages+paper が妥当なら imposition は bleed を 0 に潰して計画を残す
    ;; （止めた理由と、直した後の寸法を同時に見せる）
    (is (some? (:imposition job)))
    (is (= 0.0 (get-in job [:imposition :bleed-mm])))))

(deftest negative-trap-blocks
  (let [job (seihan/plan (assoc a4-cmyk :trap-mm -0.05))]
    (is (false? (:ok? job)))
    (is (some #{:negative-trap} (map :kind (:findings job))))
    (is (empty? (:plates job)) "負 trap では版を組まない")))

(deftest missing-paper-size-blocks
  (let [job (seihan/plan (dissoc a4-cmyk :paper-mm))]
    (is (false? (:ok? job)))
    (is (some #{:missing-paper-size} (map :kind (:findings job))))
    (is (nil? (:imposition job)))))

(deftest invalid-paper-size-blocks
  (testing "ゼロ寸法"
    (let [job (seihan/plan (assoc a4-cmyk :paper-mm [0 297]))]
      (is (false? (:ok? job)))
      (is (some #{:invalid-paper-size} (map :kind (:findings job))))))
  (testing "要素数不足"
    (let [job (seihan/plan (assoc a4-cmyk :paper-mm [210]))]
      (is (false? (:ok? job)))
      (is (some #{:invalid-paper-size} (map :kind (:findings job)))))))

(deftest no-colors-blocks
  (let [job (seihan/plan (assoc a4-cmyk :colors #{}))]
    (is (false? (:ok? job)))
    (is (some #{:no-colors} (map :kind (:findings job))))
    (is (empty? (:plates job)))))

(deftest too-many-colors-blocks
  (let [spots (into #{:c :m :y :k}
                    (map #(keyword "spot" (str "s" %)) (range 6)))
        job (seihan/plan (assoc a4-cmyk :colors spots))]
    (is (> (count spots) seihan/max-total-colors))
    (is (false? (:ok? job)))
    (is (some #{:too-many-colors} (map :kind (:findings job))))))

(deftest too-many-spot-colors-blocks
  (let [spots (into #{} (map #(keyword "spot" (str "ink" %)) (range 5)))
        job (seihan/plan {:pages 2
                          :colors spots
                          :paper-mm [100 100]
                          :bleed-mm 0
                          :trap-mm 0
                          :binding :flat})]
    (is (= 5 (count spots)))
    (is (false? (:ok? job)))
    (is (some #{:too-many-spot-colors} (map :kind (:findings job))))))

(deftest saddle-stitch-page-count-warns
  (let [job (seihan/plan (assoc a4-cmyk :pages 6 :binding :saddle-stitch))
        kinds (set (map :kind (:findings job)))]
    (is (true? (:ok? job)) "4 の倍数でないのは警告であって blocking ではない")
    (is (contains? kinds :pages-not-multiple-of-4))
    (is (not-any? seihan/blocking?
                  (filter #(= :pages-not-multiple-of-4 (:kind %))
                          (:findings job))))))

(deftest large-bleed-warns-only
  (let [job (seihan/plan (assoc a4-cmyk :bleed-mm 25))]
    (is (true? (:ok? job)))
    (is (some #{:large-bleed} (map :kind (:findings job))))))

(deftest blocking-predicate-covers-hard-holds
  (doseq [k [:missing-pages :zero-pages :missing-paper-size
             :invalid-paper-size :negative-bleed :negative-trap
             :no-colors :too-many-colors :too-many-spot-colors]]
    (is (true? (seihan/blocking? {:kind k})) (str k)))
  (is (false? (seihan/blocking? {:kind :pages-not-multiple-of-4})))
  (is (false? (seihan/blocking? {:kind :large-bleed}))))
