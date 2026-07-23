# frozen_string_literal: true

class Document < ApplicationRecord
  has_one_attached :file

  validates :file, presence: true, pdf_safety: { timeout: 20 }

  # content_type の検査は pdfgate の役割ではないので併用する
  # (active_storage_validations gem を使う場合の例)
  # validates :file, content_type: "application/pdf", size: { less_than: 50.megabytes }
end
