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
    Seq(intact, withJs(dir), withLayers(dir), withForm(dir), withAttachment(dir), withUri(dir), withImage(dir), encrypted(dir), broken(dir, intact))

object GenFixtures:
  def main(args: Array[String]): Unit =
    val dir = File(args.headOption.getOrElse("fixtures"))
    Fixtures.all(dir).foreach(f => println(s"wrote ${f.getPath}"))
