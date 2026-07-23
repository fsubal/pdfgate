package pdfgate.analysis

import java.io.File
import java.util.Calendar
import scala.jdk.CollectionConverters.*
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import pdfgate.output.*

object Analyze:
  def info(file: File, doc: PDDocument, structure: Structure): InfoResult =
    val di = doc.getDocumentInformation
    InfoResult(
      file = file.getPath,
      version = Some(formatVersion(doc.getVersion)),
      pages = Some(doc.getNumberOfPages),
      encrypted = doc.isEncrypted,
      passwordRequired = false,
      metadata = Some(
        Metadata(
          title = Option(di.getTitle),
          author = Option(di.getAuthor),
          subject = Option(di.getSubject),
          keywords = Option(di.getKeywords),
          creator = Option(di.getCreator),
          producer = Option(di.getProducer),
          creationDate = Option(di.getCreationDate).map(formatDate),
          modificationDate = Option(di.getModificationDate).map(formatDate)
        )
      ),
      xmpPresent = Some(doc.getDocumentCatalog.getMetadata != null),
      structure = structure.copy(objectCount = Some(doc.getDocument.getXrefTable.size))
    )

  def layers(doc: PDDocument): LayersResult =
    Option(doc.getDocumentCatalog.getOCProperties) match
      case None => LayersResult(present = false, count = 0, layers = Nil)
      case Some(oc) =>
        val groups = oc.getOptionalContentGroups.asScala.toSeq
        LayersResult(
          present = true,
          count = groups.size,
          layers = groups.map(g => Layer(name = g.getName, enabled = oc.isGroupEnabled(g)))
        )

  def forms(doc: PDDocument): FormsResult =
    val catalog = doc.getDocumentCatalog
    Option(catalog.getAcroForm(null)) match
      case None => FormsResult(hasAcroForm = false, 0, 0, Nil, Xfa(present = false, dynamic = false))
      case Some(af) =>
        val fields = af.getFieldTree.asScala.toSeq.map { f =>
          FormField(
            name = f.getFullyQualifiedName,
            fieldType = Option(f.getFieldType).getOrElse("(none)"),
            required = f.isRequired,
            readOnly = f.isReadOnly,
            value = Option(f.getValueAsString).filter(_.nonEmpty)
          )
        }
        FormsResult(
          hasAcroForm = true,
          fieldCount = fields.size,
          signatureFieldCount = fields.count(_.fieldType == "Sig"),
          fields = fields,
          xfa = Xfa(
            present = af.getXFA != null,
            dynamic = catalog.getCOSObject.getBoolean(COSName.getPDFName("NeedsRendering"), false)
          )
        )

  def crypto(file: File, doc: PDDocument): CryptoResult =
    val enc = Option.when(doc.isEncrypted)(doc.getEncryption)
    val perms = doc.getCurrentAccessPermission
    val fileLen = file.length()
    val signatures = doc.getSignatureDictionaries.asScala.toSeq.map { sig =>
      val br = sig.getByteRange
      SignatureInfo(
        name = Option(sig.getName),
        signDate = Option(sig.getSignDate).map(formatDate),
        filter = Option(sig.getFilter),
        subFilter = Option(sig.getSubFilter),
        coversWholeDocument = br != null && br.length == 4 && br(0) == 0 && br(2).toLong + br(3).toLong == fileLen
      )
    }
    CryptoResult(
      encrypted = doc.isEncrypted,
      passwordRequired = false,
      filter = enc.flatMap(e => Option(e.getFilter)),
      subFilter = enc.flatMap(e => Option(e.getSubFilter)),
      version = enc.map(_.getVersion),
      revision = enc.map(_.getRevision),
      keyLengthBits = enc.map(_.getLength),
      permissions = Option.when(doc.isEncrypted)(
        Permissions(
          canPrint = perms.canPrint,
          canModify = perms.canModify,
          canExtractContent = perms.canExtractContent,
          canFillInForm = perms.canFillInForm,
          canModifyAnnotations = perms.canModifyAnnotations,
          canAssembleDocument = perms.canAssembleDocument
        )
      ),
      signatures = signatures
    )

  private def formatVersion(v: Float): String = f"$v%.1f"

  private def formatDate(cal: Calendar): String = cal.toInstant.toString
