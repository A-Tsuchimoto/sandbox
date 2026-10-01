# MarkPDF（仮）Windows 版 — 多色ハイライトができる軽量PDFビュワー（試作）

Android 版（[../pdf-viewer-android](../pdf-viewer-android/)）と同じコンセプトの Windows 版。

## 方針

| 項目 | 方針 |
| --- | --- |
| 軽さ | **.NET Framework 4.8 + WPF**（Windows 10/11 に標準搭載）。ランタイムの追加インストール不要、配布物は約 120KB |
| PDF 表示 | Windows 標準の `Windows.Data.Pdf` を使う（外部ライブラリなし） |
| 共通ロジック | 保存・文字吸着・直線の計算は `MarkPdf.Core`（.NET Standard 2.0）に分離し、Android 版と同じ仕様・同じテスト用 PDF で検証 |
| 課金 | フリーミアム（無料版は 2 色、Pro で全 8 色）。製品版は Microsoft Store の買い切り／アプリ内課金を想定（未実装） |

## 試す

`main` に push すると GitHub Actions がビルドし、Releases の
**[pdf-viewer-windows-debug](https://github.com/A-Tsuchimoto/sandbox/releases/tag/pdf-viewer-windows-debug)** に最新版を置く。

1. `MarkPDF-windows.zip` をダウンロードして展開
2. `MarkPDF.exe` を実行（初回は SmartScreen の警告が出る場合あり →「詳細情報」→「実行」）

## できること

- PDF を開く（ボタン／Ctrl+O／ドラッグ＆ドロップ／exe に PDF をドロップ）
- 縦スクロール表示。見えているページ±1 だけ描画
- **ズーム** 50%〜400%（ツールバーの −／＋、Ctrl+ホイール、Ctrl+＋／－、Ctrl+0 で 100%）
  - 拡大中は見えている部分だけ高解像度で描き直す
- **マーカー**：ドラッグで始点からまっすぐな線（水平・垂直に ±10° で吸着）。3 段階の太さ
  - **文字吸着**（ツールバーで切り替え）：近くの文字の行（縦書きは列）の中央に合わせる
  - 文字は黒いまま見える（WPF には乗算合成がないため、「色を塗る → 文字だけのインク層を重ねる」で同じ見た目を作っている）
  - ページ端までドラッグすると自動スクロール
- 8 色パレット（無料 2 色）、消しゴム、元に戻す
- ハイライトは `%LocalAppData%\MarkPDF\highlights` に自動保存（Android 版と同じ形式）
- **ハイライト付きで保存**：`元の名前_highlighted.pdf` を提案。PDF 標準のハイライト注釈として追記（Android 版と同じ）

### マウス・キーボード

| 操作 | 内容 |
| --- | --- |
| ドラッグ（ツールなし） | スクロール |
| ホイール / Shift+ホイール | 縦 / 横スクロール |
| Ctrl+ホイール | ズーム（マウス位置を中心に） |
| P / E / Esc | マーカー / 消しゴム / ツール解除 |
| 1〜8 | 色 |
| W / T | 太さ / 文字吸着の切り替え |
| Ctrl+O / Ctrl+S / Ctrl+Z | 開く / 保存 / 元に戻す |
| PageUp / PageDown / Home / End | ページ移動 |

Android 版の「移動用アナログパッド」は、PC ではホイールとドラッグで足りるため省いた。

## 構成

```
pdf-viewer-windows/
├── src/MarkPdf.Core/        UI 非依存のロジック（.NET Standard 2.0）
│   ├── Pdf/                 PDF の最小読み書きとハイライト注釈の書き出し（Android 版の移植）
│   ├── HighlightStore.cs    ハイライトの保存・Undo
│   ├── StrokeGeometry.cs    直線の吸着・当たり判定・四角形化
│   ├── TextSnap.cs          文字の行の検出と吸着
│   └── Palette.cs, SaveName.cs, DocKey.cs
├── src/MarkPdf/             WPF アプリ（.NET Framework 4.8）
│   ├── MainWindow.xaml(.cs) ツールバー・ファイル操作・ショートカット
│   ├── DocumentView.cs      ページ表示・スクロール・ズーム・マーカー操作
│   ├── PdfSource.cs         Windows.Data.Pdf のラッパー、インク層の生成
│   └── AppSettings.cs       設定（%LocalAppData%\MarkPDF）
└── tests/MarkPdf.Core.Tests xUnit（Linux でも実行可）
```

## ビルド

```sh
dotnet test tests/MarkPdf.Core.Tests
dotnet build src/MarkPdf -c Debug   # Windows 以外でもビルドは可能（EnableWindowsTargeting）
```

## 次のステップ（案）

- 実機での操作感の調整（ペン付き PC・タッチ操作を含む）
- MSIX パッケージ化と Microsoft Store の買い切り課金（`Windows.Services.Store`）
- ファイルの関連付け（PDF を「プログラムから開く」に登録）
