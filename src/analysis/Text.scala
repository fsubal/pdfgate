package pdfgate.analysis

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import pdfgate.output.TextResult

object Text:
  def extract(doc: PDDocument, pages: Option[(Int, Int)], maxChars: Int): TextResult =
    val stripper = PDFTextStripper()
    pages.foreach { (from, to) =>
      stripper.setStartPage(from)
      stripper.setEndPage(to)
    }
    val text = stripper.getText(doc)
    val truncated = text.length > maxChars
    TextResult(
      pages = doc.getNumberOfPages,
      truncated = truncated,
      text = if truncated then text.take(maxChars) else text
    )
