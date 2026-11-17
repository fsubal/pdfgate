/**
 * S3 の ObjectCreated イベントをトリガーに起動し、アップロードされた PDF を pdfgate で検査する Lambda ハンドラー（Node.js 版）。
 * 処理内容は ../lambda-s3-python/handler.py（Python 版）と同等です。
 *
 * 検査結果に応じた処理:
 *   - クリーン（問題なし）: オブジェクトに scan-status=clean タグを付与
 *   - ポリシー違反       : 隔離用バケットへ移動し、違反内容（issues の code）をタグに記録
 *   - 破損・解析不能     : 同様に隔離用バケットへ移動（安全性を検証できないファイルは通過させない）
 *
 * 本関数自体が最小権限の隔離環境として動作するよう、IAM ポリシーは必要最小限に絞り込みます:
 *   - s3:GetObject / s3:PutObjectTagging (アップロード先バケットの対象プレフィックス)
 *   - s3:PutObject / s3:PutObjectTagging (隔離バケット)
 *   - s3:DeleteObject                    (アップロード先バケットの対象プレフィックス)
 *
 * アップロードされたファイルの公開・配信処理は、scan-status=clean タグの存在を前提とします。
 */

import { spawn } from "node:child_process";
import { createWriteStream } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pipeline } from "node:stream/promises";

import {
  CopyObjectCommand,
  DeleteObjectCommand,
  GetObjectCommand,
  HeadObjectCommand,
  PutObjectTaggingCommand,
  S3Client,
} from "@aws-sdk/client-s3";

const PDFGATE = process.env.PDFGATE_PATH ?? "/opt/bin/pdfgate";
const QUARANTINE_BUCKET = process.env.QUARANTINE_BUCKET;
// 解析のタイムアウト（ミリ秒）。Lambda 関数自体のタイムアウトより短く設定すること
const SCAN_TIMEOUT_MS = Number(process.env.SCAN_TIMEOUT_MS ?? 25_000);
// 上限を超える巨大ファイルは検査前に拒否する
const MAX_BYTES = Number(process.env.MAX_BYTES ?? 50 * 1024 * 1024);
// pdfgate の stdout をメモリに溜め込む上限（validate の出力は小さいので十分な余裕がある）
const MAX_STDOUT_BYTES = 1024 * 1024;

const s3 = new S3Client({});

export const handler = async (event) => {
  if (!QUARANTINE_BUCKET) throw new Error("QUARANTINE_BUCKET is not set");

  const results = [];
  // 1 回の呼び出しで /tmp を使い回すため、レコードは逐次処理する
  for (const record of event.Records) {
    const bucket = record.s3.bucket.name;
    const key = decodeURIComponent(record.s3.object.key.replace(/\+/g, " "));
    results.push(await inspectObject(bucket, key));
  }
  return results;
};

async function inspectObject(bucket, key) {
  const head = await s3.send(new HeadObjectCommand({ Bucket: bucket, Key: key }));
  if (head.ContentLength > MAX_BYTES) {
    return quarantine(bucket, key, "too-large", String(head.ContentLength));
  }

  const dir = await mkdtemp(join(tmpdir(), "pdfgate-"));
  const local = join(dir, "upload.pdf");
  let proc;
  try {
    const obj = await s3.send(new GetObjectCommand({ Bucket: bucket, Key: key }));
    await pipeline(obj.Body, createWriteStream(local));
    proc = await runPdfgate(["validate", local], SCAN_TIMEOUT_MS);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }

  if (proc.timedOut) {
    // 処理に異常な時間がかかるファイルは、DoS 細工を疑って隔離
    return quarantine(bucket, key, "scan-timeout", `${SCAN_TIMEOUT_MS}ms`);
  }

  if (proc.exitCode === 0) {
    const report = JSON.parse(proc.stdout);
    const warns = report.issues.map((i) => i.code);
    await s3.send(
      new PutObjectTaggingCommand({
        Bucket: bucket,
        Key: key,
        Tagging: {
          TagSet: [
            { Key: "scan-status", Value: "clean" },
            { Key: "scan-warnings", Value: tagValue(warns.join(" ")) || "none" },
          ],
        },
      }),
    );
    return { key, status: "clean", warnings: warns };
  }

  if (proc.exitCode === 1) {
    const report = JSON.parse(proc.stdout);
    const denied = report.issues.filter((i) => i.severity === "deny").map((i) => i.code);
    return quarantine(bucket, key, "policy-violation", denied.join(" "));
  }

  // exit code 2（破損・解析不能）および想定外の終了コードはすべて隔離
  return quarantine(bucket, key, "unparseable", errorCode(proc));
}

/**
 * pdfgate を子プロセスとして実行する。
 * タイムアウト時は SIGKILL で確実に停止させる（細工された PDF で SIGTERM を待つ理由はない）。
 */
function runPdfgate(args, timeoutMs) {
  return new Promise((resolve, reject) => {
    const child = spawn(PDFGATE, args, { stdio: ["ignore", "pipe", "pipe"] });
    const chunks = [];
    let size = 0;
    let timedOut = false;

    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGKILL");
    }, timeoutMs);

    child.stdout.on("data", (chunk) => {
      size += chunk.length;
      if (size > MAX_STDOUT_BYTES) {
        child.kill("SIGKILL");
        return;
      }
      chunks.push(chunk);
    });
    // stderr は診断メッセージなので CloudWatch Logs にそのまま流す
    child.stderr.on("data", (chunk) => process.stderr.write(chunk));

    child.on("error", (err) => {
      clearTimeout(timer);
      reject(err);
    });
    child.on("close", (exitCode, signal) => {
      clearTimeout(timer);
      resolve({ exitCode, signal, timedOut, stdout: Buffer.concat(chunks).toString("utf8") });
    });
  });
}

/** exit code 2 の場合は error.code（parse-failure など）を、それ以外は終了状態を返す */
function errorCode(proc) {
  try {
    const code = JSON.parse(proc.stdout)?.error?.code;
    if (code) return code;
  } catch {
    // JSON でなければ終了状態で代替する
  }
  return proc.signal ? `signal ${proc.signal}` : `exit ${proc.exitCode}`;
}

/**
 * S3 のタグ値として使える文字（英数字・空白・+ - = . _ : / @）に揃え、256 文字以内に切り詰める。
 * 使えない文字を含むと PutObjectTagging / CopyObject が InvalidTag で失敗するため。
 */
function tagValue(s) {
  return s.replace(/[^\p{L}\p{N} +\-=._:/@]/gu, "_").slice(0, 256);
}

async function quarantine(bucket, key, reason, detail) {
  await s3.send(
    new CopyObjectCommand({
      Bucket: QUARANTINE_BUCKET,
      Key: `${bucket}/${key}`,
      CopySource: `${bucket}/${encodeURIComponent(key)}`,
      TaggingDirective: "REPLACE",
      Tagging: new URLSearchParams({
        "scan-status": reason,
        "scan-detail": tagValue(detail),
      }).toString(),
    }),
  );
  await s3.send(new DeleteObjectCommand({ Bucket: bucket, Key: key }));
  return { key, status: "quarantined", reason, detail };
}
