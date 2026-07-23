package pdfgate.analysis

import java.io.File
import upickle.default.ReadWriter
import pdfgate.output.{ValidateResult, ValidationIssue}

/** Policy: limits plus a kind -> action ("deny" | "warn" | "allow") table.
  * Unknown kinds default to "warn". A policy JSON file may override any subset
  * of fields; `rules` entries are merged over the defaults.
  */
case class Policy(
    maxPages: Int = 5000,
    maxObjects: Int = 250000,
    maxIncrementalUpdates: Int = 0,
    rules: Map[String, String] = Map.empty
):
  def actionFor(kind: String): String =
    rules.getOrElse(kind, Policy.defaultRules.getOrElse(kind, "warn"))

object Policy:
  given ReadWriter[Policy] = upickle.default.macroRW

  val defaultRules: Map[String, String] = Map(
    "javascript" -> "deny",
    "launch-action" -> "deny",
    "remote-goto" -> "deny",
    "submit-form" -> "deny",
    "import-data" -> "deny",
    "embedded-file" -> "deny",
    "embedded-files-name-tree" -> "deny",
    "richmedia" -> "deny",
    "password-protected" -> "deny",
    "uri-action" -> "warn",
    "file-attachment-annotation" -> "warn",
    "xfa" -> "warn",
    "movie" -> "warn",
    "sound" -> "warn",
    "rendition" -> "warn",
    "3d-content" -> "warn",
    "encrypted" -> "warn",
    "incremental-update" -> "warn",
    "data-after-eof" -> "warn"
  )

object Validate:
  /** Left(message) means the file could not be parsed at all (exit 2 territory). */
  def run(file: File, password: Option[String], policy: Policy): Either[String, ValidateResult] =
    val structure = StructureScan.scan(file)
    val issues = collection.mutable.ArrayBuffer.empty[ValidationIssue]

    def issue(kind: String, message: String): Unit =
      policy.actionFor(kind) match
        case "allow" => ()
        case action => issues += ValidationIssue(action, kind, message)

    if structure.incrementalUpdates > policy.maxIncrementalUpdates then
      issue("incremental-update", s"${structure.incrementalUpdates} incremental update(s) detected")
    if structure.bytesAfterLastEof > 0 then
      issue("data-after-eof", s"${structure.bytesAfterLastEof} byte(s) of non-whitespace data after final %%EOF")

    val parsed = Doc.withDocument(file, password) { doc =>
      if doc.getNumberOfPages > policy.maxPages then
        issues += ValidationIssue("deny", "max-pages-exceeded", s"${doc.getNumberOfPages} pages > limit ${policy.maxPages}")
      val objectCount = doc.getDocument.getXrefTable.size
      if objectCount > policy.maxObjects then
        issues += ValidationIssue("deny", "max-objects-exceeded", s"$objectCount objects > limit ${policy.maxObjects}")
      if doc.isEncrypted then issue("encrypted", "document is encrypted")
      val scan = Scan.run(doc)
      scan.counts.toSeq.sortBy(_._1).foreach { (kind, count) =>
        val sample = scan.findings.find(f => f.kind == kind && f.detail.isDefined).flatMap(_.detail)
        issue(kind, s"$count occurrence(s)" + sample.fold("")(d => s"; e.g. $d"))
      }
    }

    parsed match
      case Left(LoadResult.ParseFailed(m)) => Left(m)
      case Left(LoadResult.PasswordRequired) =>
        issue("password-protected", "document requires a password; content cannot be inspected")
        Right(result(issues.toSeq))
      case _ => Right(result(issues.toSeq))

  private def result(issues: Seq[ValidationIssue]): ValidateResult =
    val sorted = issues.sortBy(i => (if i.severity == "deny" then 0 else 1, i.code))
    ValidateResult(ok = !sorted.exists(_.severity == "deny"), issues = sorted)
