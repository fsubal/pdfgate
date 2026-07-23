package pdfgate

import java.io.File
import java.nio.file.Files
import cats.syntax.all.*
import com.monovore.decline.*
import upickle.default.{read as jread, write as jwrite, Writer}
import pdfgate.analysis.*
import pdfgate.output.*

object Main:
  private val fileArg = Opts.argument[String]("file").map(File(_))
  private val passwordOpt = Opts.option[String]("password", "Password for encrypted PDFs").orNone

  private def emit[A: Writer](a: A, exit: Int): Int =
    println(jwrite(a, indent = 2))
    exit

  private def parseError(message: String): Int =
    emit(ErrorResult(ErrorBody("parse-failure", message)), 2)

  private def passwordError: Int =
    emit(ErrorResult(ErrorBody("password-required", "document requires a password")), 2)

  /** Analysis commands that need a readable document. */
  private def withDoc[A: Writer](file: File, pw: Option[String])(f: org.apache.pdfbox.pdmodel.PDDocument => A): Int =
    Doc.withDocument(file, pw)(f) match
      case Right(a) => emit(a, 0)
      case Left(LoadResult.PasswordRequired) => passwordError
      case Left(LoadResult.ParseFailed(m)) => parseError(m)
      case Left(_) => parseError("unexpected load state")

  private val infoCmd = Opts.subcommand("info", "Document metadata and file structure") {
    (fileArg, passwordOpt).mapN { (file, pw) =>
      val structure = StructureScan.scan(file)
      Doc.withDocument(file, pw)(doc => Analyze.info(file, doc, structure)) match
        case Right(r) => emit(r, 0)
        case Left(LoadResult.PasswordRequired) =>
          emit(
            InfoResult(file.getPath, None, None, encrypted = true, passwordRequired = true, None, None, structure),
            0
          )
        case Left(LoadResult.ParseFailed(m)) => parseError(m)
        case Left(_) => parseError("unexpected load state")
    }
  }

  private val layersCmd = Opts.subcommand("layers", "Optional content groups (layers)") {
    (fileArg, passwordOpt).mapN((file, pw) => withDoc(file, pw)(Analyze.layers))
  }

  private val formsCmd = Opts.subcommand("forms", "AcroForm fields and XFA") {
    (fileArg, passwordOpt).mapN((file, pw) => withDoc(file, pw)(Analyze.forms))
  }

  private val cryptoCmd = Opts.subcommand("crypto", "Encryption, permissions and signatures") {
    (fileArg, passwordOpt).mapN { (file, pw) =>
      Doc.withDocument(file, pw)(doc => Analyze.crypto(file, doc)) match
        case Right(r) => emit(r, 0)
        case Left(LoadResult.PasswordRequired) =>
          emit(CryptoResult(encrypted = true, passwordRequired = true, None, None, None, None, None, None, Nil), 0)
        case Left(LoadResult.ParseFailed(m)) => parseError(m)
        case Left(_) => parseError("unexpected load state")
    }
  }

  private val scanCmd = Opts.subcommand("scan", "Enumerate risk-relevant elements (JavaScript, actions, embedded files, ...)") {
    (fileArg, passwordOpt).mapN((file, pw) => withDoc(file, pw)(Scan.run))
  }

  private val policyOpt =
    Opts.option[String]("policy", "Path to a policy JSON file overriding the built-in defaults").orNone

  private val validateCmd = Opts.subcommand("validate", "Validate against a policy; exit 0 = OK, 1 = violations, 2 = broken") {
    (fileArg, passwordOpt, policyOpt).mapN { (file, pw, policyPath) =>
      val loadedPolicy =
        try Right(policyPath.fold(Policy())(p => jread[Policy](Files.readString(File(p).toPath))))
        catch case e: Exception => Left(s"invalid policy file: ${e.getMessage}")
      loadedPolicy match
        case Left(msg) =>
          System.err.println(msg)
          3
        case Right(policy) =>
          Validate.run(file, pw, policy) match
            case Left(m) => parseError(m)
            case Right(r) => emit(r, if r.ok then 0 else 1)
    }
  }

  private val pagesOpt = Opts
    .option[String]("pages", "Page range, e.g. 2-5 (1-based, inclusive)")
    .mapValidated { s =>
      s.split("-", 2) match
        case Array(a, b) if a.forall(_.isDigit) && b.forall(_.isDigit) && a.nonEmpty && b.nonEmpty =>
          cats.data.Validated.valid((a.toInt, b.toInt))
        case Array(a) if a.nonEmpty && a.forall(_.isDigit) =>
          cats.data.Validated.valid((a.toInt, a.toInt))
        case _ => cats.data.Validated.invalidNel(s"invalid page range: $s")
    }
    .orNone

  private val maxCharsOpt =
    Opts.option[Int]("max-chars", "Truncate extracted text after this many characters").withDefault(1_000_000)

  private val textCmd = Opts.subcommand("text", "Extract text (pdftotext equivalent)") {
    (fileArg, passwordOpt, pagesOpt, maxCharsOpt).mapN { (file, pw, pages, maxChars) =>
      withDoc(file, pw)(Text.extract(_, pages, maxChars))
    }
  }

  private val command: Command[Int] = Command(
    name = "pdfgate",
    header = "Analyze and validate untrusted PDF files (PDFBox-based, JVM-free)"
  ) {
    infoCmd <+> layersCmd <+> formsCmd <+> cryptoCmd <+> scanCmd <+> validateCmd <+> textCmd
  }

  def main(args: Array[String]): Unit =
    command.parse(args.toIndexedSeq, sys.env) match
      case Left(help) =>
        System.err.println(help)
        sys.exit(if help.errors.nonEmpty then 3 else 0)
      case Right(exit) => sys.exit(exit)
