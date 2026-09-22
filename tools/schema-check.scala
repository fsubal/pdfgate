//> using scala 3.3
//> using dep com.networknt:json-schema-validator:3.0.7
//> using dep org.slf4j:slf4j-nop:2.0.19

// Validates real pdfgate output against the published schemas in schemas/.
//
// Runs every subcommand against the fixture corpus, asserts the expected exit
// code, and validates stdout against the corresponding schema file. Keeps the
// schemas honest: any drift between the CLI output and schemas/ fails CI.
//
// Run with: scala-cli run tools/schema-check.scala -- <pdfgate-binary> <fixtures-dir>

import com.networknt.schema.{InputFormat, Schema, SchemaRegistry}
import com.networknt.schema.dialect.Dialects
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

object SchemaCheck:
  // (schema name, expected exit code, argv suffix)
  val Cases: List[(String, Int, List[String])] = List(
    ("info", 0, List("info", "simple.pdf")),
    ("info", 0, List("info", "with-japanese.pdf")),
    ("info", 0, List("info", "encrypted.pdf")), // passwordRequired variant: nulls
    ("layers", 0, List("layers", "with-layers.pdf")),
    ("layers", 0, List("layers", "simple.pdf")),
    ("forms", 0, List("forms", "with-form.pdf")),
    ("forms", 0, List("forms", "simple.pdf")),
    ("crypto", 0, List("crypto", "encrypted.pdf", "--password", "user-secret")),
    ("crypto", 0, List("crypto", "encrypted.pdf")), // passwordRequired variant: nulls
    ("crypto", 0, List("crypto", "simple.pdf")),
    ("scan", 0, List("scan", "with-js.pdf")),
    ("scan", 0, List("scan", "with-attachment.pdf")),
    ("scan", 0, List("scan", "with-uri.pdf")),
    ("scan", 0, List("scan", "simple.pdf")),
    ("validate", 0, List("validate", "simple.pdf")),
    ("validate", 1, List("validate", "with-js.pdf")),
    ("validate", 1, List("validate", "encrypted.pdf")),
    ("text", 0, List("text", "simple.pdf")),
    ("text", 0, List("text", "with-japanese.pdf")),
    ("error", 2, List("info", "broken.pdf")), // parse-failure
    ("error", 2, List("scan", "encrypted.pdf")), // password-required
  )

  def main(args: Array[String]): Unit =
    val Array(binary, fixturesDir) = args.take(2) match
      case Array(b, f) => Array(b, f)
      case _ => sys.error("usage: scala-cli run tools/schema-check.scala -- <pdfgate-binary> <fixtures-dir>")
    val fixtures = Paths.get(fixturesDir)
    val schemasDir = Paths.get("schemas")

    val registry = SchemaRegistry.withDialect(Dialects.getDraft202012())
    val schemas: Map[String, Schema] = Cases.map(_._1).distinct.map { name =>
      val file = schemasDir.resolve(s"$name.schema.json")
      name -> registry.getSchema(Files.readString(file), InputFormat.JSON)
    }.toMap

    var failures = 0
    for (schemaName, wantCode, argv) <- Cases do
      val cmd = binary :: argv.map(a => if a.endsWith(".pdf") then fixtures.resolve(a).toString else a)
      val label = s"${argv.mkString(" ")} vs $schemaName.schema.json"
      val (code, stdout) = run(cmd)
      if code != wantCode then
        println(s"FAIL $label: exit $code != $wantCode")
        failures += 1
      else
        val errors = schemas(schemaName).validate(stdout, InputFormat.JSON).asScala
        if errors.nonEmpty then
          for e <- errors do
            val where = Option(e.getInstanceLocation).map(_.toString).filter(_.nonEmpty).getOrElse("<root>")
            println(s"FAIL $label: $where: ${e.getMessage}")
          failures += 1
        else println(s"ok   $label")

    if failures > 0 then
      println(s"$failures schema check(s) failed")
      sys.exit(1)
    println("all outputs conform to schemas/")

  /** Runs a command, returning its exit code and stdout (stderr is discarded). */
  def run(cmd: List[String]): (Int, String) =
    val proc = ProcessBuilder(cmd.asJava).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val out = String(proc.getInputStream.readAllBytes(), "UTF-8")
    if !proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) then
      proc.destroyForcibly()
      sys.error(s"timed out: ${cmd.mkString(" ")}")
    (proc.exitValue(), out)
