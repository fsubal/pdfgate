# pdfgate

Apache PDFBox ベースの PDF 検査 CLI。GraalVM native-image で単一バイナリにコンパイルされ、**JVM なしで動作**します。Web サービスにアップロードされた信頼できない PDF のバリデーションを主用途とした、poppler の高機能な代替です。

すべてのコマンドは stdout に JSON を出力し、診断メッセージは stderr に出します。

## コマンド

| コマンド | 内容 |
|---|---|
| `pdfgate info <file>` | バージョン・ページ数・メタデータ・ファイル構造（オブジェクト数、インクリメンタル更新回数、%%EOF 後のデータなど） |
| `pdfgate layers <file>` | レイヤー（Optional Content Groups）の一覧と表示状態 |
| `pdfgate forms <file>` | AcroForm フィールドツリー、XFA の有無・種別、署名フィールド数 |
| `pdfgate crypto <file>` | 暗号化方式・キー長・パーミッション、電子署名の一覧 |
| `pdfgate scan <file>` | 危険要素の列挙: JavaScript、Launch/URI/GoToR/SubmitForm アクション、埋め込みファイル、XFA、RichMedia など |
| `pdfgate validate <file> [--policy <file>]` | ポリシーに基づく総合判定 |
| `pdfgate text <file> [--pages a-b] [--max-chars n]` | テキスト抽出（pdftotext 相当） |

暗号化 PDF には各コマンド共通の `--password <pw>` を指定できます。

## Exit code

| code | 意味 |
|---|---|
| 0 | 問題なし（validate では warn のみも含む） |
| 1 | validate: ポリシー違反（deny）あり |
| 2 | 破損・パース不能・パスワード不明で解析不能 |
| 3 | 使用法エラー |

解析中の例外はすべて捕捉され、`{"error":{"code":"parse-failure",...}}` の JSON に正規化されます（exit 2）。

## 出力 JSON

各コマンドの出力は [`schemas/`](schemas/) に JSON Schema (draft 2020-12) として定義してあります。コード生成やレスポンス検証にそのまま使えます。

| コマンド | スキーマ |
|---|---|
| `info` | [`schemas/info.schema.json`](schemas/info.schema.json) |
| `layers` | [`schemas/layers.schema.json`](schemas/layers.schema.json) |
| `forms` | [`schemas/forms.schema.json`](schemas/forms.schema.json) |
| `crypto` | [`schemas/crypto.schema.json`](schemas/crypto.schema.json) |
| `scan` | [`schemas/scan.schema.json`](schemas/scan.schema.json) |
| `validate` | [`schemas/validate.schema.json`](schemas/validate.schema.json) |
| `text` | [`schemas/text.schema.json`](schemas/text.schema.json) |
| すべて (exit 2) | [`schemas/error.schema.json`](schemas/error.schema.json) |

スキーマが実際の出力と一致することは `scripts/schema_check.py` が CI で検証しています（実バイナリの出力を全コマンド分バリデートするので、実装がずれたら CI が落ちます）。

### 共通の約束

- **すべてのキーは常に存在します。** 値がない場合はキーを省略せず `null` になります（`Option` は `null` に直列化）。
- 文字列は UTF-8。日本語などの非 ASCII 文字はエスケープせずそのまま出力します。
- `message` 系のフィールドは人間向けで、文言は予告なく変わります。プログラムから分岐する場合は `code` / `kind` / `severity` を使ってください。
- exit code 3（使用法エラー、`--policy` の読み込み失敗）では stdout に JSON は出ません。診断は stderr に出ます。

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

パスワード不明の暗号化 PDF では `passwordRequired: true` になり、`version` / `pages` / `metadata` / `xmpPresent` が `null` になります（`structure` はバイトレベルの解析なので常に入ります）。`crypto` も同様です。
</details>

<details>
<summary><code>pdfgate scan</code></summary>

```json
{
  "findings": [
    { "kind": "javascript", "objectNumber": 3, "detail": null },
    { "kind": "uri-action", "objectNumber": 9, "detail": "https://example.com/exfil" }
  ],
  "counts": { "javascript": 1, "uri-action": 1 }
}
```

`kind` の取りうる値: `javascript`, `launch-action`, `uri-action`, `remote-goto`, `submit-form`, `import-data`, `movie`, `sound`, `rendition`, `file-attachment-annotation`, `richmedia`, `3d-content`, `embedded-file`, `embedded-files-name-tree`, `xfa`。

`detail` は種別ごとの補足で、`uri-action` なら遷移先 URL、`launch-action` / `remote-goto` / `submit-form` / `import-data` / `embedded-file` なら対象ファイル名が入ります。
</details>

<details>
<summary><code>pdfgate validate</code></summary>

```json
{
  "ok": false,
  "issues": [
    { "severity": "deny", "code": "javascript", "message": "1 occurrence(s)" },
    { "severity": "warn", "code": "uri-action", "message": "1 occurrence(s); e.g. https://example.com/exfil" }
  ]
}
```

`issues` は deny が先、その中では `code` 順にソートされます。`ok` は deny が 1 件もないことと同値で、exit code 0/1 と対応します。ポリシーで `allow` にした種別は `issues` に現れません。

