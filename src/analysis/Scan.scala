package pdfgate.analysis

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import org.apache.pdfbox.cos.*
import org.apache.pdfbox.pdmodel.PDDocument
import pdfgate.output.{Finding, ScanResult}

/** Walks the reachable COS object graph and reports risk-relevant elements.
  * Working at the COS level (rather than the PD model) catches actions and
  * embedded files wherever they hide: name trees, annotations, additional
  * actions, field dictionaries, etc.
  */
object Scan:
  private val JavaScriptName = COSName.getPDFName("JavaScript")

  private val actionKinds = Map(
    JavaScriptName -> "javascript",
    COSName.getPDFName("Launch") -> "launch-action",
    COSName.getPDFName("URI") -> "uri-action",
    COSName.getPDFName("GoToR") -> "remote-goto",
    COSName.getPDFName("GoToE") -> "remote-goto",
    COSName.getPDFName("SubmitForm") -> "submit-form",
    COSName.getPDFName("ImportData") -> "import-data",
    COSName.getPDFName("Movie") -> "movie",
    COSName.getPDFName("Sound") -> "sound",
    COSName.getPDFName("Rendition") -> "rendition"
  )

  private val annotationKinds = Map(
    COSName.getPDFName("FileAttachment") -> "file-attachment-annotation",
    COSName.getPDFName("RichMedia") -> "richmedia",
    COSName.getPDFName("Movie") -> "movie",
    COSName.getPDFName("Sound") -> "sound",
    COSName.getPDFName("3D") -> "3d-content"
  )

  def run(doc: PDDocument): ScanResult =
    val findings = mutable.ArrayBuffer.empty[Finding]
    val visited = mutable.HashSet.empty[COSObjectKey]

    def walk(base: COSBase, objNum: Option[Long]): Unit = base match
      case obj: COSObject =>
        if visited.add(obj.getKey) then walk(obj.getObject, Some(obj.getKey.getNumber))
      case dict: COSDictionary =>
        inspect(dict, objNum)
        dict.getValues.asScala.foreach(walk(_, objNum))
      case arr: COSArray =>
        arr.iterator.asScala.foreach(walk(_, objNum))
      case _ => ()

    def inspect(dict: COSDictionary, objNum: Option[Long]): Unit =
      val s = Option(dict.getCOSName(COSName.S))
      s.flatMap(actionKinds.get).foreach { kind =>
        findings += Finding(kind, objNum, actionDetail(kind, dict))
      }
      // Defensive: /JS present without a matching /S name still means JavaScript.
      if dict.containsKey(COSName.JS) && !s.contains(JavaScriptName) then
        findings += Finding("javascript", objNum, Some("JS entry without /S /JavaScript"))
      Option(dict.getCOSName(COSName.SUBTYPE)).flatMap(annotationKinds.get).foreach { kind =>
        findings += Finding(kind, objNum, None)
      }
      if dict.getCOSName(COSName.TYPE) == COSName.FILESPEC && dict.containsKey(COSName.EF) then
        findings += Finding("embedded-file", objNum, Option(dict.getString(COSName.F)))
      if dict.containsKey(COSName.EMBEDDED_FILES) then
        findings += Finding("embedded-files-name-tree", objNum, None)
      if dict.containsKey(COSName.XFA) then findings += Finding("xfa", objNum, None)

    def actionDetail(kind: String, dict: COSDictionary): Option[String] = kind match
      case "uri-action" => Option(dict.getString(COSName.URI))
      case "launch-action" | "remote-goto" | "import-data" | "submit-form" =>
        dict.getDictionaryObject(COSName.F) match
          case s: COSString => Some(s.getString)
          case d: COSDictionary => Option(d.getString(COSName.F)).orElse(Option(d.getString(COSName.UF)))
          case _ => None
      case _ => None

    walk(doc.getDocument.getTrailer, None)
    val fs = findings.toSeq
    ScanResult(findings = fs, counts = fs.groupBy(_.kind).view.mapValues(_.size).toMap)
