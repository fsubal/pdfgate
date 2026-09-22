//> using scala 3.3
//> using dep com.lihaoyi::upickle:4.4.3

// Cross-checks pdfgate output against poppler (pdfinfo / pdftotext).
//
// Compares, per fixture: page count, PDF version, encrypted flag.
// Also compares extracted text on simple.pdf, and agreement that broken.pdf
// is unreadable. Exits non-zero on any disagreement.
//
// Run with: scala-cli run tools/poppler-compare.scala -- <pdfgate-binary> <fixtures-dir>

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

object PopplerCompare:
  var failures = 0

  def report(name: String, check: String, ours: Any, theirs: Any): Unit =
    if ours == theirs then println(s"ok   $name: $check = $ours")
    else
      println(s"FAIL $name: $check pdfgate=$ours poppler=$theirs")
      failures += 1

  def pdfinfo(path: Path, password: Option[String]): (Int, Map[String, String]) =
    val (code, out) = run("pdfinfo" :: password.toList.flatMap(p => List("-upw", p)) ::: List(path.toString))
    val fields = out.linesIterator.map { line =>
      val (key, value) = line.span(_ != ':')
      key.trim -> value.drop(1).trim
    }.toMap
    (code, fields)

  def pdfgateInfo(binary: String, path: Path, password: Option[String]): (Int, ujson.Value) =
    val (code, out) = run(binary :: "info" :: path.toString :: password.toList.flatMap(p => List("--password", p)))
    (code, ujson.read(if out.isBlank then "{}" else out))

  def compareFile(binary: String, path: Path, password: Option[String] = None): Unit =
    val (pCode, p) = pdfinfo(path, password)
    val (gCode, g) = pdfgateInfo(binary, path, password)
    val name = path.getFileName.toString
    if pCode != 0 || gCode != 0 then
      // both tools should agree the file is unreadable
      report(name, "unreadable-agreement", gCode != 0, pCode != 0)
    else
      report(name, "pages", g("pages").num.toInt, p("Pages").toInt)
      report(name, "version", g("version").str, p("PDF version"))
      report(name, "encrypted", g("encrypted").bool, p("Encrypted").startsWith("yes"))

  def pdftotext(path: Path): String = run(List("pdftotext", "-q", path.toString, "-"))._2

  def pdfgateText(binary: String, path: Path): String =
    ujson.read(run(List(binary, "text", path.toString))._2)("text").str

  def compareText(binary: String, path: Path): Unit =
    def normalize(s: String) = s.replaceAll("\\s+", " ").trim
    report(path.getFileName.toString, "text", normalize(pdfgateText(binary, path)), normalize(pdftotext(path)))

  def compareTextContains(binary: String, path: Path, needle: String): Unit =
    // containment rather than equality: the two tools differ on ligature
    // normalization (pdfgate expands ﬁ -> fi, poppler keeps it)
    val name = path.getFileName.toString
    report(name, "pdfgate text contains needle", pdfgateText(binary, path).contains(needle), true)
    report(name, "poppler text contains needle", pdftotext(path).contains(needle), true)

  def main(args: Array[String]): Unit =
    val (binary, fixtures) = args.toList match
      case b :: f :: _ => (b, Paths.get(f))
      case _ => sys.error("usage: scala-cli run tools/poppler-compare.scala -- <pdfgate-binary> <fixtures-dir>")

    val pdfs = Files.list(fixtures).iterator.asScala.filter(_.toString.endsWith(".pdf")).toList.sortBy(_.getFileName.toString)
    for pdf <- pdfs do
      val password = Option.when(pdf.getFileName.toString == "encrypted.pdf")("user-secret")
      compareFile(binary, pdf, password)
    compareText(binary, fixtures.resolve("simple.pdf"))
    compareTextContains(binary, fixtures.resolve("with-japanese.pdf"), "日本語のテキスト：吾輩は猫である。")

    if failures > 0 then
      println(s"$failures mismatch(es)")
      sys.exit(1)
    println("pdfgate and poppler agree on all checks")

  /** Runs a command, returning its exit code and stdout (stderr is discarded). */
  def run(cmd: List[String]): (Int, String) =
    val proc = ProcessBuilder(cmd.asJava).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val out = String(proc.getInputStream.readAllBytes(), "UTF-8")
    if !proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) then
      proc.destroyForcibly()
      sys.error(s"timed out: ${cmd.mkString(" ")}")
    (proc.exitValue(), out)
