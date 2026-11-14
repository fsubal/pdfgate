# pdfgate

Apache PDFBox をベースとした PDF 検査用 CLI ツールです。GraalVM native-image により単一の実行可能バイナリとしてコンパイルされており、**JVM なしで動作**します。Web サービス等にアップロードされた信頼できない（untrusted な）PDF の検証を主用途とした、poppler の高機能な代替ツールです。

各コマンドは解析結果の JSON を標準出力（stdout）に出力し、診断メッセージは標準エラー出力（stderr）に出力します。

## コマンド

| コマンド                                            | 内容                                                                                                             |
| --------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `pdfgate info <file>`                               | バージョン・ページ数・メタデータ・ファイル構造（オブジェクト数、インクリメンタル更新回数、%%EOF 後のデータなど） |
| `pdfgate layers <file>`                             | レイヤー（Optional Content Groups）の一覧と表示状態                                                              |
| `pdfgate forms <file>`                              | AcroForm フィールドツリー、XFA の有無・種別、署名フィールド数                                                    |
| `pdfgate crypto <file>`                             | 暗号化方式・キー長・パーミッション、電子署名の一覧                                                               |
| `pdfgate scan <file>`                               | 危険要素の列挙: JavaScript、Launch/URI/GoToR/SubmitForm アクション、埋め込みファイル、XFA、RichMedia など        |
| `pdfgate validate <file> [--policy <file>]`         | ポリシーに基づく総合判定                                                                                         |
| `pdfgate text <file> [--pages a-b] [--max-chars n]` | テキスト抽出（pdftotext 相当）                                                                                   |

暗号化された PDF を扱う場合は、全コマンド共通のオプション `--password <pw>` でパスワードを指定できます。

## Exit code

| code | 意味                                                                    |
| ---- | ----------------------------------------------------------------------- |
| 0    | 正常終了（validate における warn のみの場合も含む）                     |
| 1    | validate: ポリシー違反（deny）の検出                                    |
| 2    | 解析不能（ファイルの破損、パース失敗、パスワード未指定など）            |
| 3    | CLI の使用法エラー（無効な引数、`--policy` ファイルの読み込み失敗など） |

解析中に発生した例外はすべて内部で捕捉され、`{"error":{"code":"parse-failure",...}}` という形式の JSON に正規化して出力されます（exit code 2）。

## 出力 JSON

各コマンドの出力仕様は、[`schemas/`](schemas/) 配下に JSON Schema (draft 2020-12) として定義されています。API クライアントのコード生成やレスポンス検証にそのまま利用できます。

| コマンド        | スキーマ                                                       |
| --------------- | -------------------------------------------------------------- |
| `info`          | [`schemas/info.schema.json`](schemas/info.schema.json)         |
| `layers`        | [`schemas/layers.schema.json`](schemas/layers.schema.json)     |
| `forms`         | [`schemas/forms.schema.json`](schemas/forms.schema.json)       |
| `crypto`        | [`schemas/crypto.schema.json`](schemas/crypto.schema.json)     |
| `scan`          | [`schemas/scan.schema.json`](schemas/scan.schema.json)         |
| `validate`      | [`schemas/validate.schema.json`](schemas/validate.schema.json) |
| `text`          | [`schemas/text.schema.json`](schemas/text.schema.json)         |
| すべて (exit 2) | [`schemas/error.schema.json`](schemas/error.schema.json)       |

実際の出力とスキーマの整合性は、CI 上で `tools/schema-check.scala` により検証されています。実バイナリを用いて全コマンドの出力をバリデートしているため、実装とスキーマに不整合が生じた場合は CI が失敗します。

### JSON 出力の共通規約

- **すべてのキーが常に存在します。** 値が存在しない場合でもキーは省略されず、値として `null` が入ります（Scala の `Option` は `null` にシリアライズされます）。
- **文字コードは UTF-8 です。** 日本語などの非 ASCII 文字も Unicode エスケープせず、そのまま出力されます。
- **`message` 系のフィールドは人間向けの文字列です。** 文言は予告なく変更される可能性があります。プログラム側で条件分岐を行う場合は、`code`、`kind`、`severity` を参照してください。
- **exit code 3 では JSON は出力されません。** 引数の誤りや `--policy` の読み込み失敗などの使用法エラー時は、stderr に診断メッセージのみを出力します。

### 出力例

<details>
<summary><code>pdfgate info</code></summary>

