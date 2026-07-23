package pdfgate.analysis

import java.io.{File, FileInputStream}
import pdfgate.output.Structure

/** Byte-level structure checks that work even when the PDF does not parse. */
object StructureScan:
  private val Header = "%PDF-".getBytes
  private val Eof = "%%EOF".getBytes

  def scan(file: File): Structure =
    val size = file.length()
    val (eofCount, lastEofEnd) = countOccurrences(file, Eof)
    val head = readHead(file, 1024)
    val headerPos = indexOf(head, Header, 0)
    val headerVersion =
      if headerPos < 0 then None
      else
        val start = headerPos + Header.length
        val end = head.indexWhere(b => b == '\n' || b == '\r', start)
        Some(String(head, start, (if end < 0 then head.length else end) - start).trim)
    // Trailing whitespace/newlines after the final %%EOF are normal; anything else is not.
    val afterEof = if lastEofEnd < 0 then size else size - lastEofEnd
    Structure(
      fileSizeBytes = size,
      headerVersion = headerVersion,
      bytesBeforeHeader = headerPos,
      eofMarkers = eofCount,
      incrementalUpdates = math.max(0, eofCount - 1),
      bytesAfterLastEof = math.max(0L, afterEof - countTrailingWhitespace(file, lastEofEnd)),
      objectCount = None
    )

  private def readHead(file: File, n: Int): Array[Byte] =
    val in = FileInputStream(file)
    try in.readNBytes(n)
    finally in.close()

  /** Returns (number of matches, offset just past the last match), streaming in chunks. */
  private def countOccurrences(file: File, pattern: Array[Byte]): (Int, Long) =
    val in = FileInputStream(file)
    try
      val chunkSize = 1 << 20
      val overlap = pattern.length - 1
      var count = 0
      var lastEnd = -1L
      var base = 0L
      var carry = Array.emptyByteArray
      var done = false
      while !done do
        val chunk = in.readNBytes(chunkSize)
        if chunk.isEmpty then done = true
        else
          val buf = carry ++ chunk
          var i = 0
          while
            i = indexOf(buf, pattern, i)
            i >= 0
          do
            count += 1
            lastEnd = base - carry.length + i + pattern.length
            i += 1
          carry = buf.takeRight(math.min(overlap, buf.length))
          base += chunk.length
      (count, lastEnd)
    finally in.close()

  /** Number of consecutive whitespace bytes starting at `from`. */
  private def countTrailingWhitespace(file: File, from: Long): Long =
    if from < 0 then 0L
    else
      val in = java.io.BufferedInputStream(FileInputStream(file))
      try
        in.skip(from)
        var ws = 0L
        var b = in.read()
        while b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == 0 do
          ws += 1
          b = in.read()
        ws
      finally in.close()

  private def indexOf(haystack: Array[Byte], needle: Array[Byte], from: Int): Int =
    var i = math.max(0, from)
    while i <= haystack.length - needle.length do
      var j = 0
      while j < needle.length && haystack(i + j) == needle(j) do j += 1
      if j == needle.length then return i
      i += 1
    -1
