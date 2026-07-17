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

# 0) storyboard 生成（唯一の LLM ステージ。murakumo.cloud OpenAI 互換 gateway）
MURAKUMO_API_KEY=... nbb --classpath src bin/storyboard.cljs \
    "How INT4 quantization breaks" --out sb.edn --locales en,ja
#    token は cloud-murakumo README の `clojure -M:token issue` で mint（site-worker
#    の chat gate 用 secret。generation.murakumo.cloud の secret とは別物）。
#    オフライン検証: --mock <raw-llm-output-file>（抽出・検証パスは同一）

# 1) render（SVG frames + manifest.edn + cues.edn。PNG は optional dep）
npm install @resvg/resvg-js
nbb --classpath src bin/render.cljs examples/quantization.edn \
    --out /tmp/dougaka-vector/quantization --locale ja --png \
    [--font-dir fonts/] [--no-system-fonts]   # CI 等で font を固定する時

# 2) audio plan（cues.edn → audio-plan.edn: SFX cue 選定 + ongakuka への BGM 依頼仕様）
nbb --classpath src bin/audio_plan.cljs /tmp/dougaka-vector/quantization

# 3) assemble — 本番は ai-gftd-dougaka（ffmpeg assembler）。単機での dev 検証用に
#    非正規 driver を同梱（ffmpeg 1 呼び出しに徹する。assembly ロジックは持たない）:
nbb --classpath src bin/assemble.cljs /tmp/dougaka-vector/quantization \
    --out quantization-ja.mp4 [--bgm bgm.wav] [--sfx-dir sfx/]  # sfx/<kind>.wav

# 4) publish — aozora.app が主、作者 = 1 actor DID。self-sovereign CACAO なので
#    owner creds 不要（agent 単独実行可）。データは kotobase.net(yoro-social) に
#    自動で載る（aozora PDS の backing store）。
nbb --classpath src:../../kotoba-lang/kotobase-client/src bin/publish.cljs \
    /tmp/dougaka-vector/quantization --mp4 quantization-ja.mp4 --locale ja \
    [--handle dougaka-vector.aozora.app] [--identity .dougaka-vector/identity.edn] [--dry-run]
#    → dougaka-vector 作者の単一 did:key を load/create（.dougaka-vector/identity.edn に永続）
#      → createAccount(dougaka-vector.aozora.app) → uploadBlob(mp4)
#      → author profile(self, idempotent putRecord)
#      → その DID 配下に video post(app.aozora.embed.video, rkey=<work-slug>)
#      + catalog(app.gftd.dougakaVector.video, rkey=<work-slug>) を putRecord

# 5) youtube 連携投稿 — aozora record からの syndication（YouTube は主ではない）。
#    operator OAuth 注入（YOUTUBE_CLIENT_ID/_SECRET/_REFRESH_TOKEN）が要る。
nbb --classpath src:../../kotoba-lang/kotobase-client/src bin/youtube.cljs \
    /tmp/dougaka-vector/quantization --mp4 quantization-ja.mp4 --locale ja [--dry-run]
#    → mp4 を YouTube に upload → youtubeUrl を aozora catalog に putRecord で書き戻す
#      （aozora が canonical source、YouTube はその複製という join を張る）
```

**作者 = 1 actor DID の意味**: dougaka-vector チャンネルは **1 つの atproto author**（単一
did:key + aozora account + handle `dougaka-vector.aozora.app` + actor profile）。各 video work は
その DID 配下の **record**（`app.bsky.feed.post` + `app.gftd.dougakaVector.video`、rkey=作品 slug）
で、作品が増えれば同じ DID 配下に post が増える。author seed は `.dougaka-vector/identity.edn`
に gitignore 永続。認証は depth-1 self-mint CACAO（`kotobase.cacao`）で owner の token/grant 不要。
これは確立モデル（dougaka-actor / syosetsuka も 1 author DID + works as records）と同型。3D は
扱わない repo なので publish 経路も 2D vector 動画専用。

## Layout

| path | 役割 |
|---|---|
| `src/dougaka_vector/spec.cljc` | storyboard EDN の検証（no-throw、`{:ok? :errors}`） |
| `src/dougaka_vector/scene.cljc` | storyboard → scenegraph compile（templates: `:title-card` `:bar-chart` `:line-chart` `:flow` `:big-number` `:callout` `:custom`） |
| `src/dougaka_vector/timeline.cljc` | keyframe track sampling / frame times / SFX cue events |
| `src/dougaka_vector/ease.cljc` | easing（linear/cubic/expo/back/step） |
| `src/dougaka_vector/svg.cljc` | sampled nodes → SVG document string（`:polyline` progress 描画 / `:counter` 数値アニメ含む） |
| `src/dougaka_vector/theme.cljc` | design tokens（`:cyber-dark`） |
| `src/dougaka_vector/storyboard.cljc` | storyboard 生成の純関数部（prompt 契約 / EDN 抽出 / 検証 feedback） |
| `src/dougaka_vector/audio.cljc` | cues → audio-plan（SFX ルール / coalesce / ongakuka BGM 依頼仕様） |
| `src/dougaka_vector/publish.cljc` | publish の純関数部（work-slug/handle / profile・video・catalog record / youtube metadata） |
| `bin/publish.cljs` | nbb CLI（作品=1 DID keyring → aozora account+profile+video post+catalog、`--dry-run`） |
| `bin/youtube.cljs` | nbb CLI（aozora record → YouTube 連携投稿 → youtubeUrl を catalog に書き戻し） |
| `bin/render.cljs` | nbb CLI（frames + manifest.edn + cues.edn、`--font-dir`/`--no-system-fonts`） |
| `bin/storyboard.cljs` | nbb CLI（topic → 検証済み storyboard EDN、retry loop、`--mock`） |
| `bin/audio_plan.cljs` | nbb CLI（render 出力 dir → audio-plan.edn） |
| `bin/assemble.cljs` | nbb CLI（**非正規** dev mux driver。本番は ai-gftd-dougaka） |
| `examples/quantization.edn` | 参照スタイル再現のサンプル storyboard（6 scenes / 全 template 使用 / en+ja） |

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