```json
{
  "file": "upload.pdf",
  "version": "1.6",
  "pages": 1,
  "encrypted": false,
  "passwordRequired": false,
  "metadata": {
    "title": "日本語タイトル（検証用）",
    "author": null,
    "subject": null,
    "keywords": null,
    "creator": null,
    "producer": null,
    "creationDate": null,
    "modificationDate": null
  },
  "xmpPresent": false,
  "structure": {
    "fileSizeBytes": 2065,
    "headerVersion": "1.6",
    "bytesBeforeHeader": 0,
    "eofMarkers": 1,
    "incrementalUpdates": 0,
    "bytesAfterLastEof": 0,
    "objectCount": 15
  }
}
```

パスワード未指定の暗号化 PDF の場合、`passwordRequired: true` となり、`version`、`pages`、`metadata`、`xmpPresent` は `null` になります（`structure` は生バイト列の解析結果であるため、常に値が入ります）。`crypto` コマンドも同様の挙動です。

</details>

<details>
<summary><code>pdfgate scan</code></summary>

```json
{
  "findings": [
    { "kind": "javascript", "objectNumber": 3, "detail": null },
    {
      "kind": "uri-action",
      "objectNumber": 9,
      "detail": "https://example.com/exfil"
    }
  ],
  "counts": { "javascript": 1, "uri-action": 1 }
}
```

`kind` の取りうる値: `javascript`, `launch-action`, `uri-action`, `remote-goto`, `submit-form`, `import-data`, `movie`, `sound`, `rendition`, `file-attachment-annotation`, `richmedia`, `3d-content`, `embedded-file`, `embedded-files-name-tree`, `xfa`。

`detail` は検出項目ごとの補足情報です。`uri-action` では遷移先 URL が、`launch-action` / `remote-goto` / `submit-form` / `import-data` / `embedded-file` では対象のファイル名が入ります。

</details>

<details>
<summary><code>pdfgate validate</code></summary>

```json
{
  "ok": false,
  "issues": [
    { "severity": "deny", "code": "javascript", "message": "1 occurrence(s)" },
    {
      "severity": "warn",
      "code": "uri-action",
      "message": "1 occurrence(s); e.g. https://example.com/exfil"
    }
  ]
}
```

`issues` は `deny` が先頭に並び、同一 severity 内では `code` 順にソートされます。`ok` は `deny` が 1 件も存在しない場合に `true` となり、exit code（0: 正常 / 1: ポリシー違反）と連動します。なお、ポリシーで `allow` に設定した項目は `issues` に出力されません。

`code` には、前述した `scan` の全 `kind` に加え、`encrypted`、`password-protected`、`incremental-update`、`data-after-eof`、`max-pages-exceeded`、`max-objects-exceeded` が設定されます。

</details>

<details>
<summary>エラー (exit 2)</summary>

```json
{
  "error": {
    "code": "parse-failure",
    "message": "IOException: Page tree root must be a dictionary"
  }
}
```

`code` は `parse-failure`（破損やパース失敗）または `password-required`（パスワードが必要）のいずれかです。なお、`info` と `crypto` コマンドはパスワードが指定されていなくてもエラー終了（exit 2）とはせず、`passwordRequired: true` の JSON を返します。一方、`validate` は `password-protected` を検出した issue として報告します。

</details>

## validate のポリシー

デフォルトのポリシーでは、JavaScript、各種アクション（Launch/GoToR/SubmitForm/ImportData）、埋め込みファイル、RichMedia、パスワード保護を **deny** と判定します。また、URI アクション、XFA、添付ファイルのアノテーション、暗号化、インクリメンタル更新、%%EOF 後の余剰データは **warn** とします。

これらの判定ルールは、`--policy` に JSON ファイルを指定することでカスタマイズできます。

```json
{
  "maxPages": 500,
  "maxIncrementalUpdates": 0,
  "rules": { "uri-action": "deny", "xfa": "deny", "encrypted": "allow" }
}
```

`rules` に指定可能な値は `deny` / `warn` / `allow` のいずれかです。JSON で指定しなかったキーには、内蔵のデフォルト値が適用されます。

## ビルド

