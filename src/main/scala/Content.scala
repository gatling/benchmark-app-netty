import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.GZIPOutputStream

import io.netty.handler.codec.http.HttpHeaderValues.{APPLICATION_JSON, TEXT_PLAIN}
import io.netty.util.AsciiString

import scala.io.{Codec, Source}

object Content {
  private val HtmlContentType = new AsciiString("text/html; charset=utf-8")

  val HelloWorld = Content("/txt/hello.txt", TEXT_PLAIN)
  val Json100 = Content("/json/100.json", APPLICATION_JSON)
  val Json250 = Content("/json/250.json", APPLICATION_JSON)
  val Json500 = Content("/json/500.json", APPLICATION_JSON)
  val Json1k = Content("/json/1k.json", APPLICATION_JSON)
  val Json10k = Content("/json/10k.json", APPLICATION_JSON)
  val Html46k = Content("/html/46k.html", HtmlContentType)
  val Html232k = Content("/html/232k.html", HtmlContentType)

  def apply(path: String, contentType: CharSequence): Content = {
    var is = getClass.getClassLoader.getResourceAsStream(path)
    if (is == null) {
      is = getClass.getClassLoader.getResourceAsStream(path.drop(1))
    }
    require(is != null, s"Couldn't locate resource $path in ClassLoader")

    try {
      val rawBytes = Source.fromInputStream(is)(Codec.UTF8).mkString.getBytes(UTF_8)
      val compressedBytes: Array[Byte] = {
        val baos = new ByteArrayOutputStream
        val gzip = new GZIPOutputStream(baos)
        gzip.write(rawBytes)
        gzip.close()
        baos.toByteArray
      }

      new Content(path, rawBytes, compressedBytes, contentType)

    } finally {
      is.close()
    }
  }
}

case class Content(path: String, rawBytes: Array[Byte], compressedBytes: Array[Byte], contentType: CharSequence)
