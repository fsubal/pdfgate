package pdfgate.testkit

import java.io.{ByteArrayInputStream, File}
import java.nio.file.Files
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.common.filespecification.{PDComplexFileSpecification, PDEmbeddedFile}
import org.apache.pdfbox.pdmodel.encryption.{AccessPermission, StandardProtectionPolicy}
import org.apache.pdfbox.pdmodel.font.{PDType1Font, Standard14Fonts}
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.{PDOptionalContentGroup, PDOptionalContentProperties}
import org.apache.pdfbox.pdmodel.interactive.action.{PDActionJavaScript, PDActionURI}
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.form.{PDAcroForm, PDTextField}

/** Generates the fixture PDF corpus used by both the test suite and the native
  * binary smoke test. Unreachable from Main, so it never ends up in the binary.
  */
object Fixtures:

  private def baseDoc(): PDDocument =
    val doc = PDDocument()
    val page = PDPage()
    doc.addPage(page)
    val cs = PDPageContentStream(doc, page)
    cs.beginText()
    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12)
    cs.newLineAtOffset(72, 720)
    cs.showText("Hello from pdfgate fixtures")
    cs.endText()
    cs.close()
    doc

  private def save(doc: PDDocument, dir: File, name: String): File =
    val out = File(dir, name)
    doc.save(out)
    doc.close()
    out

  def simple(dir: File): File = save(baseDoc(), dir, "simple.pdf")

  def withJs(dir: File): File =
    val doc = baseDoc()
    doc.getDocumentCatalog.setOpenAction(PDActionJavaScript("app.alert('hi');"))
    save(doc, dir, "with-js.pdf")

  def withLayers(dir: File): File =
    val doc = baseDoc()
    val oc = PDOptionalContentProperties()
    oc.addGroup(PDOptionalContentGroup("Watermark"))
    oc.addGroup(PDOptionalContentGroup("Diagram"))
    doc.getDocumentCatalog.setOCProperties(oc)
    save(doc, dir, "with-layers.pdf")

  def withForm(dir: File): File =
    val doc = baseDoc()
    val af = PDAcroForm(doc)
    doc.getDocumentCatalog.setAcroForm(af)
    val field = PDTextField(af)
    field.setPartialName("email")
    field.setRequired(true)
    af.getFields.add(field)
    save(doc, dir, "with-form.pdf")

  def withAttachment(dir: File): File =
    val doc = baseDoc()
    val catalog = doc.getDocumentCatalog
    val spec = PDComplexFileSpecification()
    spec.setFile("payload.txt")
    spec.setEmbeddedFile(PDEmbeddedFile(doc, ByteArrayInputStream("attached data".getBytes)))
    val tree = org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode()
    tree.setNames(java.util.Map.of("payload.txt", spec))
    val names = PDDocumentNameDictionary(catalog)
    names.setEmbeddedFiles(tree)
    catalog.setNames(names)
    save(doc, dir, "with-attachment.pdf")

  def withUri(dir: File): File =
    val doc = baseDoc()
    val link = PDAnnotationLink()
    val action = PDActionURI()
    action.setURI("https://example.com/exfil")
    link.setAction(action)
    doc.getPage(0).getAnnotations.add(link)
    save(doc, dir, "with-uri.pdf")

  /** Image generation uses AWT (BufferedImage), which is fine here: fixtures are
    * always generated on the JVM. Analyzing the result with the AWT-free native
    * binary is exactly the regression this fixture exists to cover.
    */
  def withImage(dir: File): File =
    val doc = baseDoc()
    val bimg = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
    for x <- 0 until 8; y <- 0 until 8 do bimg.setRGB(x, y, 0xff8800)
    val image = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, bimg)
    val cs = PDPageContentStream(doc, doc.getPage(0), PDPageContentStream.AppendMode.APPEND, true)
    cs.drawImage(image, 100, 600, 64, 64)
    cs.close()
    save(doc, dir, "with-image.pdf")

  /** Japanese and other non-Latin text. Built at the COS level as a Type0
    * (Identity-H) font carrying a ToUnicode CMap but no embedded glyphs — the
    * shape subset-font PDFs in the wild have, where extraction can only rely on
    * ToUnicode. Covers predefined-CMap resources in the native binary, non-BMP
    * surrogate pairs, and non-ASCII JSON output (metadata title).
    */
  val japaneseText = "日本語のテキスト：吾輩は猫である。①②③ ソ能表〜 ﬁΩ🎌"

  def withJapanese(dir: File): File =
    import org.apache.pdfbox.cos.*
    import org.apache.pdfbox.pdmodel.common.PDStream
    val doc = baseDoc()
    doc.getDocumentInformation.setTitle("日本語タイトル（検証用）")
    val page = doc.getPage(0)

    val codePoints = japaneseText.codePoints().toArray
    val bfchars = codePoints.zipWithIndex
      .map((cp, i) =>
        val dst = String(Character.toChars(cp)).getBytes("UTF-16BE").map(b => f"$b%02X").mkString
        f"<${i + 1}%04X> <$dst>")
      .mkString("\n")
    val cmap =
      s"""/CIDInit /ProcSet findresource begin
         |12 dict begin
         |begincmap
         |/CMapName /pdfgate-ToUnicode def
         |/CMapType 2 def
         |1 begincodespacerange
         |<0000> <FFFF>
         |endcodespacerange
         |${codePoints.length} beginbfchar
         |$bfchars
         |endbfchar
         |endcmap
         |CMapName currentdict /CMap defineresource pop
         |end
         |end""".stripMargin
    val toUnicode = PDStream(doc, ByteArrayInputStream(cmap.getBytes("US-ASCII")))

    val descriptor = COSDictionary()
    descriptor.setItem(COSName.TYPE, COSName.FONT_DESC)
    descriptor.setName(COSName.FONT_NAME, "PdfgateNoGlyphs")
    descriptor.setInt(COSName.FLAGS, 4)
    val bbox = COSArray()
    Seq(0, -120, 1000, 880).foreach(v => bbox.add(COSInteger.get(v)))
    descriptor.setItem(COSName.FONT_BBOX, bbox)
    descriptor.setInt(COSName.ITALIC_ANGLE, 0)
    descriptor.setInt(COSName.ASCENT, 880)
    descriptor.setInt(COSName.DESCENT, -120)
    descriptor.setInt(COSName.CAP_HEIGHT, 700)
    descriptor.setInt(COSName.STEM_V, 80)

    val cidSystemInfo = COSDictionary()
    cidSystemInfo.setString(COSName.REGISTRY, "Adobe")
    cidSystemInfo.setString(COSName.ORDERING, "Identity")
    cidSystemInfo.setInt(COSName.SUPPLEMENT, 0)

    val cidFont = COSDictionary()
    cidFont.setItem(COSName.TYPE, COSName.FONT)
    cidFont.setItem(COSName.SUBTYPE, COSName.getPDFName("CIDFontType2"))
    cidFont.setName(COSName.BASE_FONT, "PdfgateNoGlyphs")
    cidFont.setItem(COSName.CIDSYSTEMINFO, cidSystemInfo)
    cidFont.setInt(COSName.DW, 1000)
    cidFont.setItem(COSName.FONT_DESC, descriptor)

    val font = COSDictionary()
    font.setItem(COSName.TYPE, COSName.FONT)
    font.setItem(COSName.SUBTYPE, COSName.getPDFName("Type0"))
    font.setName(COSName.BASE_FONT, "PdfgateNoGlyphs")
    font.setItem(COSName.ENCODING, COSName.IDENTITY_H)
    val descendants = COSArray()
    descendants.add(cidFont)
    font.setItem(COSName.DESCENDANT_FONTS, descendants)
    font.setItem(COSName.TO_UNICODE, toUnicode)

    page.getResources.getCOSObject
      .getCOSDictionary(COSName.FONT)
      .setItem(COSName.getPDFName("Fjp"), font)

    val codes = (1 to codePoints.length).map(i => f"$i%04X").mkString
    val ops = s"BT /Fjp 12 Tf 72 690 Td <$codes> Tj ET"
    val extra = PDStream(doc, ByteArrayInputStream(ops.getBytes("US-ASCII")))
    val contents = COSArray()
    contents.add(page.getCOSObject.getItem(COSName.CONTENTS))
    contents.add(extra.getCOSObject)
    page.getCOSObject.setItem(COSName.CONTENTS, contents)
    save(doc, dir, "with-japanese.pdf")

  def encrypted(dir: File): File =
    val doc = baseDoc()
    val policy = StandardProtectionPolicy("owner-secret", "user-secret", AccessPermission())
    policy.setEncryptionKeyLength(256)
    doc.protect(policy)
    save(doc, dir, "encrypted.pdf")

  def broken(dir: File, intact: File): File =
    val out = File(dir, "broken.pdf")
    Files.write(out.toPath, Files.readAllBytes(intact.toPath).take(120))
    out

  def all(dir: File): Seq[File] =
    dir.mkdirs()
    val intact = simple(dir)
    Seq(intact, withJs(dir), withLayers(dir), withForm(dir), withAttachment(dir), withUri(dir), withImage(dir), withJapanese(dir), encrypted(dir), broken(dir, intact))

object GenFixtures:
  def main(args: Array[String]): Unit =
    val dir = File(args.headOption.getOrElse("fixtures"))
    Fixtures.all(dir).foreach(f => println(s"wrote ${f.getPath}"))
