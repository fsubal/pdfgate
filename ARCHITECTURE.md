# ARCHITECTURE

本書は、pdfgate の設計思想とコードベースの全体像をまとめたアーキテクチャドキュメントです。想定読者は、**一般的なプログラミングの経験はあるものの、Java / Scala のエコシステムや PDF の内部仕様には詳しくないエンジニア**です。

pdfgate を理解する難しさは、大きく 2 つに分類できます。1 つは **Scala / Java エコシステムに起因する難しさ**（言語構文というよりは、ビルドツールやライブラリの作法、GraalVM native-image 特有の事情）、もう 1 つは **PDF 仕様そのものに起因する難しさ**（COS グラフ、フォント、暗号化などの複雑さ）です。この 2 つは一見すると絡み合って見えやすいため、本書では「どちらに起因する話なのか」を意識して切り分けながら解説します。問題が起きたときの原因調査に役立ててください。

CLI の使い方については [README.md](README.md)、出力 JSON のスキーマ定義は [`schemas/`](schemas/) を参照してください。本書は内部実装や開発・保守に携わるコントリビューター向けの内容です。

---

## 1. 何をするものか、なぜこの構成なのか

pdfgate は、**信頼できない（untrusted な）PDF を、レンダリングを行わずに解析して JSON を返す CLI ツール**です。Web サービスにアップロードされた PDF の検査（危険要素の検出、暗号化・署名情報の確認、テキスト抽出など）を主な用途としています。

アーキテクチャを決定づけている技術的制約は、主に次の 2 点です。

1. **Java 製の Apache PDFBox を利用すること**  
   poppler では取得しにくい詳細な構造情報（レイヤー［OCG］、AcroForm / XFA、各種アクション、暗号化辞書など）を網羅できる点が選定理由です。このため、コード全体に「Scala から Java ライブラリを呼び出す」構図が生じ、Java 特有の作法（`null` の扱いや Java 標準コレクションとの相互運用など）が各所に現れます。
2. **GraalVM native-image によりスタンドアロンの単一バイナリへコンパイルすること**  
   実行環境に JVM のセットアップを要求せず、`pdfgate` バイナリを 1 つ配置するだけで動作させるための要件です。これにより、「実行時リフレクションの回避」や「AWT への参照を完全に排除する」といった、後述する一連の制約が生まれます。

本プロジェクト特有の複雑さの大部分は、この 2 つの制約に起因しています。Scala 言語自体の難解さは、それほど本質的ではありません。

### 1.1 前提知識：poppler と PDFBox の違い

本書では比較対象として **poppler** を頻繁に引き合いに出すため、あらかじめ両者の違いを整理しておきます。PDF のドメインに詳しくない方でも理解できるよう、最低限の位置づけをまとめました。

PDF ファイルを処理するには、生バイト列を解釈して内部データ（ページ、テキスト、フォント、メタデータ、埋め込みオブジェクトなど）を取り出すライブラリが必要です。代表的なものとして以下の 2 つが挙げられます。

- **poppler**: C++ 製のデファクトスタンダードな PDF ライブラリです。多くの Linux ディストリビューションに標準で導入されており、付属 CLI コマンドの `pdftotext`（テキスト抽出）、`pdfinfo`（メタデータ表示）、`pdftoppm`（ラスタライズ・画像化）などが広く使われています。**レンダリングやテキスト抽出に優れている**一方、レイヤーやフォーム、潜在的な危険要素といった「内部構造の細部」を機械可読な形で網羅的に取り出す用途には適していません。
- **Apache PDFBox**: Java 製の PDF ライブラリです。poppler と比べて**内部構造に関する API が充実**しており、レイヤー (OCG)、AcroForm / XFA フォーム、各種アクション、暗号化辞書、電子署名などをオブジェクト単位で細かく解析できます。pdfgate が検査ツールとして PDFBox を採用した理由はここにあります。

