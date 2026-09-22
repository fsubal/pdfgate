# ARCHITECTURE

このドキュメントは pdfgate の設計とコードの歩き方をまとめたものです。想定読者は、**プログラミング自体は分かるが、Java/Scala のエコシステムや PDF のドメインには不案内な人**です。

pdfgate の難しさは大きく 2 系統に分かれます。**Scala 由来の難しさ**（言語機能ではなく、ビルドの流儀・ライブラリの作法・native-image という Java 界特有の事情）と、**PDF 由来の難しさ**（COS グラフ、フォント、暗号化など仕様そのものの複雑さ）です。この 2 つは絡み合って見えるので、各所で「これはどっちの話か」を明示します。詰まったときに切り分けの当たりがつくはずです。

利用者向けの使い方は [README.md](README.md)、出力 JSON の仕様は [`schemas/`](schemas/) を見てください。ここは中の人向けです。

---

## 1. 何をするものか、なぜこの構成なのか

**信頼できない PDF を、レンダリングせずに解析して JSON で返す CLI** です。Web サービスにアップロードされた PDF の検査（危険要素の検出、暗号化・署名情報、テキスト抽出など）が主用途です。

設計を決めている技術的制約は 2 つ:

1. **Apache PDFBox（Java 製）を使う。** poppler では取りにくい情報（レイヤー = OCG、AcroForm/XFA、各種アクション、暗号化辞書）を取れるのが採用理由です。→ ここから「Scala から Java ライブラリを呼ぶ」構図と、Java 由来の作法（`null`、Java コレクション）が全体に染み出します。
2. **GraalVM native-image で単一バイナリにコンパイルする。** 実行環境に JVM を要求しないため、`pdfgate` という 1 ファイルを置けば動きます。→ ここから「リフレクションを避ける」「AWT を到達不能にする」といった、後述の一連の制約が生まれます。

この 2 つが本プロジェクト特有の面倒さの発生源です。純粋な Scala の話は実はそれほど多くありません。

### 1.1 前提知識：poppler と PDFBox とは何か

このドキュメントでは比較対象として **poppler** を繰り返し引き合いに出すので、先に整理します。PDF に不案内でも困らないよう、最低限の位置づけだけ押さえてください。

PDF を読むには、バイト列を解釈して中身（ページ・テキスト・フォント・メタデータ・埋め込みオブジェクトなど）を取り出すライブラリが要ります。代表的なものが 2 つあります:

- **poppler** … C++ 製の定番 PDF ライブラリ。Linux に広く入っており、付属のコマンド `pdftotext`（テキスト抽出）・`pdfinfo`（ページ数やメタデータの表示）・`pdftoppm`（画像化）などで有名です。**レンダリングとテキスト抽出に強い**一方、レイヤーやフォーム、危険要素といった「構造の細部」を機械可読に取り出す用途には向きません。多くの環境で `pdftotext upload.pdf` のように既に使われています。
- **Apache PDFBox** … Java 製の PDF ライブラリ。poppler より**構造情報へのアクセスが豊富**で、レイヤー (OCG)、AcroForm/XFA フォーム、各種アクション、暗号化辞書、電子署名などをオブジェクト単位で読めます。pdfgate が検査ツールとしてこれを選んだ理由がここにあります。

つまり pdfgate は、ざっくり言えば **「poppler ではなく PDFBox で PDF を読み、検査に必要な情報を JSON で出すツール」** です。README で「poppler の高機能な代替」と書いているのはこの意味で、`text` サブコマンドが「pdftotext 相当」と言っているのも、poppler の `pdftotext` と同じ用途だからです。

なお poppler は本プロジェクトの依存**ではありません**。CI で `tools/poppler-compare.scala` が poppler を呼ぶのは、pdfgate の出力（ページ数やテキスト）が枯れた実装とズレていないかを**答え合わせ**するためだけで、pdfgate 本体は poppler なしで動きます。

---

## 2. リポジトリの地図

