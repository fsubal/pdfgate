# サードパーティライセンス

pdfgate の配布バイナリは GraalVM native-image で生成されており、以下の依存ライブラリを**静的リンクして同梱**しています。したがってバイナリの再配布は、これらのライブラリのバイナリ再頒布にあたります。ソースコード配布・バイナリ配布のいずれの場合も、本ファイルと [`LICENSE`](LICENSE) / [`NOTICE`](NOTICE) を添付してください。

一覧は `scala-cli compile . --print-class-path` が示す実行時クラスパスに基づいており、各ライセンスは配布物の POM および jar 内の `META-INF/LICENSE` から確認したものです。

## 実行時に同梱される依存

| ライブラリ | バージョン | ライセンス |
|---|---|---|
| org.apache.pdfbox:pdfbox<sup>†</sup> | 3.0.7 | Apache-2.0 |
| org.apache.pdfbox:fontbox | 3.0.7 | Apache-2.0 |
| org.apache.pdfbox:pdfbox-io | 3.0.7 | Apache-2.0 |
| commons-logging:commons-logging | 1.3.5 | Apache-2.0 |
| com.monovore:decline | 2.5.0 | Apache-2.0 |
| org.typelevel:cats-core | 2.12.0 | MIT |
| org.typelevel:cats-kernel | 2.12.0 | MIT |
| com.lihaoyi:upickle | 4.4.3 | MIT |
| com.lihaoyi:upickle-core | 4.4.3 | MIT |
| com.lihaoyi:upickle-implicits | 4.4.3 | MIT |
| com.lihaoyi:ujson | 4.4.3 | MIT |
| com.lihaoyi:upack | 4.4.3 | MIT |
| com.lihaoyi:geny | 1.1.1 | MIT |
| org.scala-lang:scala3-library | 3.3.8 | Apache-2.0 |
| org.scala-lang:scala-library | 2.13.18 | Apache-2.0 |

<sup>†</sup> pdfbox は AWT 依存を除去した改変版 (`lib/pdfbox-3.0.7-noawt.jar`) として同梱しています。改変の内容と理由は [README](README.md#なぜ-patched-jar-が必要か) および [`tools/patch-pdfbox.scala`](tools/patch-pdfbox.scala) を参照してください。Apache-2.0 第 4 条(b)に従い、改変を行った旨をここに明示します。

## ビルド時のみ使用（バイナリには含まれない）

| ライブラリ | バージョン | ライセンス | 用途 |
|---|---|---|---|
| org.ow2.asm:asm-tree | 9.8 | BSD-3-Clause | pdfbox jar のバイトコード改変 |
| org.scalameta:munit | 1.1.1 | Apache-2.0 | テスト |
| GraalVM Community Edition | 21.0.2 | GPLv2+CPE | ネイティブビルド<sup>‡</sup> |

<sup>‡</sup> GraalVM CE は GPLv2 with Classpath Exception です。Classpath Exception により、native-image で生成したバイナリに GPL は伝播しません。

## 同梱されるデータ・フォント

pdfbox / fontbox 経由で、以下のデータがバイナリに埋め込まれます（`resources/META-INF/native-image/pdfgate/resource-config.json` 参照）。

- **Adobe Glyph List**、**Zapf Dingbats Glyph List** — Copyright 1997-2010 Adobe Systems Incorporated
- **Unicode データ** (BidiMirroring, Scripts) — Copyright 1991-2017 Unicode, Inc.
- **Liberation Sans (LiberationSans-Regular.ttf)** — SIL Open Font License 1.1
  - Digitized data copyright (c) 2010 Google Corporation, with Reserved Font Arimo, Tinos and Cousine
  - Copyright (c) 2012 Red Hat, Inc., with Reserved Font Name Liberation
  - OFL 1.1 の全文は pdfbox jar の `META-INF/LICENSE` に含まれています。OFL はフォント単体での販売を禁じますが、ソフトウェアへのバンドル再配布は許可されています。
- **Adobe 定義済み CMap** (`org/apache/fontbox/cmap/`) — CJK PDF のテキスト抽出に使用

## MIT ライセンス依存の著作権表示

MIT ライセンスは全ての複製への著作権表示の添付を要求します。該当する表示は以下のとおりです。

```
upickle, geny:
  Copyright (c) 2016 Li Haoyi (haoyi.sg@gmail.com)

cats-core, cats-kernel:
  Cats Copyright (c) 2015 Cats Contributors.
```

MIT ライセンス本文:

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