要約すると、pdfgate は **「poppler ではなく PDFBox を内部エンジンに用いることで、セキュリティ検証に必要な構造情報を JSON として抽出するツール」** です。README で「poppler の高機能な代替」と謳っているのはこの背景によるもので、`text` サブコマンドが「pdftotext 相当」としているのも、既存の `pdftotext` と同様の用途で置き換えて使えることを意味しています。

なお、poppler は本プロジェクトの依存ライブラリ**ではありません**。CI で `tools/poppler-compare.scala` が poppler を実行しているのは、pdfgate の出力結果（ページ数や抽出テキスト）が実績ある既存実装と乖離していないかを検証（答え合わせ）するためであり、pdfgate 自体は poppler なしで完結して動作します。

---

## 2. ディレクトリ構成とコードの歩き方

```
pdfgate/
  project.scala            ← ビルド定義（依存ライブラリ、コンパイラ設定、native-image 設定）
  src/
    Main.scala             ← CLI エントリポイント。引数をパースしサブコマンドへ振り分ける
    analysis/              ← PDF 解析ロジック（PDFBox に直接触れるのはこのディレクトリのみ）
      Doc.scala            ←   PDF のオープン/クローズ、例外の正規化を担う関所
      Analyze.scala        ←   info / layers / forms / crypto コマンドの実装
      Scan.scala           ←   scan: 危険要素の検出（COS グラフの走査）
      Validate.scala       ←   validate: ポリシー判定と Policy 定義
      Structure.scala      ←   生バイト列の構造チェック（パース不能な破損ファイルでも動作）
      Text.scala           ←   text: テキスト抽出
    output/
      Model.scala          ← 出力 JSON のデータ構造を定義した case class 群
    testkit/
      Fixtures.scala       ← テスト用 PDF をコードから動的に生成するヘルパー
  test/
    PdfgateSuite.scala     ← munit による単体テスト
  tools/
    patch-pdfbox.scala     ← PDFBox JAR から AWT 依存を除去する前処理スクリプト（後述）
    schema-check.scala     ← 実バイナリ出力を schemas/ で検証する CI スクリプト
    poppler-compare.scala  ← 実バイナリ出力を poppler と突き合わせて比較する CI スクリプト
  resources/
    META-INF/native-image/ ← GraalVM 向けメタデータ（リフレクションやリソースの設定）
  schemas/                 ← 出力 JSON の JSON Schema（外部向けの出力契約）
  scripts/                 ← CI 用スモークテストスクリプト（smoke.sh）
  lib/
    pdfbox-3.0.7-noawt.jar ← patch-pdfbox.scala が生成する改変版 PDFBox JAR（git 管理対象外）
```

コードを読み解く際は、**`Main.scala`（CLI の入口）→ `output/Model.scala`（出力データ構造）→ `analysis/`（解析ロジック）** の順に追うのがおすすめです。「入口」「データの形」「ドメインロジック」という 3 つの階層を押さえることで、全体のアーキテクチャが自然に見通せます。

---

## 3. ビルドの流儀（Java / Scala に不案内な人向け）

