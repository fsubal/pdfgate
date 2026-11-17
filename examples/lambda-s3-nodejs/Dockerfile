# 検査専用 Lambda のコンテナイメージ（Node.js 版）。
# pdfgate は JVM 不要の単一バイナリなので、ランタイムに Node.js を選んでも
# イメージには Java が一切入らない。
FROM public.ecr.aws/lambda/nodejs:22

# GitHub Releases から取得した Linux バイナリを同梱する
# （アーキテクチャは Lambda の設定に合わせる: x86_64 or arm64）
COPY pdfgate-linux-x86_64 /opt/bin/pdfgate
RUN chmod +x /opt/bin/pdfgate

# ベースイメージにも AWS SDK v3 は入っているが、バージョンを固定するため明示的にインストールする
COPY package.json ${LAMBDA_TASK_ROOT}/
RUN npm install --omit=dev --prefix ${LAMBDA_TASK_ROOT}

COPY handler.mjs ${LAMBDA_TASK_ROOT}/
CMD ["handler.handler"]