必要なもの: [scala-cli](https://scala-cli.virtuslab.org/)（GraalVM はビルド時に自動で取得されます）

```sh
# 1. AWT 依存を除去した patched PDFBox jar を生成（初回と PDFBox 更新時のみ）
scala-cli run tools/patch-pdfbox.scala

# 2. テスト
scala-cli test .

# 3. ネイティブバイナリ
scala-cli --power package --native-image . -o pdfgate

# 4. ネイティブバイナリのスモークテスト
scripts/smoke.sh ./pdfgate

# 5. 出力が schemas/ の JSON Schema に適合することの検証（要 pip install jsonschema）
scala-cli run . --main-class pdfgate.testkit.GenFixtures -- fixtures
scala-cli run tools/schema-check.scala -- ./pdfgate fixtures
```

### 完全静的リンク (musl) — scratch コンテナ向け

Linux x86_64 環境では、libc に依存しない完全静的リンクバイナリをビルドできます（Releases で配布している `pdfgate-linux-x86_64-static` に相当）。glibc のない軽量コンテナ環境（scratch, distroless, busybox, alpine など）でもそのまま実行可能です。

```sh
scala-cli --power package --assembly . -o pdfgate-assembly.jar --preamble=false
docker run --rm -v "$PWD:/work" -w /work \
  ghcr.io/graalvm/native-image-community:21-muslib \
  -jar pdfgate-assembly.jar --static --libc=musl --no-fallback -H:+AddAllCharsets \
  -o pdfgate-linux-x86_64-static
```

※ GraalVM の制約により、musl による静的バイナリ生成は linux-amd64 のみに対応しています（aarch64 環境では通常の動的リンク版をご利用ください）。

### なぜ patched jar が必要か

PDFBox の `PDDocument` クラスは、static initializer 内でレンダリング用の AWT カラースペースをウォームアップ（事前初期化）します。しかし、GraalVM native-image の AWT サポートは Linux 環境に限定されているため、このままコンパイルすると macOS 上でバイナリが起動できなくなります。

pdfgate はレンダリングを行わないため、`tools/patch-pdfbox.scala` を使って ASM により該当の初期化ブロックだけをバイトコードから切除しています（`RESERVE_BYTE_RANGE` や `LOG` などのフィールド初期化はそのまま保持されます）。これにより AWT への参照が完全に到達不能となり、全プラットフォームで統一された動作を実現しています。

PDFBox のバージョンを更新する際は、`project.scala` と `tools/patch-pdfbox.scala` のバージョンを揃えた上で patch スクリプトを再実行してください。もし `<clinit>` のバイトコード構造が想定と異なっていた場合は、スクリプトがエラーを報告して停止します。

### native-image メタデータ

`resources/META-INF/native-image/pdfgate/` 配下の設定ファイルは、GraalVM tracing agent を用いて生成しています。依存ライブラリの更新などで動作が変わった場合は、以下の手順で設定を再生成してください。

```sh
CFG=resources/META-INF/native-image/pdfgate
rm -rf $CFG
scala-cli run . --jvm graalvm-community:21.0.2 \
  --java-opt "-agentlib:native-image-agent=config-output-dir=$CFG" -- info fixtures/simple.pdf
# 以降のコマンドは config-merge-dir=$CFG で追記マージ（全コマンド・全フィクスチャを一巡させる）
```

JVM 実行時と native バイナリ実行時で出力 JSON が一致することは、`scripts/smoke.sh` によるテストで担保されています。

## 組み込み例

[`examples/`](examples/) に、S3 と隔離環境（AWS Lambda）を組み合わせた検査アーキテクチャや、Rails (ActiveStorage) でのアップロード検証の実装例を用意しています。

## 信頼できない入力に対する運用上の注意

pdfgate はレンダリングを行わず解析に特化しており、内部エラーも安全に正規化されます。しかし、意図的に細工された PDF を処理させた場合、CPU やメモリを過度に浪費させられるリスクは排除できません。そのため、サーバーサイドで運用する際は必ず以下の対策を実施してください。

- タイムアウトを設定して実行する（例: `timeout 30 pdfgate validate upload.pdf`）
- メモリ使用量の上限を設ける（cgroup、`ulimit -v` など）
- 可能な限り隔離されたサンドボックス環境（コンテナ、seccomp など）で実行する

なお、`text` コマンドには `--max-chars`（デフォルト 1,000,000 文字）が用意されており、過度なテキスト出力量を制限できます。

## ライセンス

[Apache License 2.0](LICENSE)

中核コンポーネントである Apache PDFBox / FontBox が Apache-2.0 で公開されており、ネイティブバイナリはそれらを静的リンクして同梱するため、同一のライセンスを採用しています。

ビルド済みバイナリを再頒布する場合は、[`LICENSE`](LICENSE)、[`NOTICE`](NOTICE)、および [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) を同梱してください（Apache-2.0 第 4 条および各依存ライブラリの著作権表示要件に基づく）。同梱される全依存ライブラリとそのライセンス一覧は、[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) を参照してください。