Java や Scala と聞くと Maven、Gradle、sbt といったビルドツールを思い浮かべるかもしれませんが、**本プロジェクトでは [scala-cli](https://scala-cli.virtuslab.org/) を採用しています**。そのため `build.sbt` や `pom.xml` は存在せず、ビルド定義はすべて `project.scala` の冒頭にあるディレクティブに集約されています。

```scala
//> using scala 3.3                              // Scala のバージョン
//> using dep com.lihaoyi::upickle:4.4.3         // 依存ライブラリ
//> using packaging.graalvmArgs --no-fallback    // native-image 向けのフラグ
```

`//> using` は単なるコメントではなく、**scala-cli が解釈するビルド設定（ディレクティブ）**です。初めて触れる際に引っかかりやすいポイントです。また、依存ライブラリの指定方法には、Java / Scala エコシステム特有のルールがあります。

- `dep org.group:artifact:version`（コロンが 1 つ）: **Java ライブラリ**を指定します。形式は `groupId:artifactId:version` です。PDFBox など Java 製のライブラリはこちらで記述します。
- `dep org.group::artifact:version`（コロンが 2 つ）: **Scala ライブラリ**を指定します。ダブルコロンは「現在の Scala バージョンに対応した成果物を選択する」という意味であり、内部的には `artifact_3` のようにバイナリバージョンのサフィックスが自動で補完されます。Scala ではコンパイラのメジャーバージョンごとにバイナリ互換性が分かれるため、この区別が必要です。

指定した依存ライブラリは、すべて Maven Central（Java / Scala 界隈の標準パッケージリポジトリ）から自動的にダウンロードされます。

日常的によく使うコマンド:

```sh
scala-cli test .                                       # ユニットテストの実行（JVM 上で高速に動作）
scala-cli run . -- info fixtures/simple.pdf            # JVM 上での直接実行
scala-cli --power package --native-image . -o pdfgate  # ネイティブバイナリのビルド（数分程度）
```

`--power` は、scala-cli の **power モード**（拡張機能）を有効にするフラグです。

scala-cli は、デフォルトでは日常的によく使うサブコマンド（`run`, `test`, `compile` など）のみを提供し、高度な機能や実験的な機能を隠しています。`--power` はそれらを一時的に解禁するスイッチであり、**本プロジェクトでは `package` サブコマンド（ネイティブバイナリや assembly JAR の生成）を利用するために指定しています**。日常の `test` や `run` では不要です。要するに「バイナリをパッケージングする工程でのみ `--power` が必要になる」と把握しておけば十分です（`scala-cli config power true` で常時有効化も可能ですが、本リポジトリでは環境差異を防ぐためコマンドライン引数で明示する運用にしています）。

> **切り分けのヒント:** 日常の開発サイクルは、高速に実行できる JVM 上（`scala-cli test` や `run`）で回すのが基本です。native-image によるバイナリ生成には数分単位の時間がかかる上、「native 環境特有の不具合」（後述の §6）も存在します。そのため、**JVM 上で再現する問題と、native バイナリでのみ発生する問題は明確に切り分けて調査する**必要があります。前者は Scala や PDFBox のロジックの不具合であり、後者はほぼ間違いなく native-image 向けの設定漏れです。

---

## 4. データの流れ（1 リクエストのライフサイクル）

`pdfgate scan upload.pdf` を実行した際の処理の流れを上から順に辿ります。

```
CLI 引数 "scan upload.pdf"
      │
      ▼
Main.scala  ── decline がサブコマンドをパースし、file と password を抽出
      │
      ▼
Doc.withDocument(file, pw) { doc => Scan.run(doc) }
      │        ↑ PDF をオープンし、処理後に確実にクローズ。例外を LoadResult へ正規化
      ▼
Scan.run(doc): ScanResult          ── PDFBox で解析を行い、結果を case class に格納
      │
      ▼
emit(result, exitCode)             ── upickle が case class を JSON にシリアライズして標準出力へ送出
      │
      ▼
標準出力に JSON が出力され、プロセスが exitCode で終了
```

設計の核となるのは、**「PDFBox に依存するコードを `analysis/` 内だけに閉じること」**、そして**「外部に公開する出力データ構造を `output/Model.scala` の case class に限定すること」**という 2 点の関心の分離です。

`Main.scala` は PDFBox の具体的な型をほとんど意識せず、開いた `PDDocument` を解析層へそのまま渡すだけの役割にとどまります。これにより、PDF ドメイン特有の複雑さを `analysis/` の中に完全に隔離でき、他のレイヤーは PDF の仕様に煩わされることなく見通しよく実装できます。

---

## 5. 各レイヤーの責務

### 5.1 `Main.scala` — CLI の入口

コマンドライン引数のパースには [decline](https://ben.kirw.in/decline/) を採用しています（Scala エコシステムで定評のある CLI パーサーライブラリです）。

```scala
private val fileArg = Opts.argument[String]("file").map(File(_))
private val infoCmd = Opts.subcommand("info", "...") {
  (fileArg, passwordOpt).mapN { (file, pw) => ... }
}
```

`(fileArg, passwordOpt).mapN { (file, pw) => ... }` は、「必須引数とオプションが両方揃った段階で処理を組み立てる」という decline の標準的なイディオムです。また、`<+>`（`infoCmd <+> layersCmd <+> ...`）は複数のサブコマンドを「いずれか 1 つを選択する」形に束ねる演算子です。これらは decline の構文ルールとして押さえておけば十分であり、深く掘り下げる必要はありません。

`Main` のもう 1 つの重要な役割は **exit code の確定**です。

| exit code | 意味 | 決定される箇所 |
|---|---|---|
| 0 | 正常終了 | `emit(r, 0)` |
| 1 | validate によるポリシー違反 | `emit(r, if r.ok then 0 else 1)` |
| 2 | パース不能・パスワード未指定 | `parseError` / `passwordError` |
| 3 | CLI の使用法エラー | decline のパース失敗、または `--policy` の読み込み失敗 |

### 5.2 `analysis/Doc.scala` — 例外を正規化する関所（最重要）

信頼できない外部の PDF を処理する以上、パーサーはいつでも例外を発生させる可能性があります。それらの例外を一箇所で安全に捕捉・正規化するのが `Doc` であり、pdfgate の堅牢性を担保する上で最も重要なコンポーネントです。

```scala
enum LoadResult:
  case Loaded(doc: PDDocument)
  case PasswordRequired
  case ParseFailed(message: String)
```

`withDocument(file, pw)(f)` は、**「ファイルを開く → 処理 `f` を実行 → 確実に `close()` を呼ぶ → 発生した例外を `ParseFailed` に変換する」** という一連のライフサイクルを集約しています。ここでは Java ライブラリとの連携に起因する作法が 2 つ現れます。

- **明示的なリソース解放:** Java の `PDDocument` は OS レベルのリソース（ファイルハンドルやネイティブメモリ）を保持するため、確実に `finally doc.close()` を呼ぶ必要があります（Java の try-with-resources と同等の仕組みを明示的に実装しています）。
- **checked 例外と unchecked 例外の双方の捕捉:** PDFBox は `IOException`（Java の checked 例外）だけでなく `RuntimeException`（unchecked 例外）も送出するため、両方を漏れなく `catch` します。捕捉に失敗するとスタックトレースが標準出力へ漏れ出し、出力 JSON を破損させてしまうためです。

成否を Either 型（`Left(...)` / `Right(...)`）で表現しているのは Scala の標準的なプラクティスですが、その目的は「呼び出し側に対してパターンマッチによる網羅的なエラーハンドリングを強制し、エラーの握りつぶしを防ぐこと」にあります。単なる文法上の好みの問題ではなく、**信頼できない入力を安全に処理するための防御的設計**として理解してください。

### 5.3 `analysis/Analyze.scala` — info / layers / forms / crypto

PDFBox の高レベル API を呼び出し、取得した結果を case class へ詰め替える薄いレイヤーです。ここでは以下のような **PDF 仕様特有の概念や用語** が登場します。

- `getDocumentInformation`: 旧来の Info 辞書（Title や Author など）を取得します。これは XMP メタデータとは別物であり、`xmpPresent` は後者がドキュメント内に存在するかどうかを示します。
- `getOCProperties` / `OptionalContentGroups`: **レイヤー情報**です。PDF 仕様においてレイヤーは Optional Content Group (OCG) と呼ばれ、表示状態（ON / OFF）を管理します。
- `getAcroForm` / `XFA`: **フォーム機能**です。PDF のフォームには古典的な AcroForm と、XML ベースの XFA の 2 系統が存在します。`NeedsRendering` フラグが立っている場合は動的 XFA を意味します。これらは PDF 仕様の歴史的経緯によるものであり、Scala とは無関係なドメイン固有の複雑さです。
- `getEncryption` / `getCurrentAccessPermission` / 署名辞書: 暗号化方式、アクセスパーミッション、電子署名情報を扱います。なお、署名内容自体の**検証は行わず**、存在の有無と構造の抽出のみにとどめています。

Java ライブラリとの相互運用において頻出するパターンを挙げておきます。これらは Scala 特有の思想というよりも、Java との**境界を取り持つための定型処理**です。

- `Option(x)`: Java のメソッドは戻り値として `null` を返す可能性があるため、境界部分ですぐに `Option` へラップし、以降のコードで `null` を意識しなくて済むようにします。
- `.asScala`: Java のコレクションを Scala のコレクション型へ変換します（`import scala.jdk.CollectionConverters.*`）。PDFBox の API は Java コレクションを返すため、頻繁に利用されます。

### 5.4 `analysis/Scan.scala` — COS グラフの走査（PDF ドメインの核心）

`scan` コマンドの実装は、他のコマンドとはアプローチが大きく異なります。**Scala の構文ではなく PDF の内部構造そのものの難しさに起因する**部分であるため、背景から丁寧に解説します。

PDF ファイルの実態は、辞書（キーと値のペア）、配列、数値、文字列、そして他オブジェクトへの参照から構成される**オブジェクトの有向グラフ**です。PDFBox においては、この内部構造が **COS**（Carousel Object System: PDF の内部データモデルの名称）レイヤーの型（`COSDictionary`, `COSArray`, `COSObject` など）として表現されています。普段よく目にする高レベル API（`PDDocument` など）は、この生グラフの上に被せられた扱いやすいラッパーに過ぎません。

`scan` では高レベル API を経由せず、**グラフのルートから到達可能なすべての COS オブジェクトを再帰的にトラバース（走査）します**。

```scala
def walk(base: COSBase, objNum: Option[Long]): Unit = base match
  case obj: COSObject      => ... // 参照は 1 度だけ辿る（visited で循環参照対策）
  case dict: COSDictionary => inspect(dict, objNum); 子を walk
  case arr: COSArray       => 各要素を walk
  case _ => ()
```

なぜオブジェクトグラフ全体を走査する必要があるのでしょうか。それは、**PDF 仕様上、危険な要素を埋め込める場所が極めて多岐にわたるため**です。

例えば JavaScript は、ドキュメントオープン時のアクション（OpenAction）だけでなく、ページめくり時の追加アクション（AA）、name tree、各種フォームフィールドのアクションなど、ドキュメント内の無数の場所に潜伏させることができます。高レベル API を使って「ドキュメント直下の JS」「フォームの JS」と個別に取得しようとすると、往々にして見落としが発生します。しかし、オブジェクトグラフ全体を網羅的に走査すれば、配置場所に関わらず確実に検出できます。「PDF は悪意あるコードを隠蔽できる箇所があまりに多い」というドメイン知識に基づいた設計です。

なお、実装内の `visited`（走査済みオブジェクトのセット）は、PDF 内部の循環参照によって走査が無限ループに陥るのを防ぐためのガードです。これも PDF 仕様特有の性質に由来します。

各辞書ノードを検査して危険要素を判定するのが `inspect` メソッドであり、検出時に `Finding` オブジェクトをリストに追加します。ここで出力される `kind` 文字列（`javascript`, `uri-action` など）は、README や `schemas/scan.schema.json` の enum 定義と紐づく**対外的なインターフェース契約**です。安易に変更しないよう注意してください。

### 5.5 `analysis/Validate.scala` — ポリシー判定

`Policy` は、「各種リソースの上限値（ページ数、オブジェクト数、インクリメンタル更新回数）」および「各検出項目に対する判定ポリシー（`kind → deny/warn/allow` のマッピング表）」を保持する case class です。

`Validate.run` の判定フローは以下のとおりです。

1. `Structure` によるバイトレベル解析（§5.6 参照）を行い、`incremental-update` や `data-after-eof` などの構造異常を issue として登録する。
2. PDF をパースできた場合は、上限超過、暗号化状態、および `Scan.run` で検出された危険要素を issue に追加する。
3. 蓄積された issue の中に 1 件でも `deny` が存在すれば `ok = false`（exit code 1）と判定する。

`--policy <file>` によるポリシーのカスタマイズは、upickle を用いて JSON ファイルから `Policy` クラスへデシリアライズすることで実現しています。

### 5.6 `analysis/Structure.scala` — パース不能なファイルでも動作する構造解析

このモジュールに限り、PDFBox のパーサーに頼らず**ファイルの生バイト列を直接スキャン**しています。

`%PDF-` ヘッダーの出現位置、`%%EOF` マーカーの個数（インクリメンタル更新回数の指標）、末尾の EOF 以降に付加された余剰データなどを、PDFBox がパースに失敗するような破損ファイルからでも抽出できます。「パーサーがクラッシュするような異常なファイルであっても、取得可能なメタ情報は漏らさず吸い出す」ための工夫です。

### 5.7 `output/Model.scala` — 出力データ構造と upickle の作法

出力 JSON のスキーマに対応する `case class` 群が集約されています。

```scala
case class Layer(name: String, enabled: Boolean) derives ReadWriter
```

`derives ReadWriter` は Scala 3 特有の重要な構文です。本プロジェクトで採用している JSON ライブラリ [upickle](https://github.com/com-lihaoyi/upickle) に対し、クラスと JSON の相互変換コードを**コンパイル時マクロによって自動生成させる**宣言です。

最大の利点は**実行時リフレクションを一切使わない**点にあり、これが GraalVM native-image との抜群の親和性につながっています（詳細は §6 参照）。Java エコシステムで主流の Jackson などはリフレクションを前提としているため、native-image 化にあたって膨大な設定が必要になりますが、upickle を選ぶことでその煩雑さを根本から回避しています。

**API 契約上の注意点:** フィールドを `Option[T]` と定義した場合、値が存在しない（`None`）ときは JSON 上で `null` としてシリアライズされます（キー自体は省略されずに出力されます）。README に記載されている「すべてのキーが常に存在し、値がない場合は `null` になる」という仕様は、この実装に由来するものです。したがって、case class のフィールド追加や名前変更は**外部向けの出力仕様に対する破壊的変更（breaking change）**となります。変更時は必ず `schemas/` のスキーマ更新と `tools/schema-check.scala` による検証をセットで行ってください。

---

## 6. native-image まわりの注意点（Java エコシステム特有の制約）

pdfgate の開発において、最もデバッグが難しく壊れやすいのがこの領域です。なお、**これは Scala という言語の問題ではなく、GraalVM native-image という「Java バイトコードを AOT コンパイルして単一ネイティブバイナリを生成する」仕組みに由来する固有の制約**です。native-image は静的解析に基づいてバイナリを生成するため、「実行時に何が使われるか」を事前に確定しにくい要素（リフレクション、動的なリソースロード、JNI などのネイティブ連携）の扱いに癖があります。

### 6.1 なぜ改変版 PDFBox（noawt JAR）が必要なのか

**AWT**（Abstract Window Toolkit）とは、Java 標準ライブラリに含まれる GUI・画像処理パッケージ群（`java.awt.*`）です。ウィンドウ描画やラスタ画像、カラースペースの変換処理などを担っており、PDF を**画像としてレンダリングする処理**は AWT に依存しています。
pdfgate はテキストや構造の解析のみを行いレンダリングは行わないため、本来 AWT を必要としません。しかし、AWT は OS ネイティブのグラフィック機能と強く結びついているため、**native-image におけるコンパイルが極めて難しく、特に macOS 向けビルドではサポートされていません**。したがって、AWT への参照がバイナリ内に紛れ込むこと自体を完全に阻止する必要があります。

厄介なことに、PDFBox の `PDDocument` クラスは static initializer（クラスロード時に一度だけ実行される初期化ブロック `<clinit>`）の中で、将来のレンダリングを高速化する目的で **AWT のカラースペースをウォームアップ**する処理を呼び出しています。pdfgate ではレンダリングを行わないため全く無用な処理ですが、この呼び出しが存在するだけで native-image の静的解析器から `java.awt.image` パッケージ全体が「到達可能（reachable）」と判定されてしまいます。その結果、**macOS 上でのビルドや起動に失敗する**という事態に陥ります。使ってもいない機能の初期化コードのせいで、バイナリ全体が道連れにされる構図です。

この問題を解決するため、`tools/patch-pdfbox.scala` ではバイトコード操作ライブラリ ASM を使い、`PDDocument` の `<clinit>` から該当のウォームアップ処理**のみ**を切除した改変版 JAR（`lib/pdfbox-3.0.7-noawt.jar`）を生成しています。`project.scala` はこの JAR を直接参照することで、AWT 依存を完全に断ち切っています。
なお、上流ライブラリのバイナリに直接変更を加えているため、ライセンス要件（Apache-2.0 第 4 条）に従い、改変を行った旨を `THIRD-PARTY-NOTICES.md` に明記しています。

→ **PDFBox を更新する際の留意点:** `project.scala` と `tools/patch-pdfbox.scala` に記述されたバージョン番号を揃えた上で、patch スクリプトを再実行してください。もし PDFBox 側の更新によって `<clinit>` のバイトコード構造に変化があった場合は、patch スクリプトが検知してエラーを出し、処理を中断します。

### 6.2 reflect-config / resource-config と、その潜在的リスク

`resources/META-INF/native-image/pdfgate/` に配置されている設定ファイル群は、GraalVM の **tracing agent** を使って JVM 上でアプリケーションを実行し、「実際にアクセスされたリフレクションやリソースファイル」をプロファイリングして生成したものです。native-image の静的解析ではこれらを自動検出できないため、ホワイトリストとして設定を明示しておく必要があります。

しかし、ここには重大な落とし穴があります。

> tracing agent は、**トレース実行中に実際に通過したコードパスしか記録しません**。そのため、トレース時に読み込まれなかったフォント定義や CMap などは設定から抜け落ちてしまい、**native バイナリを実行した際に初めてランタイムエラーが発生する**ことになります。JVM 上の実行ではクラスパスから動的に読み込めるため、通常のユニットテストをすり抜けてしまいます。

実際、過去の開発において、日本語 PDF で頻出する **CMap**（文字コードとグリフの対応表。CJK フォントや `Identity-H` エンコーディングで必須）や、Helvetica 以外の **AFM**（フォントのメトリクスファイル）が設定から漏れており、native バイナリでのみクラッシュするという問題が発生しました。現在はこうした事態を防ぐため、`resource-config.json` に `org/apache/fontbox/cmap/.*` や各種 AFM を正規表現で網羅的に含めています。PDF 特有の仕様（フォントと文字エンコーディング）と native-image の制約が交錯する、最もトラブルシューティングが難しい領域です。

→ **運用の教訓:** 依存ライブラリを更新した際や、未知の構造を持つ PDF に対応した際は、必ず **native バイナリを用いたスモークテスト（`scripts/smoke.sh`）を実行してください**。「JVM 上ではテストが通るのに native バイナリだと異常終了する」という場合は、Scala コードのロジックではなく、十中八九この §6 で述べた設定漏れが原因です。

---

## 7. テストと検証（4 段構え）

| 検証手段 | 保証する内容 | 実行環境 |
|---|---|---|
| munit（JVM） | 解析ロジックの正しさ（高速に動作するため、日常の開発サイクルで利用） | `test/PdfgateSuite.scala` |
| `scripts/smoke.sh` | **native バイナリ**が JVM 実行時と同一の JSON を出力すること（§6 の設定漏れの検出） | CI |
| `tools/schema-check.scala` | 実バイナリの出力が `schemas/` の JSON Schema に適合すること（出力契約の維持） | CI |
| `tools/poppler-compare.scala` | ページ数やテキストなどの抽出結果が poppler と概ね一致することの検証 | CI |

テスト用の PDF は静的なファイルを配置するのではなく、**`testkit/Fixtures.scala` 内のコードによって動的に生成**しています（レイヤー付き、JavaScript 埋め込み、暗号化、日本語文字列など）。さらに生バイト列を意図的に破損させた PDF も生成できるため、パースエラーの正規化処理や exit code 2 の挙動も網羅的にテスト可能です。

CI 上で `smoke.sh` が「JVM 実行時の JSON 出力」と「native バイナリの JSON 出力」を厳密に突き合わせているのは、まさに §6 で解説した native-image 特有の設定漏れを確実に検知するための仕掛けです。

---

## 8. コントリビューションガイド

### 危険要素の検出ルールを追加する

1. `Scan.scala` の `inspect` メソッドに判定ロジックを追加し、`Finding(kind, ...)` を生成する（新しい `kind` を定義）。
2. 追加した `kind` を `Validate.scala` の `Policy.defaultRules` に登録し、デフォルトの判定（`deny` または `warn`）を指定する。
3. `schemas/scan.schema.json` および `schemas/validate.schema.json` の enum 定義に新しい `kind` を追加する。
4. `Fixtures.scala` に該当要素を含むテスト用 PDF の生成処理を追加し、`PdfgateSuite.scala` で検出のテストケースを記述する。
5. `README.md` の危険要素一覧や出力例にも反映する。

### 出力 JSON のフィールドを追加・変更する

- `output/Model.scala` の case class を変更した後、対応する `schemas/*.schema.json` を更新し、`tools/schema-check.scala` がパスすることを確認します。出力スキーマの変更は**外部利用側にとっての破壊的変更（breaking change）**に直結するため、慎重に検討してください。

### PDFBox のバージョンを更新する

- §6.1 に従い patch スクリプトを再実行した上で、§6.2 に従って native バイナリのスモークテストを必ず通過させてください。

### 依存ライブラリの自動更新について

- `project.scala` に記述されている scala-cli の `//> using dep` ディレクティブは、Dependabot や Scala Steward が標準では対応していません（従来の sbt, Maven, Gradle を想定しているため）。そのため、ライブラリの依存更新は原則として**手動**で行う必要があります。なお、GitHub Actions のワークフローのバージョン更新については Dependabot による自動更新が有効です。

---

## 9. トラブルシューティング早見表（原因の切り分け）

問題が発生した際、どの領域に原因があるかを素早く切り分けるためのヒントです。

| 症状・トピック | 原因の所在 | 参照 |
|---|---|---|
| `//> using` が効かない、依存パッケージが解決できない | Scala エコシステム（scala-cli / Maven Central） | §3 |
| `PDDocument` のクローズ漏れ、`null` による例外 | Java 相互運用の作法 | §5.2 / §5.3 |
| JSON のキーが増減した、`null` のシリアライズ挙動 | upickle（Scala ライブラリ）の仕様 | §5.7 |
| 危険要素が検出されない、探索対象の場所が分からない | PDF ドメイン固有の仕様（COS グラフ・アクションの散在） | §5.4 |
| レイヤー / フォーム / XFA / 署名の仕様が分からない | PDF ドメイン固有の仕様（仕様の歴史的経緯） | §5.3 |
| CMap / AFM / Identity-H / フォント解決の失敗 | PDF ドメイン仕様 × native-image リソース設定 | §6.2 |
| **JVM では正常に動くのに native バイナリでクラッシュする** | ほぼ確実に native-image の設定漏れ（Scala コードの不具合ではない） | §6 |
| macOS 上で native ビルドまたは起動に失敗する | native-image × AWT の非互換性 | §6.1 |

コードリーディングを始める際は、短く設計思想が凝縮された `Doc.scala` から読み始め、続いて PDF 仕様の核心を突いている `Scan.scala` へ進むのがおすすめです。
