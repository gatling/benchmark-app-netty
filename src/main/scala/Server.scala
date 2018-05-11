import java.io.{ByteArrayOutputStream, IOException}
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

import scala.io.{Codec, Source}

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
import io.netty.util.internal.logging.{InternalLoggerFactory, Slf4JLoggerFactory}

object Server extends StrictLogging {

  implicit val codec: Codec = Codec.UTF8

  private val HtmlContentType = new AsciiString("text/html; charset=utf-8")

  private val port = 8000

  object Content {
    def fromText(text: String, contentType: CharSequence): Content =
      new Content(text.getBytes(codec.charSet), contentType)

    def fromResource(res: String, contentType: CharSequence): Content =
      new Content(resourceAsBytes(res), contentType)
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
  private val Json500Content = Content.fromResource("500.json", APPLICATION_JSON)
  private val Json1kContent = Content.fromResource("1k.json", APPLICATION_JSON)
  private val Json10kContent = Content.fromResource("10k.json", APPLICATION_JSON)
  private val NewsContent = Content.fromResource("news.html", HtmlContentType)

  private def resourceAsBytes(path: String) = {
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
        ctx.executor().schedule(new Runnable {
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
            //            .addLast("idleTimer", new CloseOnIdleReadTimeoutHandler(1))
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
                  ioe.printStackTrace()
                  ctx.channel.close()
                case _ => ctx.fireExceptionCaught(cause)
              }

              override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit =
                msg match {
                  case request: FullHttpRequest =>
                    ReferenceCountUtil.release(request) // FIXME is this necessary?

                    if (request.uri == "/echo") {
                      val content = request.content()
                      val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
                      response.headers().add(CONTENT_LENGTH, content.readableBytes)
                      writeResponse(ctx, response)
                    } else {
                      request.uri match {
                        case "/hello" => writeResponse(ctx, request, HelloWorldContent)
                        case "/json500" => writeResponse(ctx, request, Json500Content)
                        case "/json1k" => writeResponse(ctx, request, Json1kContent)
                        case "/json10k" => writeResponse(ctx, request, Json10kContent)
                        case "/news" => writeResponse(ctx, request, NewsContent)
                        case _ => writeResponse(ctx, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND))
                      }
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
    bossGroup.shutdownGracefully()
    workerGroup.shutdownGracefully()
  }
}
