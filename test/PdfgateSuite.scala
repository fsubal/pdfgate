package pdfgate

import java.io.File
import java.nio.file.Files
import pdfgate.analysis.*
import pdfgate.testkit.Fixtures

class PdfgateSuite extends munit.FunSuite:

  val corpus = FunFixture[File](
    setup = _ => {
      val dir = Files.createTempDirectory("pdfgate-fixtures").toFile
      Fixtures.all(dir)
      dir
    },
    teardown = dir => {
      dir.listFiles().foreach(_.delete())
      dir.delete()
    }
  )

  private def analyzed[A](file: File, password: Option[String] = None)(f: org.apache.pdfbox.pdmodel.PDDocument => A): A =
    Doc.withDocument(file, password)(f).fold(state => fail(s"expected document to load, got $state"), identity)

  corpus.test("structure scan reports sane values for a clean file") { dir =>
    val s = StructureScan.scan(File(dir, "simple.pdf"))
    assertEquals(s.bytesBeforeHeader, 0)
    assertEquals(s.eofMarkers, 1)
    assertEquals(s.incrementalUpdates, 0)
    assertEquals(s.bytesAfterLastEof, 0L)
    assert(s.headerVersion.exists(_.startsWith("1.")))
  }

  corpus.test("layers are reported") { dir =>
    val r = analyzed(File(dir, "with-layers.pdf"))(Analyze.layers)
    assert(r.present)
    assertEquals(r.layers.map(_.name).sorted, Seq("Diagram", "Watermark"))
  }

  corpus.test("plain file has no layers") { dir =>
    val r = analyzed(File(dir, "simple.pdf"))(Analyze.layers)
    assert(!r.present)
  }

  corpus.test("form fields are reported") { dir =>
    val r = analyzed(File(dir, "with-form.pdf"))(Analyze.forms)
    assert(r.hasAcroForm)
    assertEquals(r.fields.map(f => (f.name, f.fieldType, f.required)), Seq(("email", "Tx", true)))
    assert(!r.xfa.present)
  }

  corpus.test("scan finds JavaScript") { dir =>
    val r = analyzed(File(dir, "with-js.pdf"))(Scan.run)
    assert(r.counts.contains("javascript"), r.counts.toString)
  }

  corpus.test("scan finds embedded files") { dir =>
    val r = analyzed(File(dir, "with-attachment.pdf"))(Scan.run)
    assert(r.counts.contains("embedded-file"), r.counts.toString)
    assert(r.counts.contains("embedded-files-name-tree"), r.counts.toString)
  }

  corpus.test("scan finds URI actions with the target URL") { dir =>
    val r = analyzed(File(dir, "with-uri.pdf"))(Scan.run)
    val uri = r.findings.filter(_.kind == "uri-action")
    assertEquals(uri.flatMap(_.detail), Seq("https://example.com/exfil"))
  }

  corpus.test("scan finds nothing risky in a clean file") { dir =>
    val r = analyzed(File(dir, "simple.pdf"))(Scan.run)
    assertEquals(r.findings, Seq.empty)
  }

  corpus.test("image-bearing PDF is parsed, scans clean, and text still extracts") { dir =>
    val file = File(dir, "with-image.pdf")
    val scan = analyzed(file)(Scan.run)
    assertEquals(scan.findings, Seq.empty)
    val text = analyzed(file)(Text.extract(_, None, 1000))
    assert(text.text.contains("Hello from pdfgate fixtures"))
    val Right(v) = Validate.run(file, None, Policy()): @unchecked
    assert(v.ok)
  }

  corpus.test("Japanese and unusual characters survive extraction, metadata, and validation") { dir =>
    val file = File(dir, "with-japanese.pdf")
    val text = analyzed(file)(Text.extract(_, None, 1000))
    assert(text.text.contains("日本語のテキスト"), text.text)
    assert(text.text.contains("吾輩は猫である"), text.text)
    // Shift_JIS 0x5C-tail characters, enclosed numerics, a ligature, and a non-BMP emoji
    assert(text.text.contains("ソ能表"), text.text)
    assert(text.text.contains("①②③"), text.text)
    // the ﬁ ligature is expanded to "fi" by the stripper's Unicode normalization
    assert(text.text.contains("fiΩ🎌"), text.text)
    val info = analyzed(file)(doc => Analyze.info(file, doc, StructureScan.scan(file)))
    assertEquals(info.metadata.flatMap(_.title), Some("日本語タイトル（検証用）"))
    val scan = analyzed(file)(Scan.run)
    assertEquals(scan.findings, Seq.empty)
    val Right(v) = Validate.run(file, None, Policy()): @unchecked
    assert(v.ok)
  }

  corpus.test("validate passes a clean file") { dir =>
    val r = Validate.run(File(dir, "simple.pdf"), None, Policy())
    assertEquals(r.map(_.ok), Right(true))
  }

  corpus.test("validate denies JavaScript by default") { dir =>
    val Right(r) = Validate.run(File(dir, "with-js.pdf"), None, Policy()): @unchecked
    assert(!r.ok)
    assert(r.issues.exists(i => i.code == "javascript" && i.severity == "deny"))
  }

  corpus.test("validate warns on URI actions but stays ok") { dir =>
    val Right(r) = Validate.run(File(dir, "with-uri.pdf"), None, Policy()): @unchecked
    assert(r.ok)
    assert(r.issues.exists(i => i.code == "uri-action" && i.severity == "warn"))
  }

  corpus.test("policy rules can be overridden") { dir =>
    val strict = Policy(rules = Map("uri-action" -> "deny"))
    val Right(r) = Validate.run(File(dir, "with-uri.pdf"), None, strict): @unchecked
    assert(!r.ok)
  }

  corpus.test("password-protected file is denied when unreadable") { dir =>
    val Right(r) = Validate.run(File(dir, "encrypted.pdf"), None, Policy()): @unchecked
    assert(!r.ok)
    assert(r.issues.exists(_.code == "password-protected"))
  }

  corpus.test("encrypted file opens with password and reports crypto info") { dir =>
    val file = File(dir, "encrypted.pdf")
    val r = analyzed(file, Some("user-secret"))(doc => Analyze.crypto(file, doc))
    assert(r.encrypted)
    assertEquals(r.keyLengthBits, Some(256))
  }

  corpus.test("broken file yields ParseFailed, not an exception") { dir =>
    Doc.load(File(dir, "broken.pdf"), None) match
      case LoadResult.ParseFailed(_) => ()
      case other => fail(s"expected ParseFailed, got $other")
  }

  corpus.test("text extraction returns page text") { dir =>
    val r = analyzed(File(dir, "simple.pdf"))(Text.extract(_, None, 1000))
    assert(r.text.contains("Hello from pdfgate fixtures"))
    assert(!r.truncated)
  }

  corpus.test("text extraction truncates at max-chars") { dir =>
    val r = analyzed(File(dir, "simple.pdf"))(Text.extract(_, None, 5))
    assertEquals(r.text, "Hello")
    assert(r.truncated)
  }
