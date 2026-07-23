//> using scala 3.3
//> using dep org.apache.pdfbox:pdfbox:3.0.7
//> using dep org.ow2.asm:asm-tree:9.8

// Produces lib/pdfbox-<version>-noawt.jar: a copy of the pdfbox jar whose
// PDDocument static initializer no longer runs the AWT color-space warm-up
// (Raster.createBandedRaster + PDDeviceRGB.toRGBImage). That warm-up only
// matters for concurrent *rendering*, which pdfgate does not do, and it makes
// java.awt.image reachable — which breaks GraalVM native-image on macOS and
// bloats Linux images. The static field assignments preceding the warm-up
// (RESERVE_BYTE_RANGE, LOG) are kept.
//
// The warm-up is the trailing `try { ... } catch (IOException ...)` block of
// <clinit>, so the transform is: cut everything from the start label of the
// sole IOException try/catch to the end of the method, then re-append RETURN.
//
// Run with: scala-cli run tools/patch-pdfbox.scala

import java.io.{File, FileOutputStream}
import java.util.jar.{JarFile, JarOutputStream, JarEntry}
import org.objectweb.asm.{ClassReader, ClassWriter, Opcodes}
import org.objectweb.asm.tree.{ClassNode, InsnNode}
import scala.jdk.CollectionConverters.*

object PatchPdfbox:
  val TargetClassFile = "org/apache/pdfbox/pdmodel/PDDocument.class"

  def main(args: Array[String]): Unit =
    val srcJar = File(
      classOf[org.apache.pdfbox.pdmodel.PDDocument].getProtectionDomain.getCodeSource.getLocation.toURI
    )
    val version = srcJar.getName.stripPrefix("pdfbox-").stripSuffix(".jar")
    val outJar = File(s"lib/pdfbox-$version-noawt.jar")
    outJar.getParentFile.mkdirs()

    val jar = JarFile(srcJar)
    val out = JarOutputStream(FileOutputStream(outJar))
    var patched = false
    try
      for entry <- jar.entries().asScala do
        val bytes = jar.getInputStream(entry).readAllBytes()
        out.putNextEntry(JarEntry(entry.getName))
        out.write(if entry.getName == TargetClassFile then { patched = true; stripAwtWarmup(bytes) } else bytes)
        out.closeEntry()
    finally
      out.close()
      jar.close()

    require(patched, s"$TargetClassFile not found in $srcJar")
    println(s"wrote ${outJar.getPath}")

  def stripAwtWarmup(classBytes: Array[Byte]): Array[Byte] =
    val node = ClassNode()
    ClassReader(classBytes).accept(node, 0)
    val clinit = node.methods.asScala.find(_.name == "<clinit>")
      .getOrElse(sys.error("PDDocument has no <clinit>; pdfbox layout changed, re-inspect the source"))

    val ioCatches = clinit.tryCatchBlocks.asScala.filter(_.`type` == "java/io/IOException")
    require(
      ioCatches.sizeIs == 1 && clinit.tryCatchBlocks.size == 1,
      s"expected exactly one IOException try/catch in <clinit>, found ${clinit.tryCatchBlocks.size}; pdfbox layout changed, re-inspect the source"
    )

    val cutFrom = clinit.instructions.indexOf(ioCatches.head.start)
    while clinit.instructions.size > cutFrom do
      clinit.instructions.remove(clinit.instructions.get(cutFrom))
    clinit.instructions.add(InsnNode(Opcodes.RETURN))
    clinit.tryCatchBlocks.clear()
    clinit.localVariables = null

    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
    node.accept(writer)
    val result = writer.toByteArray

    // Sanity check: the AWT warm-up references must be gone from the patched class.
    val text = String(result, java.nio.charset.StandardCharsets.ISO_8859_1)
    require(!text.contains("java/awt/image/Raster"), "AWT warm-up still referenced after patch")
    result