```
pdfgate/
  project.scala            ← ビルド定義（依存・コンパイラ設定・native-image 設定）
  src/
    Main.scala             ← CLI エントリポイント。引数を解釈しサブコマンドに振り分ける
    analysis/              ← PDF を解析するロジック（PDFBox を触るのはここだけ）
      Doc.scala            ←   PDF を開く/閉じる/例外を正規化する“関所”
      Analyze.scala        ←   info / layers / forms / crypto の中身
      Scan.scala           ←   scan：危険要素の列挙（COS グラフを歩く）
      Validate.scala       ←   validate：ポリシー判定 + Policy 定義
      Structure.scala      ←   バイトレベルの構造チェック（パース不能でも動く）
      Text.scala           ←   text：テキスト抽出
    output/
      Model.scala          ← 出力 JSON の“形”を定義した case class 群
    testkit/
      Fixtures.scala       ← テスト用 PDF をコードで生成する
  test/
    PdfgateSuite.scala     ← munit テスト
  tools/
    patch-pdfbox.scala     ← PDFBox jar から AWT 依存を除去する前処理（後述）
    schema-check.scala     ← 実バイナリ出力を schemas/ で検証（CI）
    poppler-compare.scala  ← 実バイナリ出力を poppler と突き合わせ（CI）
  resources/
    META-INF/native-image/ ← GraalVM 用メタデータ（reflect/resource 設定）
  schemas/                 ← 出力 JSON の JSON Schema（利用者向け契約）
  scripts/                 ← CI 用の smoke テスト（smoke.sh）
  lib/
    pdfbox-3.0.7-noawt.jar ← patch-pdfbox.scala が生成する改変版 PDFBox（git 管理外）
```

読む順番は `Main.scala` → `output/Model.scala` → `analysis/` です。入口・データの形・解析ロジックの 3 階層を押さえれば全体が見えます。

---

## 3. ビルドの流儀（Java/Scala に不案内な人向け）

