# frozen_string_literal: true

# ActiveStorage の添付を保存する前に pdfgate で検査するバリデータ。
#
#   class Document < ApplicationRecord
#     has_one_attached :file
#     validates :file, pdf_safety: true
#   end
#
# バリデーションはレコード保存前（= blob がストレージへアップロードされる前）に
# 動くため、危険な PDF はストレージに一切書き込まれない。
class PdfSafetyValidator < ActiveModel::EachValidator
  DENY_MESSAGES = {
    "javascript" => "JavaScript が埋め込まれた PDF はアップロードできません",
    "embedded-file" => "ファイルが埋め込まれた PDF はアップロードできません",
    "embedded-files-name-tree" => "ファイルが埋め込まれた PDF はアップロードできません",
    "launch-action" => "外部プログラムを起動する PDF はアップロードできません",
    "password-protected" => "パスワード付き PDF はアップロードできません"
  }.freeze
  DENY_FALLBACK = "安全でない要素（%s）が含まれる PDF はアップロードできません"
  UNREADABLE = "PDF として読み取れないファイルです"

  def validate_each(record, attribute, _value)
    attachable = record.attachment_changes[attribute.to_s]&.attachable
    # 新しい添付があるときだけ検査する。attachable が Tempfile を持たない形
    # （署名付き blob ID の再添付など）は検査済みとみなしてスキップ
    return unless attachable.respond_to?(:tempfile)

    result = Pdfgate.validate(attachable.tempfile.path, timeout: scan_timeout)

    if result.error
      # timeout / parse-failure / password-required はすべて「読めない物」として拒否
      record.errors.add(attribute, DENY_MESSAGES.fetch(result.error, UNREADABLE))
      return
    end

    result.deny_codes.uniq.each do |code|
      record.errors.add(attribute, DENY_MESSAGES.fetch(code) { DENY_FALLBACK % code })
    end

    # warn はブロックせずログに残す（URI アクション・XFA・暗号化など）
    if result.warn_codes.any?
      Rails.logger.info(
        "[pdfgate] #{record.class.name}##{attribute}: warnings=#{result.warn_codes.uniq.join(',')}"
      )
    end
  end

  private

  def scan_timeout
    options.fetch(:timeout, 20)
  end
end
