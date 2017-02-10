import java.io.{ByteArrayOutputStream, IOException}
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel._
import io.netty.channel.epoll.{EpollEventLoopGroup, EpollServerSocketChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.ServerSocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http._
import HttpHeaderNames._
import HttpHeaderValues._
import io.netty.handler.timeout.{IdleState, IdleStateEvent}
import io.netty.util._
import io.netty.util.internal.logging.{InternalLoggerFactory, Slf4JLoggerFactory}
import scala.io.{Codec, Source}

import com.typesafe.scalalogging.StrictLogging
import io.netty.handler.ssl.{SslContextBuilder, SslProvider}
import io.netty.handler.ssl.util.SelfSignedCertificate
import org.apache.commons.io.IOUtils

object Server extends StrictLogging {

  implicit val codec = Codec.UTF8

  private val HtmlContentType = new AsciiString("text/html; charset=utf-8")

  private val port = 8000

  object Content {
    def fromText(text: String, contentType: CharSequence): Content =
      new Content(text.getBytes(codec.charSet), contentType)

    def fromResource(res: String, contentType: CharSequence): Content =
      new Content(IOUtils.toByteArray(Thread.currentThread().getContextClassLoader.getResourceAsStream(res)), contentType)
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

  private val HelloWorldContent = Content.fromText("Hello, World!", TEXT_PLAIN)
  private val Json1kContent = Content.fromResource("1k.json", APPLICATION_JSON)
  private val Json10kContent = Content.fromResource("10k.json", APPLICATION_JSON)
  private val NewsContent = Content.fromResource("news.html", HtmlContentType)

  def resourceAsBytes(path: String) = {
    val source = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(path))
    try {
      source.mkString.getBytes(codec.charSet)
    } finally {
      source.close()
    }
  }

  private def writeResponse(ctx: ChannelHandlerContext, response: DefaultFullHttpResponse): Unit = {
    ctx.writeAndFlush(response)
    logger.debug(s"wrote response=$response")
  }

  private def writeResponse(ctx: ChannelHandlerContext, request: HttpRequest, content: Content, timer: HashedWheelTimer): Unit = {

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
        timer.newTimeout(timeout => if (ctx.channel.isActive) {
          writeResponse(ctx, response)
        }, delay, TimeUnit.MILLISECONDS)


      case _ =>
        writeResponse(ctx, response)
    }
  }

  private def acceptGzip(request: HttpRequest): Boolean =
    Option(request.headers.get(ACCEPT_ENCODING)).exists(_.contains("gzip"))

  def main(args: Array[String]): Unit = {

    InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE)

    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED)
    val useNativeTransport = java.lang.Boolean.getBoolean("gatling.useNativeTransport")
    val useHttps = java.lang.Boolean.getBoolean("gatling.useHttps")
    val useOpenSsl = java.lang.Boolean.getBoolean("gatling.useOpenSsl")

    val bossGroup = if (useNativeTransport) new EpollEventLoopGroup else new NioEventLoopGroup
    val workerGroup = if (useNativeTransport) new EpollEventLoopGroup else new NioEventLoopGroup

    val ssc = new SelfSignedCertificate
    val sslContext = SslContextBuilder
      .forServer(ssc.certificate, ssc.privateKey)
      .sslProvider(if (useOpenSsl) SslProvider.OPENSSL else SslProvider.JDK)
      .build()

    val channelClass: Class[_ <: ServerSocketChannel] = if (useNativeTransport) classOf[EpollServerSocketChannel] else classOf[NioServerSocketChannel]

    val timer = new HashedWheelTimer(10, TimeUnit.MILLISECONDS)
    timer.start()

    val bootstrap = new ServerBootstrap()
      .option[Integer](ChannelOption.SO_BACKLOG, 2 * 1024)
      .group(bossGroup, workerGroup)
      .channel(channelClass)
      .childHandler(new ChannelInitializer[Channel] {
        override def initChannel(ch: Channel): Unit = {
          val pipeline = ch.pipeline
          if (useHttps) {
            pipeline.addLast(sslContext.newHandler(ch.alloc))
          }
          pipeline
            // don't validate headers
            .addLast("idleTimer", new CloseOnIdleReadTimeoutHandler(1))
            .addLast("decoder", new HttpRequestDecoder(4096, 8192, 8192, false))
            .addLast("aggregator", new HttpObjectAggregator(30000))
            .addLast("encoder", new HttpResponseEncoder)
            .addLast("handler", new ChannelInboundHandlerAdapter {

              override def userEventTriggered(ctx: ChannelHandlerContext, evt: AnyRef): Unit =
                evt match {
                  case e: IdleStateEvent if e.state == IdleState.READER_IDLE => ctx.close()
                  case _ =>
                }

              override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = cause match {
                case ioe: IOException => ctx.channel.close()
                case _                => ctx.fireExceptionCaught(cause)
              }

              override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit =
                msg match {
                  case request: FullHttpRequest if request.uri == "/echo" =>
                    val content = request.content()
                    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
                    response.headers().add(CONTENT_LENGTH, content.readableBytes)
                    writeResponse(ctx, response)

                  case request: FullHttpRequest =>
                    ReferenceCountUtil.release(request) // FIXME is this necessary?

                    request.uri match {
                      case "/hello" => writeResponse(ctx, request, HelloWorldContent, timer)
                      case "/json1k" => writeResponse(ctx, request, Json1kContent, timer)
                      case "/json10k" => writeResponse(ctx, request, Json10kContent, timer)
                      case "/news" => writeResponse(ctx, request, NewsContent, timer)

                      case uri =>
                        val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND)
                        writeResponse(ctx, response)
                    }

                  case _ =>
                    logger.error(s"Read unexpected msg=$msg")
                }
            })
        }
      })

    val f = bootstrap.bind(new InetSocketAddress(port)).sync
    logger.info("Server started on port " + port)
    f.channel.closeFuture.sync
    logger.info("stopping")
    timer.stop()
    bossGroup.shutdownGracefully()
    workerGroup.shutdownGracefully()
  }
}
