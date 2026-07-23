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
```

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

## 信頼できない入力に対する運用上の注意

pdfgate は例外を正規化し解析専用（レンダリングなし）ですが、意図的に細工された PDF は CPU・メモリを浪費させる可能性があります。サーバーサイドでは必ず:

- タイムアウト付きで実行する（例: `timeout 30 pdfgate validate upload.pdf`）
- メモリ上限を課す（cgroup / `ulimit -v` など）
- 可能ならサンドボックス（コンテナ、seccomp）内で実行する

`text` は `--max-chars`（デフォルト 1,000,000）で出力量を制限します。