`code` は上記の `scan` の `kind` 全種に加えて、`encrypted`, `password-protected`, `incremental-update`, `data-after-eof`, `max-pages-exceeded`, `max-objects-exceeded` を取ります。
</details>

<details>
<summary>エラー (exit 2)</summary>

```json
{ "error": { "code": "parse-failure", "message": "IOException: Page tree root must be a dictionary" } }
```

`code` は `parse-failure`（破損・パース不能）か `password-required`（パスワードなしでは解析できない）のいずれかです。なお `info` と `crypto` はパスワード不明でもエラーにせず `passwordRequired: true` を返し、`validate` は `password-protected` の issue として報告します。
</details>

## validate のポリシー

デフォルトでは JavaScript・Launch/GoToR/SubmitForm/ImportData アクション・埋め込みファイル・RichMedia・パスワード保護を **deny**、URI アクション・XFA・添付アノテーション・暗号化・インクリメンタル更新・%%EOF 後のデータを **warn** とします。`--policy` の JSON で上書きできます:

```json
{
  "maxPages": 500,
  "maxIncrementalUpdates": 0,
  "rules": { "uri-action": "deny", "xfa": "deny", "encrypted": "allow" }
}
```

`rules` の値は `deny` / `warn` / `allow`。指定しなかったキーは内蔵デフォルトが使われます。

## ビルド

必要なもの: [scala-cli](https://scala-cli.virtuslab.org/)（GraalVM は自動取得されます）

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
python3 scripts/schema_check.py ./pdfgate fixtures
```

### 完全静的リンク (musl) — scratch コンテナ向け

Linux x86_64 に限り、libc 非依存の完全静的バイナリを作れます（Releases の `pdfgate-linux-x86_64-static`）。glibc のないコンテナ（scratch / distroless / busybox / alpine）でそのまま動きます:

```sh
scala-cli --power package --assembly . -o pdfgate-assembly.jar --preamble=false
docker run --rm -v "$PWD:/work" -w /work \
  ghcr.io/graalvm/native-image-community:21-muslib \
  -jar pdfgate-assembly.jar --static --libc=musl --no-fallback -H:+AddAllCharsets \
  -o pdfgate-linux-x86_64-static
```

musl 静的イメージは GraalVM の制約で linux-amd64 のみです（aarch64 は通常の動的リンク版を使ってください）。

### なぜ patched jar が必要か

PDFBox の `PDDocument` は static initializer でレンダリング用の AWT カラースペースをウォームアップしますが、GraalVM native-image の AWT サポートは Linux 限定のため macOS でバイナリが起動しなくなります。pdfgate はレンダリングを行わないので、`tools/patch-pdfbox.scala` が ASM でこのウォームアップブロックだけをバイトコードから除去します（`RESERVE_BYTE_RANGE` / `LOG` などのフィールド初期化は保持）。これにより AWT が完全に到達不能になり、全プラットフォームで同一の挙動になります。

PDFBox を更新するときは `project.scala` と `tools/patch-pdfbox.scala` のバージョンを揃えて patch を再実行してください。patch スクリプトは `<clinit>` の構造が想定と変わった場合に失敗して知らせます。

### native-image メタデータ

`resources/META-INF/native-image/pdfgate/` の設定は GraalVM tracing agent で生成しています。依存を更新して挙動が変わった場合は再生成してください:

```sh
CFG=resources/META-INF/native-image/pdfgate
rm -rf $CFG
scala-cli run . --jvm graalvm-community:21.0.2 \
  --java-opt "-agentlib:native-image-agent=config-output-dir=$CFG" -- info fixtures/simple.pdf
# 以降のコマンドは config-merge-dir=$CFG で追記マージ（全コマンド・全フィクスチャを一巡させる）
```

JVM 実行と native 実行の JSON が一致することは `scripts/smoke.sh` が担保します。

## 組み込み例

[`examples/`](examples/) に、S3 + 隔離 Lambda での検査と Rails / ActiveStorage のアップロードバリデーションのサンプルがあります。

## 信頼できない入力に対する運用上の注意

pdfgate は例外を正規化し解析専用（レンダリングなし）ですが、意図的に細工された PDF は CPU・メモリを浪費させる可能性があります。サーバーサイドでは必ず:

- タイムアウト付きで実行する（例: `timeout 30 pdfgate validate upload.pdf`）
- メモリ上限を課す（cgroup / `ulimit -v` など）
- 可能ならサンドボックス（コンテナ、seccomp）内で実行する

`text` は `--max-chars`（デフォルト 1,000,000）で出力量を制限します。

## ライセンス

[Apache License 2.0](LICENSE)

中核となる Apache PDFBox / FontBox が Apache-2.0 であり、ネイティブバイナリはそれらを静的リンクして同梱するため、同じライセンスに揃えています。配布バイナリを再頒布する場合は、[`LICENSE`](LICENSE)・[`NOTICE`](NOTICE)・[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) を添付してください（Apache-2.0 第 4 条および MIT 依存の著作権表示要件）。同梱される依存とそのライセンスの一覧は [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) にあります。
