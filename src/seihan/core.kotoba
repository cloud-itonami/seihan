(ns seihan.core
  "商業紙印刷の製版 decision core。

  紙のオフセット／デジタル前工程（CMYK + スポット、裁ち落とし、トラップ、面付け）を
  **純関数**で計画する。LLM もネットワークも使わない。同じ job map からは必ず同じ
  計画が出るので、承認した版と刷った版が同じであることをハッシュで示せる。

  ## 境界（sibling との役割分担）

  | repo | 領域 |
  |---|---|
  | **seihan**（本 repo） | **紙**の商業印刷製版（CMYK/スポット版・bleed・trap・imposition） |
  | `cloud-itonami/shirohan` | **ガーメント**装飾の白版（白インク下地・choke・白抜き） |

  白版（しろはん）は濃色ボディに色を載せるための**白インク下地**で、紙の製版とは
  別物。本ライブラリは shirohan の平面を侵食しない。

  ```clojure
  (require '[seihan.core :as seihan])

  (def job
    (seihan/plan {:pages 16
                  :colors #{:c :m :y :k}
                  :paper-mm [210 297]
                  :bleed-mm 3
                  :trap-mm 0.1}))

  (:plates job)
  (:findings job)
  (:imposition job)
  ```"
  (:require [kotoba.lang.text :as str]))

;; ---------------------------------------------------------------- limits（意図的な天井）

(def max-total-colors
  "プロセス + スポットの合計上限。8 胴の枚葉オフセットを想定した現実的な床。"
  8)

(def max-spot-colors
  "スポット色だけの上限。CMYK 4 色を除いた追加ユニット。"
  4)

(def max-bleed-mm
  "これより大きい bleed は通すが警告する。実務の常識域は 2〜5mm。"
  20.0)

(def max-trap-mm
  "これより大きい trap は通すが警告する。オフセットの実務域は 0.05〜0.3mm。"
  1.0)

(def process-order
  "刷り順の既定。シアン→マゼンタ→イエロー→スミ（K）。スポットは後段。"
  [:c :m :y :k])

(def process-labels
  {:c "シアン（C）"
   :m "マゼンタ（M）"
   :y "イエロー（Y）"
   :k "スミ（K）"})

(def process-aliases
  "入口で受け付ける別名 → 正規プロセス色。"
  {:c :c :cyan :c :C :c
   :m :m :magenta :m :M :m
   :y :y :yellow :y :Y :y
   :k :k :black :k :key :k :K :k})

(def default-job
  {:pages nil
   :colors #{}
   :paper-mm nil
   :bleed-mm 3.0
   :trap-mm 0.1
   :binding :saddle-stitch
   :press-sheet-mm nil})

;; ---------------------------------------------------------------- helpers

(defn- numberish? [x]
  (and (number? x) (not #?(:clj (Double/isNaN (double x))
                           :cljs (js/Number.isNaN x)))))

(defn- nonneg? [x]
  (and (numberish? x) (>= (double x) 0.0)))

(defn- pos-num? [x]
  (and (numberish? x) (pos? (double x))))

(defn- positive-int? [x]
  (and (integer? x) (pos? x)))

(defn- fmt
  "表示用の短い小数。"
  [x]
  (let [v (double x)
        r (/ (Math/round (* v 1000.0)) 1000.0)]
    (if (= r (double (long r)))
      (str (long r))
      (str r))))

;; ---------------------------------------------------------------- color normalization

