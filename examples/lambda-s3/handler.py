"""S3 の ObjectCreated イベントをトリガーに起動し、アップロードされた PDF を pdfgate で検査する Lambda ハンドラー。

検査結果に応じた処理:
  - クリーン（問題なし）: オブジェクトに scan-status=clean タグを付与
  - ポリシー違反       : 隔離用バケットへ移動し、違反内容（findings）をタグに記録
  - 破損・解析不能     : 同様に隔離用バケットへ移動（安全性を検証できないファイルは通過させない）

本関数自体が最小権限の隔離環境として動作するよう、IAM ポリシーは必要最小限に絞り込みます:
  - s3:GetObject / s3:PutObjectTagging (アップロード先バケットの対象プレフィックス)
  - s3:PutObject                       (隔離バケット)
  - s3:DeleteObject                    (アップロード先バケットの対象プレフィックス)

アップロードされたファイルの公開・配信処理は、scan-status=clean タグの存在を前提とします
（例: CloudFront の前段で署名付き URL を発行する認可サービスがタグを確認するなど）。
"""

import json
import os
import subprocess
import urllib.parse

import boto3

PDFGATE = "/opt/bin/pdfgate"
QUARANTINE_BUCKET = os.environ["QUARANTINE_BUCKET"]
# 解析のタイムアウト（秒）。Lambda 関数自体のタイムアウトより短く設定すること
SCAN_TIMEOUT = int(os.environ.get("SCAN_TIMEOUT", "25"))
# 上限を超える巨大ファイルは検査前に拒否する
MAX_BYTES = int(os.environ.get("MAX_BYTES", str(50 * 1024 * 1024)))

s3 = boto3.client("s3")


def lambda_handler(event, _context):
    results = []
    for record in event["Records"]:
        bucket = record["s3"]["bucket"]["name"]
        key = urllib.parse.unquote_plus(record["s3"]["object"]["key"])
        results.append(inspect_object(bucket, key))
    return results


def inspect_object(bucket: str, key: str) -> dict:
    head = s3.head_object(Bucket=bucket, Key=key)
    if head["ContentLength"] > MAX_BYTES:
        return quarantine(bucket, key, reason="too-large", detail=str(head["ContentLength"]))

    local = "/tmp/upload.pdf"
    s3.download_file(bucket, key, local)

    try:
        proc = subprocess.run(
            [PDFGATE, "validate", local],
            capture_output=True,
            text=True,
            timeout=SCAN_TIMEOUT,
        )
    except subprocess.TimeoutExpired:
        # 処理に異常な時間がかかるファイルは、DoS 細工を疑って隔離
        return quarantine(bucket, key, reason="scan-timeout", detail=f"{SCAN_TIMEOUT}s")
    finally:
        os.remove(local)

    if proc.returncode == 0:
        report = json.loads(proc.stdout)
        warns = [i["code"] for i in report["issues"]]
        s3.put_object_tagging(
            Bucket=bucket,
            Key=key,
            Tagging={"TagSet": [
                {"Key": "scan-status", "Value": "clean"},
                {"Key": "scan-warnings", "Value": ",".join(warns)[:255] or "none"},
            ]},
        )
        return {"key": key, "status": "clean", "warnings": warns}

    if proc.returncode == 1:
        report = json.loads(proc.stdout)
        denied = [i["code"] for i in report["issues"] if i["severity"] == "deny"]
        return quarantine(bucket, key, reason="policy-violation", detail=",".join(denied))

    # exit code 2（破損・解析不能）および想定外の終了コードはすべて隔離
    return quarantine(bucket, key, reason="unparseable", detail=proc.stdout[:255])


def quarantine(bucket: str, key: str, reason: str, detail: str) -> dict:
    s3.copy_object(
        Bucket=QUARANTINE_BUCKET,
        Key=f"{bucket}/{key}",
        CopySource={"Bucket": bucket, "Key": key},
        TaggingDirective="REPLACE",
        Tagging=urllib.parse.urlencode({"scan-status": reason, "scan-detail": detail[:255]}),
    )
    s3.delete_object(Bucket=bucket, Key=key)
    return {"key": key, "status": "quarantined", "reason": reason, "detail": detail}
