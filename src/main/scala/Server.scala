import java.io.{ByteArrayOutputStream, IOException}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

import scala.io.Source
import com.typesafe.scalalogging.StrictLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel._
import io.netty.channel.epoll.{EpollEventLoopGroup, EpollServerSocketChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.ServerSocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpHeaderNames._
import io.netty.handler.codec.http.HttpHeaderValues._
import io.netty.handler.codec.http._
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.handler.ssl.{SslContextBuilder, SslProvider}
import io.netty.handler.timeout.{IdleState, IdleStateEvent}
import io.netty.util._
import io.netty.util.internal.PlatformDependent

object Server extends StrictLogging {

  private val HtmlContentType = new AsciiString("text/html; charset=utf-8")

  private val httpPort = 8000
  private val httpsPort = 8001

  object Content {
    def fromText(text: String, contentType: CharSequence): Content =
      new Content(text.getBytes(UTF_8), contentType)

    def fromResource(res: String, contentType: CharSequence): Content =
      new Content(resourceAsBytes(res), contentType)

    val HelloWorld = Content.fromText("Hello, World!", TEXT_PLAIN)
    val Json100 = Content.fromResource("100.json", APPLICATION_JSON)
    val Json250 = Content.fromResource("250.json", APPLICATION_JSON)
    val Json500 = Content.fromResource("500.json", APPLICATION_JSON)
    val Json1k = Content.fromResource("1k.json", APPLICATION_JSON)
    val Json10k = Content.fromResource("10k.json", APPLICATION_JSON)
    val News = Content.fromResource("news.html", HtmlContentType)
  }

  case class Content(rawBytes: Array[Byte], contentType: CharSequence) {
    val compressedBytes: Array[Byte] = {
      val baos = new ByteArrayOutputStream
      val gzip = new GZIPOutputStream(baos)
      gzip.write(rawBytes)
      gzip.close()
      baos.toByteArray
    }
  }

  private def resourceAsBytes(path: String) = {
    val source = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(path))
    try {
      source.mkString.getBytes(UTF_8)
    } finally {
      source.close()
    }
  }

  private def writeResponse(ctx: ChannelHandlerContext, response: DefaultFullHttpResponse): Unit = {
    response.headers.set(CONTENT_LENGTH, response.content.readableBytes)
    ctx.writeAndFlush(response)
    logger.debug(s"wrote response=$response")
  }

  private def writeResponse(ctx: ChannelHandlerContext, request: HttpRequest, content: Content): Unit = {
    val compress = acceptGzip(request)
    val bytes = if (compress) content.compressedBytes else content.rawBytes
    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes))

    response.headers
      .set(CONTENT_TYPE, content.contentType)
      .set(CONTENT_LENGTH, bytes.length)

    if (compress) {
      response.headers.set(CONTENT_ENCODING, GZIP)
    }

    Option(request.headers.get("X-Delay")) match {
      case Some(delayHeader) =>
        val delay = delayHeader.toLong
        ctx.executor.schedule(new Runnable {
          override def run(): Unit =
            if (ctx.channel.isActive) {
              writeResponse(ctx, response)
            }
        }, delay, TimeUnit.MILLISECONDS)

      case _ =>
        writeResponse(ctx, response)
    }
  }

  private def acceptGzip(request: HttpRequest): Boolean =
    Option(request.headers.get(ACCEPT_ENCODING)).exists(_.contains("gzip"))

  def main(args: Array[String]): Unit = {

    logger.info(s"os.name ${System.getProperty("os.name")}")
    logger.info(s"os.version ${System.getProperty("os.version")}")

    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED)
    val useNativeTransport = !PlatformDependent.isOsx && !PlatformDependent.isWindows

    val bossGroup = if (useNativeTransport) new EpollEventLoopGroup else new NioEventLoopGroup
    val workerGroup = if (useNativeTransport) new EpollEventLoopGroup else new NioEventLoopGroup

    val ssc = new SelfSignedCertificate
    val sslContext = SslContextBuilder
      .forServer(ssc.certificate, ssc.privateKey)
      .sslProvider(SslProvider.OPENSSL)
      .protocols("TLSv1.3")
      .build()

    val channelClass: Class[_ <: ServerSocketChannel] = if (useNativeTransport) classOf[EpollServerSocketChannel] else classOf[NioServerSocketChannel]

    def channelInitializer(useHttps: Boolean): ChannelInitializer[Channel] =
      (ch: Channel) => {
        val pipeline = ch.pipeline
        if (useHttps) {
          pipeline.addLast(sslContext.newHandler(ch.alloc))
        }
        pipeline
          .addLast("idleTimer", new CloseOnIdleReadTimeoutHandler(5))
          .addLast("decoder", new HttpRequestDecoder(4096, 8192, 8192, false))
          .addLast("aggregator", new HttpObjectAggregator(30000))
          .addLast("encoder", new HttpResponseEncoder)
          .addLast("handler", new ChannelInboundHandlerAdapter {

            override def userEventTriggered(ctx: ChannelHandlerContext, evt: AnyRef): Unit =
              evt match {
                case e: IdleStateEvent if e.state == IdleState.READER_IDLE =>
                  logger.info("Idle => closing")
                  ctx.close()
                case _ =>
              }

            override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = cause match {
              case ioe: IOException =>
                if (ioe.getMessage != "Connection reset by peer") {
                  // ignore, this is just client aborting
                  ioe.printStackTrace()
                }
                ctx.channel.close()
              case _ => ctx.fireExceptionCaught(cause)
            }

            override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit =
              msg match {
                case request: FullHttpRequest =>
                  ReferenceCountUtil.release(request)

                  if (request.uri == "/echo") {
                    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, request.content)
                    writeResponse(ctx, response)
                  } else {
                    request.uri match {
                      case "/hello" => writeResponse(ctx, request, Content.HelloWorld)
                      case "/json100" => writeResponse(ctx, request, Content.Json100)
                      case "/json250" => writeResponse(ctx, request, Content.Json250)
                      case "/json500" => writeResponse(ctx, request, Content.Json500)
                      case "/json1k" => writeResponse(ctx, request, Content.Json1k)
                      case "/json10k" => writeResponse(ctx, request, Content.Json10k)
                      case "/news" => writeResponse(ctx, request, Content.News)
                      case "/post" => writeResponse(ctx, request, Content.HelloWorld)
                      case _ => writeResponse(ctx, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND))
                    }
                  }

                case _ =>
                  logger.error(s"Read unexpected msg=$msg")
              }
          })
      }

    val bootstrap = new ServerBootstrap()
      .option[Integer](ChannelOption.SO_BACKLOG, 2 * 1024)
      .group(bossGroup, workerGroup)
      .channel(channelClass)
      .childHandler(channelInitializer(useHttps = false))

    val whenCloseHttp = bootstrap.bind(new InetSocketAddress(httpPort)).sync
    val whenCloseHttps = bootstrap.clone.childHandler(channelInitializer(useHttps = true)).bind(new InetSocketAddress(httpsPort)).sync
    logger.info(s"Server started on port $httpPort (HTTP) and port $httpsPort (HTTPS)")
    whenCloseHttp.channel.closeFuture.sync
    whenCloseHttps.channel.closeFuture.sync
    logger.info("stopping")
    bossGroup.shutdownGracefully()
    workerGroup.shutdownGracefully()
  }
}
