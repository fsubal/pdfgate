# 検査専用 Lambda のコンテナイメージ。
# pdfgate は JVM 不要の単一バイナリなので、ランタイムに Python を選んでも
# イメージには Java が一切入らない。
FROM public.ecr.aws/lambda/python:3.13

# GitHub Releases から取得した Linux バイナリを同梱する
# （アーキテクチャは Lambda の設定に合わせる: x86_64 or arm64）
COPY pdfgate-linux-x86_64 /opt/bin/pdfgate
RUN chmod +x /opt/bin/pdfgate

COPY handler.py ${LAMBDA_TASK_ROOT}
CMD ["handler.lambda_handler"]
