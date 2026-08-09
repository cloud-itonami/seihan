# seihan — 紙の製版（せいはん）decision core

商業**紙**印刷の前工程（製版）を、LLM もネットワークも使わない**純 `.cljc` 関数**で計画するライブラリ。

```clojure
(require '[seihan.core :as seihan])

(def job
  (seihan/plan {:pages 16
                :colors #{:c :m :y :k}
                :paper-mm [210 297]   ; 仕上がり A4
                :bleed-mm 3
                :trap-mm 0.1}))

(:ok? job)          ; blocking 所見が無ければ true
(:plates job)       ; CMYK / スポット版の並び
(:findings job)     ; 刷る前に潰すべき問題
(:imposition job)   ; 面付け計画（裁ち落とし込み寸法・署名数）
```

## 境界 — shirohan とは別物

| | **seihan**（本 repo） | **shirohan**（sibling） |
|---|---|---|
| 媒体 | **紙**（商業印刷・オフセット／デジタル前工程） | **ガーメント**（Tシャツ等のスクリーン装飾） |
| 版の意味 | CMYK + スポットの**分版** | 濃色ボディへの**白インク下地（白版）** |
| 余白の概念 | bleed（裁ち落とし）・trap（重ね代） | choke（白版の縮み代）・knockout（白抜き） |
| 出力の核 | 版一覧 + 面付け計画 + 所見 | 白版輪郭 + スポット版 SVG フィルム |

**重ならない。** shirohan は「しろはん＝白版」のガーメント製版エンジンで、紙の商業印刷を扱わない。
seihan は紙の製版 decision core で、白インク下地や choke は持たない。両者は fleet 上の
craft 兄弟であり、平面を共有しない。

公開サービス（任意・本 wave では未着手）: `itonami.cloud/cloud-itonami/seihan/` 予定。

## 入力（job map）

```clojure
{:pages          16                 ; 必須・正の整数
 :colors         #{:c :m :y :k}     ; 必須。別名 :cyan 等、スポット :spot/gold / {:spot "Pantone 185 C"}
 :paper-mm       [210 297]          ; 必須・仕上がり寸法 mm
 :bleed-mm       3                  ; 既定 3
 :trap-mm        0.1                ; 既定 0.1
 :binding        :saddle-stitch     ; :saddle-stitch / :perfect-bound / :flat
 :press-sheet-mm [636 939]}         ; 任意。刷版の紙（全判）mm。無いと 1 ページ = 1 面
```

## 出力

| キー | 内容 |
|---|---|
| `:plates` | 版一式。プロセスは C→M→Y→K、スポットは名前順。各版に `:trap-mm` |
| `:findings` | 所見。`:blocking? true` は刷る前に必ず人が見る |
| `:imposition` | 面付け計画。`:trim-mm` / `:with-bleed-mm` / 署名数 / n-up |
| `:spec` | 正規化済み入力（監査用） |
| `:ok?` | blocking 所見が 0 なら `true` |

`seihan/summary` は承認・監査に載せる要約。`seihan/blocking?` は止めるべき所見だけを選ぶ。

## 所見（holds）

読めなかったもの・成立しない仕事は黙って落とさず、必ず `:findings` で報告する。

| 所見 | blocking | 意味 |
|---|---|---|
| `:missing-pages` / `:zero-pages` | yes | ページ数が無い / 正の整数でない |
| `:missing-paper-size` / `:invalid-paper-size` | yes | 用紙寸法が無い / 正の 2 要素でない |
| `:negative-bleed` / `:negative-trap` | yes | 負の裁ち落とし / 負の重ね代 |
| `:no-colors` | yes | 色が 1 つも無い |
| `:too-many-colors` | yes | プロセス+スポットが **8** を超える（8 胴想定） |
| `:too-many-spot-colors` | yes | スポットだけで **4** を超える |
| `:pages-not-multiple-of-4` | no | 中綴じなのに 4 の倍数でない |
| `:large-bleed` / `:large-trap` | no | 実務常識域（bleed 20mm / trap 1mm）超え |
| `:missing-process-black` | no | プロセス色にスミ（K）が無い |

## この道具が「しないこと」

- **幾何のラスタライズ / SVG フィルム出力** — Wave 0 の decision core は「何版が要るか・
  面付けは成立するか」だけを固定する。版下 SVG は後続 wave。
- **LLM 判断** — 色数の削減提案や Pantone 近似は持たない（純関数のまま）。
- **ガーメント白版** — shirohan の仕事。こちらには `:choke-mm` も underbase も無い。
- **ネットワーク** — `plan` は外部 I/O を持たない。

## ランタイム

`seihan.core` は **`clojure.string` / `clojure.set` しか引かない** 純 `.cljc`。
第一級の runtime は ClojureScript、JVM はテストハーネス専用。

```bash
clojure -M:test
```

## ライセンス

MIT. cloud-itonami fleet の一部。Copyright (c) 2026 Jun Kawasaki.