Java/Scala と聞いて Maven や Gradle、sbt を思い浮かべたなら、いったん忘れてください。**このプロジェクトは [scala-cli](https://scala-cli.virtuslab.org/) を使っています。** `build.sbt` も `pom.xml` もありません。ビルド定義は `project.scala` の冒頭にあるコメント風の行に全部書かれています。

```scala
//> using scala 3.3                              // Scala のバージョン
//> using dep com.lihaoyi::upickle:4.4.3         // 依存ライブラリ
//> using packaging.graalvmArgs --no-fallback    // native-image のフラグ
```

`//> using` は**コメントではなく、scala-cli が読むビルド設定**です。ここが最初の引っかかりどころ。依存の書き方には Java/Scala エコシステム共通の作法が出ます:

- `dep org.group:artifact:version`（`:` 1 個）… **Java ライブラリ**。座標は `groupId:artifactId:version`。PDFBox など Java 製はこれ。
- `dep org.group::artifact:version`（`::` 2 個）… **Scala ライブラリ**。`::` は「この Scala バージョン用にビルドされた版を選べ」という意味で、実際には `artifact_3` のようにサフィックスが補われます。Scala ライブラリはコンパイラのメジャー版ごとに別 jar になるため、この区別が要ります。

依存は Maven Central から取得されます（Java 界の中央リポジトリ。Scala ライブラリもここに publish されます）。

日常コマンド:

```sh
scala-cli test .                                       # テスト（JVM 上、速い）
scala-cli run . -- info fixtures/simple.pdf            # JVM 上で実際に動かす
scala-cli --power package --native-image . -o pdfgate  # ネイティブバイナリを作る（数分）
```

`--power` は scala-cli の **power モード**を有効にするフラグです。

scala-cli はデフォルトでは日常的なサブコマンド（`run` / `test` / `compile` など）だけを見せ、上級者向け・非標準の機能を隠しています。`--power` はその隠された機能を解禁するスイッチで、**本プロジェクトでは `package` サブコマンド**（native バイナリや assembly jar の生成）を使うために付けています。`test` や `run` に `--power` が要らないのは、それらが最初から表に出ているコマンドだからです。要するに「バイナリを固めるビルド工程だけ power モードが要る」と覚えれば十分で、機能面での深い含意はありません（`scala-cli config power true` で常時 ON にもできますが、このリポジトリは明示的にフラグを付ける方針です）。

> **切り分けのヒント:** 開発は JVM 上の `scala-cli test` / `run` で回すのが基本です。native ビルドは遅く、かつ「native でだけ出る問題」（後述 §6）があるので、**JVM で再現するバグと native でだけ出るバグは別物**として扱ってください。前者は普通の Scala/PDFBox の問題、後者はほぼ native-image 設定の問題です。

---

## 4. データの流れ（1 リクエストの一生）

`pdfgate scan upload.pdf` を上から下へ辿ります。

```
引数 "scan upload.pdf"
      │
      ▼
Main.scala  ── decline がサブコマンドを解釈し、file と password を取り出す
      │
      ▼
Doc.withDocument(file, pw) { doc => Scan.run(doc) }
      │        ↑ ここで PDF を開き、必ず閉じ、例外を LoadResult に正規化する
      ▼
Scan.run(doc): ScanResult          ── PDFBox で解析し、結果を case class に詰める
      │
      ▼
emit(result, exitCode)             ── upickle が case class → JSON に変換して println
      │
      ▼
stdout に JSON / プロセスは exitCode で終了
```

設計の核は **「PDFBox に触るのは `analysis/` の中だけ」「対外的な出力の形は `output/Model.scala` の case class だけ」** という 2 つの分離です。`Main.scala` は PDFBox の型をほぼ知らず、`PDDocument` を右から左へ渡すだけです。おかげで「PDF ドメインの複雑さ」は `analysis/` に隔離され、他の層は PDF を知らなくても読めます。

---

## 5. 各レイヤーの責務

### 5.1 `Main.scala` — CLI の入口

CLI パーサに [decline](https://ben.kirw.in/decline/) を使っています（Scala エコシステムで定番の CLI ライブラリ）。

```scala
private val fileArg = Opts.argument[String]("file").map(File(_))
private val infoCmd = Opts.subcommand("info", "...") {
  (fileArg, passwordOpt).mapN { (file, pw) => ... }
}
```

`(fileArg, passwordOpt).mapN { (file, pw) => ... }` は「引数とオプションが両方揃ったらその値で本体を組み立てる」という decline の定型です。`<+>`（`infoCmd <+> layersCmd <+> ...`）はサブコマンドを「どれか 1 つ」に合成する演算子。この 2 つは decline の書き方として覚えれば十分で、深掘り不要です。

`Main` のもう 1 つの仕事は **exit code の確定**です:

| exit | 意味                       | どこで決まるか                                       |
| -----| -------------------------- | ---------------------------------------------------- |
| 0    | 正常                       | `emit(r, 0)`                                         |
| 1    | validate のポリシー違反    | `emit(r, if r.ok then 0 else 1)`                     |
| 2    | パース不能・パスワード不明 | `parseError` / `passwordError`                       |
| 3    | 使用法エラー               | decline のパース失敗、または `--policy` 読み込み失敗 |

### 5.2 `analysis/Doc.scala` — 例外を正規化する関所（最重要）

信頼できない PDF を扱うので、パーサはいつでも例外を投げます。それを 1 箇所に閉じ込めるのが `Doc` で、pdfgate で最も重要な設計上の一点です。

```scala
enum LoadResult:
  case Loaded(doc: PDDocument)
  case PasswordRequired
  case ParseFailed(message: String)
```

`withDocument(file, pw)(f)` が **開く → `f` を実行 → 必ず `close()` → 途中の例外を `ParseFailed` に変換**、をまとめています。ここで Java 由来の事情が 2 つ出ます:

- **リソースの明示的クローズ。** Java の `PDDocument` はネイティブ資源を握るので `finally doc.close()` が要ります（Java の try-with-resources に相当する処理を手で書いている）。
- **チェック例外と実行時例外の両方を捕まえる。** PDFBox は `IOException`（Java のチェック例外）も `RuntimeException` も投げるので両方 `catch` します。捕まえ損ねるとスタックトレースが stdout に漏れ、JSON 出力を汚します。

Either型（`Left(...)` / `Right(...)`）で成否を返しているのは Scala の作法ですが、狙いは「失敗を呼び出し側に `match` で強制的に処理させ、握りつぶしを防ぐ」ことです。ここは Scala 機能の説明というより、**untrusted input を扱う設計判断**として読んでください。

### 5.3 `analysis/Analyze.scala` — info / layers / forms / crypto

PDFBox の高レベル API を叩いて結果を case class に詰め替える、素直な層です。**PDF ドメインの語彙**がここで初登場します:

- `getDocumentInformation` … 旧来の Info 辞書（Title/Author など）。XMP メタデータとは別物で、`xmpPresent` は後者の有無。
- `getOCProperties` / OptionalContentGroups … **レイヤー**。PDF の「レイヤー」は Optional Content Group (OCG) という仕様上の呼び名で、表示 ON/OFF を持ちます。
- `getAcroForm` / XFA … **フォーム**。PDF のフォームには古い AcroForm と、XML ベースの XFA の 2 系統があり、`NeedsRendering` が立っていれば動的 XFA。ここは PDF 仕様の歴史的経緯で、Scala とは無関係の複雑さです。
- `getEncryption` / `getCurrentAccessPermission` / 署名辞書 … 暗号化方式・権限フラグ・電子署名。署名の**検証はしません**（存在と構造のみ）。

Java 連携で繰り返し出るパターンだけ挙げておきます。これは PDFBox が Java だから避けられないもので、Scala の思想ではなく**橋渡しの定型**です:

- `Option(x)` … Java メソッドは `null` を返しうるので、境界で `Option` に包んで以降 `null` を考えない。
- `.asScala` … Java コレクションを Scala コレクションに変換（`import scala.jdk.CollectionConverters.*`）。PDFBox の戻り値は Java コレクションなので頻出。

### 5.4 `analysis/Scan.scala` — COS グラフを歩く（PDF ドメインの核心）

`scan` だけ他と作りが違います。**ここは Scala が難しいのではなく、PDF が難しい**箇所なので、少し丁寧に説明します。

PDF ファイルの中身は、辞書（キー→値）・配列・数値・文字列・参照からなる**オブジェクトのグラフ**です。PDFBox ではこれを **COS**（Carousel Object System、PDF の内部データモデルの呼称）レベルの型 `COSDictionary` / `COSArray` / `COSObject` … として触れます。「高レベル API」（`PDDocument` 等）は、この生グラフの上に貼られた便利な皮です。

`scan` は高レベル APIを使わず、**グラフの入口から到達可能な全 COS オブジェクトを再帰的にたどります**:

```scala
def walk(base: COSBase, objNum: Option[Long]): Unit = base match
  case obj: COSObject      => ... // 参照は 1 度だけ辿る（visited で循環対策）
  case dict: COSDictionary => inspect(dict, objNum); 子を walk
  case arr: COSArray       => 各要素を walk
  case _ => ()
```

なぜ全走査なのか。**危険要素は仕様上あちこちに置ける**からです。JavaScript は OpenAction にも、追加アクション (AA) にも、name tree にも、フォームフィールドのアクションにも書けます。高レベル API で「フォームの JS」「ドキュメントの JS」と個別に取りに行くと必ず漏れます。グラフを一度舐めれば取りこぼしません。PDF が悪意ある入力を隠せる場所を持つ、というドメイン知識がこの設計の理由です。

`visited`（訪問済みオブジェクトの集合）は、PDF が循環参照を持ちうるための無限ループ対策です。これも PDF 側の事情。

`inspect` が各辞書を見て危険要素を判定し `Finding` を積みます。`kind` 文字列（`javascript`, `uri-action`, …）は README と `schemas/scan.schema.json` の enum に対応する**対外契約**なので、うかつに変えないでください。

### 5.5 `analysis/Validate.scala` — ポリシー判定

`Policy` は「上限値（ページ数・オブジェクト数・インクリメンタル更新回数）」と「`kind → deny/warn/allow` のルール表」を持つ case class です。`Validate.run` は:

1. `Structure`（バイトレベル解析、§5.6）を見て incremental-update / data-after-eof を issue に。
2. PDF が開けたら、上限超過・暗号化・`Scan.run` の結果を issue に。
3. `deny` が 1 件でもあれば `ok = false`（= exit 1）。

`--policy foo.json` による部分上書きは、upickle が JSON を `Policy` に読み込むことで実現しています。

### 5.6 `analysis/Structure.scala` — パースできなくても動く検査

ここだけ PDFBox を使わず、**生バイトを自前で走査**します。`%PDF-` ヘッダ位置、`%%EOF` の個数（= インクリメンタル更新回数の目安）、EOF 後の余分なデータなどを、PDFBox で開けないほど壊れたファイルからも取れます。「パースできないPDFからも取れる情報を取る」ための工夫です。

### 5.7 `output/Model.scala` — 出力の形と upickle の作法

出力 JSON に対応する `case class` が集約されています。

```scala
case class Layer(name: String, enabled: Boolean) derives ReadWriter
```

`derives ReadWriter` が Scala エコシステム特有の勘所です。[upickle](https://github.com/com-lihaoyi/upickle)（このプロジェクトの JSON ライブラリ）に「この class ⇄ JSON の変換を生成させる」指定で、**コンパイル時にマクロで変換コードを生成します**。実行時リフレクションを使わないのがポイントで、これが native-image と相性が良い理由です（§6 で効いてきます）。Java 界で一般的な Jackson 等はリフレクション前提なので native-image で追加設定が要る——それを避けるための選択、という文脈で理解してください。

**契約上の要点:** フィールドが `Option[T]` だと、値が無いとき JSON では `null` になります（キー自体は消えません）。README の「すべてのキーが常に存在し、無い値は `null`」はこれに由来します。case class のフィールド追加・リネームは**そのまま JSON の破壊的変更**なので、`schemas/` の更新と `tools/schema-check.scala` の通過をセットで考えてください。

---

## 6. native-image まわりの罠（Java 界特有の異物）

pdfgate で一番壊しやすいのはここです。そして **これは Scala の話ではなく、GraalVM native-image という「Java バイトコードを AOT コンパイルして単体バイナリにする」仕組み特有の事情**です。native-image は「実行時に何が使われるか」を静的に決めきれない要素（リフレクション、動的リソース読み込み、一部のネイティブ連携）を苦手とします。

### 6.1 なぜ改変版 PDFBox（noawt jar）が要るのか

**AWT**（Abstract Window Toolkit）とは、Java 標準ライブラリに含まれる GUI・画像処理まわりのパッケージ群（`java.awt.*`）です。ウィンドウ描画やラスタ画像・カラースペースの操作を担い、PDF を**画像にレンダリング**する処理はこれに依存します。pdfgate はレンダリングをしないので、本来 AWT は要りません。ところが AWT は OS のグラフィック機能に密結合しているため、**native-image では扱いが難しく、特に macOS 向けビルドではサポートされていません**。だから「AWT がバイナリに紛れ込むこと」自体を避ける必要があります。

PDFBox の `PDDocument` は static initializer（クラスが最初に使われる際に一度だけ走る初期化ブロック）で **AWT のカラースペースをウォームアップ**します（本来はレンダリング高速化のため）。pdfgate はレンダリングしないので不要なのに、この一行があるだけで `java.awt.image` がコードから到達可能とみなされ、**native-image が macOS でビルド/起動に失敗**します（前述のとおり AWT サポートは Linux 限定のため）。使いもしない AWT が、初期化コードの都合でバイナリに引きずり込まれてしまう、という構図です。

`tools/patch-pdfbox.scala` が、ASM（Java バイトコード操作ライブラリ）でこのウォームアップブロック**だけ**を `PDDocument` の `<clinit>` から切除し、`lib/pdfbox-3.0.7-noawt.jar` を生成します。`project.scala` はこの改変版 jar を参照します。上流ライブラリのバイトコードに手を入れているので、`THIRD-PARTY-NOTICES.md` に改変の旨を明記しています（Apache-2.0 第 4 条）。

→ **PDFBox を更新するとき:** `project.scala` と `tools/patch-pdfbox.scala` のバージョンを揃え、patch を再実行。`<clinit>` の構造が変わっていれば patch スクリプトが失敗して知らせます。

### 6.2 reflect-config / resource-config と、その静かなバグ

`resources/META-INF/native-image/pdfgate/` の設定は、GraalVM の **tracing agent** が「トレース実行中に実際に触ったリフレクション・リソース」を記録して生成したものです。native-image は静的解析でこれらを見つけられないため、明示リストが要ります。

ここに罠があります:

> tracing agent は**トレース中に使われた経路しか拾いません**。トレースで踏まなかったフォントや CMap は設定から漏れ、**native バイナリでだけ実行時に失敗**します。JVM 実行では普通に動くので、通常のテストをすり抜けます。

過去に実際、日本語 PDF で使う **CMap**（文字コード → グリフの対応表。CJK フォントで多用され、`Identity-H` エンコーディングの PDF で必要）と、Helvetica 以外の **AFM**（フォントメトリクス）が漏れていて、native でだけ落ちるバグがありました。今は resource-config に `org/apache/fontbox/cmap/.*` と AFM をまとめて含めてあります。ここは PDF ドメイン（フォント/エンコーディング）と native-image の事情が交差する、最も切り分けにくい箇所です。

→ **教訓:** 依存更新や新種の PDF 対応をしたら、**native バイナリでスモークテスト**を回すこと。「JVM で通るのに native で落ちる」なら、まず §6 を疑ってください（Scala コードのバグではまずない）。

---

## 7. テストと検証（4 段構え）

| 手段                          | 何を保証するか                                                              | どこ                      |
| ----------------------------- | --------------------------------------------------------------------------- | ------------------------- |
| munit（JVM）                  | 解析ロジックの正しさ。速い。日常開発はこれ                                  | `test/PdfgateSuite.scala` |
| `scripts/smoke.sh`            | **native バイナリ**が JVM と同じ JSON を出すこと（§6 の設定漏れ検出）       | CI                        |
| `tools/schema-check.scala`    | 実バイナリ出力が `schemas/` の JSON Schema に適合すること（出力契約の維持） | CI                        |
| `tools/poppler-compare.scala` | ページ数・テキストなどが poppler と概ね一致すること                         | CI                        |

テスト PDF は外部ファイルではなく **`testkit/Fixtures.scala` がコードで生成**します（レイヤー付き・JS 付き・暗号化・日本語など）。バイト操作で壊した PDF も作れるので、エラー正規化と exit 2 も再現できます。

`smoke.sh` が「JVM 出力 == native 出力」を確認しているのは、まさに §6 の設定漏れを CI で炙り出すためです。

---

## 8. コントリビュートの実務ガイド

### 新しい危険要素の検出を足す

1. `Scan.scala` の `inspect` に判定を追加し `Finding(kind, ...)` を積む（`kind` を新設）。
2. その `kind` を `Validate.scala` の `Policy.defaultRules` に `deny`/`warn` で登録。
3. `schemas/scan.schema.json` と `schemas/validate.schema.json` の enum に `kind` を追加。
4. `Fixtures.scala` にその要素を持つ PDF を追加し、`PdfgateSuite.scala` でアサート。
5. README の一覧表・出力例も更新。

### 出力フィールドを足す/変える

- `output/Model.scala` の case class を変更 → 対応する `schemas/*.schema.json` を更新 → `tools/schema-check.scala` が通ることを確認。**JSON の破壊的変更**に直結するので慎重に。

### PDFBox を更新する

- §6.1（patch 再実行）と §6.2（native スモーク）を必ず通す。

### 依存の自動更新

- scala-cli の `//> using dep`（`project.scala`）は Dependabot も Scala Steward も素直に読めません（両者とも sbt/Maven/Gradle 前提）。依存更新は基本**手動**です。GitHub Actions のバージョン更新だけは Dependabot で自動化できます。

---

## 9. 「Scala のせい / PDF のせい / native-image のせい」早見表

詰まったときの当たり付けに。

| 症状・トピック                                   | どの領域の話か                                  | 参照        |
| -------------------------------------------------| ----------------------------------------------- | ----------- |
| `//> using` が効かない、依存が引けない           | Scala エコシステム（scala-cli / Maven Central） | §3          |
| `PDDocument` の close 忘れ、`null` が飛んでくる  | Java 連携の作法                                 | §5.2 / §5.3 |
| JSON のキーが増減した、`null` の扱い             | upickle（Scala ライブラリ）の作法               | §5.7        |
| 「危険要素が検出漏れする」「どこを探せばいい？」 | PDF ドメイン（COS グラフ・アクションの散在）    | §5.4        |
| レイヤー/フォーム/XFA/署名の意味が分からない     | PDF ドメイン（仕様の歴史的経緯）                | §5.3        |
| CMap / AFM / Identity-H / フォント解決           | PDF ドメイン ×（native では）native-image       | §6.2        |
| **JVM では動くのに native バイナリで落ちる**     | ほぼ native-image 設定（Scala バグではない）    | §6          |
| macOS で native ビルドが壊れる                   | native-image × AWT                              | §6.1        |

まず読むなら `Doc.scala`（短く、設計思想が凝縮されている）→ `Scan.scala`（PDF ドメインの核心）の順がおすすめです。
