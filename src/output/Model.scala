package pdfgate.output

import upickle.default.ReadWriter

case class Structure(
    fileSizeBytes: Long,
    headerVersion: Option[String],
    bytesBeforeHeader: Int,
    eofMarkers: Int,
    incrementalUpdates: Int,
    bytesAfterLastEof: Long,
    objectCount: Option[Int]
) derives ReadWriter

case class Metadata(
    title: Option[String],
    author: Option[String],
    subject: Option[String],
    keywords: Option[String],
    creator: Option[String],
    producer: Option[String],
    creationDate: Option[String],
    modificationDate: Option[String]
) derives ReadWriter

case class InfoResult(
    file: String,
    version: Option[String],
    pages: Option[Int],
    encrypted: Boolean,
    passwordRequired: Boolean,
    metadata: Option[Metadata],
    xmpPresent: Option[Boolean],
    structure: Structure
) derives ReadWriter

case class Layer(name: String, enabled: Boolean) derives ReadWriter
case class LayersResult(present: Boolean, count: Int, layers: Seq[Layer]) derives ReadWriter

case class FormField(
    name: String,
    fieldType: String,
    required: Boolean,
    readOnly: Boolean,
    value: Option[String]
) derives ReadWriter
case class Xfa(present: Boolean, dynamic: Boolean) derives ReadWriter
case class FormsResult(
    hasAcroForm: Boolean,
    fieldCount: Int,
    signatureFieldCount: Int,
    fields: Seq[FormField],
    xfa: Xfa
) derives ReadWriter

case class Permissions(
    canPrint: Boolean,
    canModify: Boolean,
    canExtractContent: Boolean,
    canFillInForm: Boolean,
    canModifyAnnotations: Boolean,
    canAssembleDocument: Boolean
) derives ReadWriter
case class SignatureInfo(
    name: Option[String],
    signDate: Option[String],
    filter: Option[String],
    subFilter: Option[String],
    coversWholeDocument: Boolean
) derives ReadWriter
case class CryptoResult(
    encrypted: Boolean,
    passwordRequired: Boolean,
    filter: Option[String],
    subFilter: Option[String],
    version: Option[Int],
    revision: Option[Int],
    keyLengthBits: Option[Int],
    permissions: Option[Permissions],
    signatures: Seq[SignatureInfo]
) derives ReadWriter

case class Finding(kind: String, objectNumber: Option[Long], detail: Option[String]) derives ReadWriter
case class ScanResult(findings: Seq[Finding], counts: Map[String, Int]) derives ReadWriter

case class ValidationIssue(severity: String, code: String, message: String) derives ReadWriter
case class ValidateResult(ok: Boolean, issues: Seq[ValidationIssue]) derives ReadWriter

case class TextResult(pages: Int, truncated: Boolean, text: String) derives ReadWriter

case class ErrorBody(code: String, message: String) derives ReadWriter
case class ErrorResult(error: ErrorBody) derives ReadWriter
