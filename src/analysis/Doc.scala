package pdfgate.analysis

import java.io.File
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBufferedFile
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException

/** Outcome of trying to open an untrusted PDF. Never lets a parser exception escape. */
enum LoadResult:
  case Loaded(doc: PDDocument)
  case PasswordRequired
  case ParseFailed(message: String)

object Doc:
  def load(file: File, password: Option[String]): LoadResult =
    try LoadResult.Loaded(Loader.loadPDF(new RandomAccessReadBufferedFile(file), password.getOrElse("")))
    catch
      case _: InvalidPasswordException => LoadResult.PasswordRequired
      case e: java.io.IOException => LoadResult.ParseFailed(describe(e))
      case e: RuntimeException => LoadResult.ParseFailed(describe(e))

  /** Opens the document, runs `f`, and always closes it. Parser exceptions thrown
    * lazily during analysis (untrusted input) are normalized to ParseFailed.
    */
  def withDocument[A](file: File, password: Option[String])(f: PDDocument => A): Either[LoadResult, A] =
    load(file, password) match
      case LoadResult.Loaded(doc) =>
        try Right(f(doc))
        catch
          case e: java.io.IOException => Left(LoadResult.ParseFailed(describe(e)))
          case e: RuntimeException => Left(LoadResult.ParseFailed(describe(e)))
        finally doc.close()
      case other => Left(other)

  private def describe(e: Throwable): String =
    s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("(no message)")}"