(defn- spot-id
  "スポット色の安定 id。キーワード / 文字列 / {:spot …} を受け付ける。"
  [c]
  (cond
    (and (map? c) (contains? c :spot))
    (let [s (str (:spot c))]
      (keyword "spot" (str/replace (str/lower s) #"[^a-z0-9]+" "-")))

    (keyword? c)
    (let [ns (namespace c)
          nm (name c)]
      (cond
        (= "spot" ns) c
        (or (= "spot" nm) (str/starts-with? nm "spot"))
        (keyword "spot" (str/replace nm #"(?i)^spot[-_]?" ""))
        :else nil))

    (string? c)
    (let [s (str/trim c)
          low (str/lower s)]
      (cond
        (contains? #{"c" "cyan" "m" "magenta" "y" "yellow" "k" "black" "key"} low)
        nil
        (str/starts-with? low "spot")
        (keyword "spot" (str/replace low #"(?i)^spot[-_/:\s]*" ""))
        :else
        (keyword "spot" (str/replace low #"[^a-z0-9]+" "-"))))

    :else nil))

(defn normalize-color
  "1 色を `{:id :kind :label}` に正規化する。認識できなければ nil。"
  [c]
  (cond
    (nil? c) nil

    (contains? process-aliases c)
    (let [id (get process-aliases c)]
      {:id id :kind :process :label (get process-labels id)})

    (and (keyword? c) (contains? process-aliases (keyword (name c))))
    (let [id (get process-aliases (keyword (name c)))]
      {:id id :kind :process :label (get process-labels id)})

    (and (string? c)
         (contains? #{"c" "cyan" "m" "magenta" "y" "yellow" "k" "black" "key"}
                    (str/lower (str/trim c))))
    (let [id (get process-aliases (keyword (str/lower (str/trim c))))]
      {:id id :kind :process :label (get process-labels id)})

    :else
    (when-let [id (spot-id c)]
      (let [label (if (and (map? c) (:spot c))
                    (str "スポット " (:spot c))
                    (str "スポット " (name id)))]
        {:id id :kind :spot :label label}))))

(defn normalize-colors
  "色集合を安定した順序のベクトルに正規化する。プロセスは CMYK 順、スポットは
  名前順。重複は落とす。"
  [colors]
  (let [xs (cond
             (nil? colors) []
             (set? colors) (vec colors)
             (sequential? colors) (vec colors)
             :else [colors])
        normalized (keep normalize-color xs)
        by-id (into {} (map (juxt :id identity) normalized))
        process (keep by-id process-order)
        spots (->> (vals by-id)
                   (filter #(= :spot (:kind %)))
                   (sort-by (comp name :id)))]
    (vec (concat process spots))))

;; ---------------------------------------------------------------- findings

(defn- finding
  ([kind note] (finding kind note true))
  ([kind note blocking?]
   {:kind kind :blocking? (boolean blocking?) :note note}))

(defn validate
  "job の入力検査。blocking な所見は刷れない／計画できないものだけ。"
  [{:keys [pages colors paper-mm bleed-mm trap-mm] :as job}]
  (let [cols (normalize-colors colors)
        process-n (count (filter #(= :process (:kind %)) cols))
        spot-n (count (filter #(= :spot (:kind %)) cols))
        total (count cols)
        [pw ph] (when (sequential? paper-mm) (vec paper-mm))
        out []]
    (cond-> out
      (nil? pages)
      (conj (finding :missing-pages
                     "ページ数が無い。:pages に正の整数を渡す"))

      (and (some? pages) (not (positive-int? pages)))
      (conj (finding :zero-pages
                     (str "ページ数が正の整数でない（得た値: " (pr-str pages) "）。"
                          "0 ページや負のページでは版を組めない")))

      (nil? paper-mm)
      (conj (finding :missing-paper-size
                     "用紙（仕上がり）寸法が無い。:paper-mm [width height] を mm で渡す"))

      (and (some? paper-mm)
           (or (not (sequential? paper-mm))
               (not= 2 (count (take 3 paper-mm)))))
      (conj (finding :invalid-paper-size
                     (str "用紙寸法は [width-mm height-mm] の 2 要素。得た値: "
                          (pr-str paper-mm))))

      (and (sequential? paper-mm)
           (= 2 (count (take 3 paper-mm)))
           (or (not (pos-num? pw)) (not (pos-num? ph))))
      (conj (finding :invalid-paper-size
                     (str "用紙寸法の幅・高さは正の数（mm）。得た値: "
                          (pr-str paper-mm))))

      (and (some? bleed-mm) (not (nonneg? bleed-mm)))
      (conj (finding :negative-bleed
                     (str "bleed（裁ち落とし）が負（" (pr-str bleed-mm)
                          "）。負の余白は幾何として成立しない")))

      (and (some? trap-mm) (not (nonneg? trap-mm)))
      (conj (finding :negative-trap
                     (str "trap（トラップ／重ね代）が負（" (pr-str trap-mm)
                          "）。負の重ね代は定義できない")))

      (empty? cols)
      (conj (finding :no-colors
                     "色が 1 つも無い。:colors に :c/:m/:y/:k またはスポットを渡す"))

      (> total max-total-colors)
      (conj (finding :too-many-colors
                     (str "色数が上限 " max-total-colors " を超えている（"
                          total " 色）。枚葉オフセット 8 胴想定。"
                          "工程を分版するか特色を減らす")))

      (> spot-n max-spot-colors)
      (conj (finding :too-many-spot-colors
                     (str "スポット色が上限 " max-spot-colors " を超えている（"
                          spot-n " 色）。プロセス 4 + スポット "
                          max-spot-colors " がこの decision core の天井")))

      (and (nonneg? bleed-mm) (> (double bleed-mm) max-bleed-mm))
      (conj (finding :large-bleed
                     (str "bleed " (fmt bleed-mm) "mm は実務の常識域（〜"
                          (fmt max-bleed-mm) "mm）を超える。意図を確認する")
                     false))

      (and (nonneg? trap-mm) (> (double trap-mm) max-trap-mm))
      (conj (finding :large-trap
                     (str "trap " (fmt trap-mm) "mm は実務の常識域（〜"
                          (fmt max-trap-mm) "mm）を超える。見当が甘い現場向けか確認")
                     false))

      (and (positive-int? pages)
           (= :saddle-stitch (get job :binding :saddle-stitch))
           (pos? (mod pages 4)))
      (conj (finding :pages-not-multiple-of-4
                     (str "中綴じ（saddle-stitch）ではページ数は 4 の倍数が必要（得た値: "
                          pages "）。白紙を足すか無線綴じに切り替える")
                     false))

      (and (pos? process-n) (zero? (count (filter #(= :k (:id %)) cols)))
           (>= process-n 1)
           (some #{:c :m :y} (map :id cols)))
      (conj (finding :missing-process-black
                     "プロセス色にスミ（K）が無い。本文テキストがある仕事では黒版を足すことが多い"
                     false)))))

(defn blocking?
  "刷る前に必ず人が見るべき所見か。止めるべきものだけを止める。"
  [{:keys [blocking? kind]}]
  (if (some? blocking?)
    (boolean blocking?)
    (contains? #{:missing-pages :zero-pages :missing-paper-size
                 :invalid-paper-size :negative-bleed :negative-trap
                 :no-colors :too-many-colors :too-many-spot-colors}
               kind)))

;; ---------------------------------------------------------------- plates

(defn- plate-for
  "正規化済み 1 色 → 版レコード。"
  [order trap-mm {:keys [id kind label]}]
  {:id id
   :kind kind
   :label label
   :order order
   :trap-mm (double trap-mm)
   :process? (= :process kind)
   :spot? (= :spot kind)})

(defn build-plates
  "色 → 版一式。プロセスは CMYK 順、スポットは名前順で order を振る。"
  [normalized-colors trap-mm]
  (mapv (fn [i c] (plate-for i trap-mm c))
        (range)
        normalized-colors))

;; ---------------------------------------------------------------- imposition

(defn- floor-div [a b]
  (long (Math/floor (/ (double a) (double b)))))

(defn- n-up
  "press sheet に trim+bleed のページが何面載るか（単純格子）。回転なし。"
  [[sw sh] [pw ph]]
  (let [nx (max 0 (floor-div sw pw))
        ny (max 0 (floor-div sh ph))]
    {:across nx
     :down ny
     :pages-per-sheet (* nx ny)
     :orientation :as-is}))

(defn build-imposition
  "面付けの決定核。press-sheet が無ければ 1 ページ = 1 面（単ページ刷り）とみなす。

  返り値は幾何そのものではなく**計画**（何面取りか、何署名か）。RIP / 面付け機が
  実際の折り丁を組む前提で、ここでは成立条件だけを固定する。"
  [{:keys [pages paper-mm bleed-mm binding press-sheet-mm]}]
  (let [binding (or binding :saddle-stitch)
        bleed (double (or bleed-mm 0.0))
        [tw th] (mapv double paper-mm)
        with-bleed [(+ tw (* 2.0 bleed)) (+ th (* 2.0 bleed))]
        base {:pages pages
              :binding binding
              :trim-mm [tw th]
              :bleed-mm bleed
              :with-bleed-mm with-bleed
              :press-sheet-mm press-sheet-mm}
        sig-size (case binding
                   :saddle-stitch 4
                   :perfect-bound 4
                   :flat 1
                   1)
        signatures (when (and (positive-int? pages) (pos? sig-size))
                     (long (Math/ceil (/ (double pages) (double sig-size)))))]
    (if (and (sequential? press-sheet-mm)
             (= 2 (count press-sheet-mm))
             (every? pos-num? press-sheet-mm))
      (let [sheet (mapv double press-sheet-mm)
            grid (n-up sheet with-bleed)
            pps (max 1 (:pages-per-sheet grid))]
        (merge base
               {:mode :sheet
                :n-up grid
                :pages-per-sheet pps
                :sheets (long (Math/ceil (/ (double pages) (double pps))))
                :signature-size sig-size
                :signatures signatures}))
      (merge base
             {:mode :single-page
              :n-up {:across 1 :down 1 :pages-per-sheet 1 :orientation :as-is}
              :pages-per-sheet 1
              :sheets pages
              :signature-size sig-size
              :signatures signatures}))))

;; ---------------------------------------------------------------- plan

(defn plan
  "商業紙印刷の製版計画を返す純関数。

  入力（job map）:

  ```clojure
  {:pages          16                 ; 必須・正の整数
   :colors         #{:c :m :y :k}     ; 必須。:spot/gold や {:spot \"Pantone 185 C\"} 可
   :paper-mm       [210 297]          ; 必須・仕上がり寸法 mm
   :bleed-mm       3                  ; 既定 3
   :trap-mm        0.1                ; 既定 0.1
   :binding        :saddle-stitch     ; :saddle-stitch / :perfect-bound / :flat
   :press-sheet-mm [636 939]}         ; 任意。刷版の紙（全判）mm
  ```

  出力:

  ```clojure
  {:plates      [{:id :c :label \"シアン（C）\" :kind :process :order 0 :trap-mm 0.1} …]
   :findings    [{:kind … :blocking? … :note …} …]
   :imposition  {:mode :single-page|:sheet :trim-mm … :with-bleed-mm … …}
   :spec        {…正規化済み入力…}
   :ok?         true/false}   ; blocking 所見が 0 なら true
  ```

  blocking 所見がある場合も plates / imposition は**可能な範囲で**埋める
  （止まっている理由を UI が同時に見せられるように）。完全に入力が壊れていて
  計算できない欄だけ nil / 空にする。"
  ([job] (plan job nil))
  ([job _opts]
   (let [job (merge default-job (or job {}))
         bleed (let [b (:bleed-mm job)] (if (numberish? b) (double b) b))
         trap (let [t (:trap-mm job)] (if (numberish? t) (double t) t))
         job* (assoc job :bleed-mm bleed :trap-mm trap)
         findings (validate job*)
         colors (normalize-colors (:colors job*))
         paper-ok? (and (sequential? (:paper-mm job*))
                        (= 2 (count (:paper-mm job*)))
                        (every? pos-num? (:paper-mm job*)))
         pages-ok? (positive-int? (:pages job*))
         trap-ok? (nonneg? trap)
         plates (if (and (seq colors) trap-ok?)
                  (build-plates colors (if trap-ok? trap 0.0))
                  [])
         imposition (when (and pages-ok? paper-ok?)
                      (build-imposition
                       (assoc job*
                              :bleed-mm (if (nonneg? bleed) bleed 0.0)
                              :trap-mm (if trap-ok? trap 0.0))))
         blocking (filterv blocking? findings)
         spec {:pages (:pages job*)
               :colors (mapv :id colors)
               :paper-mm (when paper-ok? (mapv double (:paper-mm job*)))
               :bleed-mm (when (nonneg? bleed) bleed)
               :trap-mm (when trap-ok? trap)
               :binding (or (:binding job*) :saddle-stitch)
               :press-sheet-mm (:press-sheet-mm job*)
               :max-total-colors max-total-colors
               :max-spot-colors max-spot-colors}]
     {:plates plates
      :findings (vec findings)
      :imposition imposition
      :spec spec
      :ok? (empty? blocking)})))

(defn summary
  "承認・監査に載せる要約。"
  [{:keys [plates findings imposition spec ok?]}]
  {:ok? ok?
   :plate-count (count plates)
   :plates (mapv #(select-keys % [:id :label :kind :order :trap-mm]) plates)
   :pages (:pages spec)
   :paper-mm (:paper-mm spec)
   :bleed-mm (:bleed-mm spec)
   :trap-mm (:trap-mm spec)
   :binding (:binding spec)
   :imposition (when imposition
                 (select-keys imposition
                              [:mode :pages :sheets :pages-per-sheet
                               :signature-size :signatures :with-bleed-mm]))
   :findings (count findings)
   :blocking (count (filter blocking? findings))})
