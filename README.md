# ai-gftd-dougaka-vector — 言語・キャラクター非依存のベクターアニメーション動画 workflow

`ai-gftd-yukkuri`（キャラ立ち絵 + 日本語TTS掛け合い）と対照的な、**モーショングラフィックス
主体**の動画生成パイプライン。参照スタイルは "Engineering Behind LLM Inference" 系チャンネル:
黒背景・ネオンアクセント・アニメーションするチャート/ダイアグラムが説明を運び、画面上の
テキストは最小限（すべて copy-id 経由で差し替え可能）。

設計 ADR: superproject `90-docs/adr/2607171800-ai-gftd-dougaka-vector-video-workflow.md`

## Pipeline

```
topic ──▶ storyboard EDN ──▶ scenegraph ──▶ SVG frames ──▶ PNG ──▶ mp4
      LLM (murakumo text)  scene.cljc     svg.cljc      resvg    ai-gftd-dougaka
      ＝創造性はここまで    ここから下は純関数（決定論的）          (ffmpeg assembler)
                                   │
                                   └─▶ cues.edn ──▶ SFX/BGM (murakumo audio / ongakuka)
```

- **言語非依存**: scene は文字列でなく **copy-id** を参照する。`:video/copy` の locale map
  （`{:en "..." :ja "..."}`）を差し替えるだけで、同一アニメーションのまま多言語版を再レンダー
  できる（`--locale ja`）。ナレーションはオプション（既定は BGM+SFX のみ）。
- **キャラクター非依存**: 立ち絵・口パク・キャラ TTS を一切持たない。視覚的アイデンティティは
  `theme.cljc` の design token（raw hex をテンプレートに書かない）。
- **決定論**: LLM の関与は storyboard EDN 生成まで。同じ storyboard + locale → バイト同一の
  フレーム列。レビュー・差分・部分再レンダーが安価。
- **既存資産の再利用**: 最終 mux は新設せず `ai-gftd-dougaka`（ffmpeg assembler）に渡す。
  BGM は `ongakuka`、SFX/ナレーションは murakumo（`cloud-murakumo`）。

## Usage

```bash
# test（nbb / 第一級 runtime は ClojureScript。JVM 不要）
nbb --classpath src:test test/run.cljs

# render（SVG frames + manifest.edn + cues.edn）
nbb --classpath src bin/render.cljs examples/quantization.edn \
    --out /tmp/dougaka-vector/quantization --locale en

# PNG まで出す場合（optional dep）
npm install @resvg/resvg-js
nbb --classpath src bin/render.cljs examples/quantization.edn \
    --out /tmp/dougaka-vector/quantization --locale ja --png

# assemble（ai-gftd-dougaka の担当領域。直接なら:）
ffmpeg -framerate 30 -i /tmp/dougaka-vector/quantization/frames/%06d.png \
       -pix_fmt yuv420p quantization-ja.mp4
```

## Layout

| path | 役割 |
|---|---|
| `src/dougaka_vector/spec.cljc` | storyboard EDN の検証（no-throw、`{:ok? :errors}`） |
| `src/dougaka_vector/scene.cljc` | storyboard → scenegraph compile（templates: `:title-card` `:bar-chart` `:callout` `:custom`） |
| `src/dougaka_vector/timeline.cljc` | keyframe track sampling / frame times / SFX cue events |
| `src/dougaka_vector/ease.cljc` | easing（linear/cubic/expo/back/step） |
| `src/dougaka_vector/svg.cljc` | sampled nodes → SVG document string |
| `src/dougaka_vector/theme.cljc` | design tokens（`:cyber-dark`） |
| `bin/render.cljs` | nbb CLI（frames + manifest.edn + cues.edn） |
| `examples/quantization.edn` | 参照スタイル再現のサンプル storyboard（en/ja 2 locale） |

## Scenegraph（中間表現）

```edn
{:sg/dur 6.0
 :sg/nodes  [{:node/id :bar-7 :node/kind :rect :node/attrs {:x 900 :y 700 :w 24 :h 0 :fill :accent}}]
 :sg/tracks [{:track/node :bar-7 :track/attr :h
              :track/keys [[0.85 0] [1.35 340]] :track/ease :out-cubic}]}
```

`:custom` template でこの生 scenegraph を直接書ける（テンプレート化前の一点物シーン用）。

## Out of scope / 決め事

- **3D は扱わない**（repo-wide rule: 3D は kami-engine stack。本 repo は 2D vector のみで、
  SVG は 3D viewport ではないため DOM/SVG 禁止則の対象外）。
- 新規 Rust / 生 JS / `.sh` を書かない（rasterize は既存 `@resvg/resvg-js` を消費するだけ）。
- ffmpeg mux ロジックをこの repo に複製しない（`ai-gftd-dougaka` が正）。
