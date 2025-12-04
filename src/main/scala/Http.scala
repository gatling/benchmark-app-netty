import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.{ForkJoinPool, TimeUnit}
import com.typesafe.scalalogging.StrictLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel._
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.HttpHeaderNames.{ACCEPT_ENCODING, CONTENT_ENCODING, CONTENT_LENGTH, CONTENT_TYPE}
import io.netty.handler.codec.http.HttpHeaderValues.GZIP
import io.netty.handler.ssl.SslContext
import io.netty.util.{AsciiString, ReferenceCountUtil}
import org.apache.commons.math3.distribution.LogNormalDistribution

import scala.jdk.CollectionConverters._
import scala.concurrent.duration.FiniteDuration

@Sharable
object AppHandler extends ChannelInboundHandlerAdapter with StrictLogging {

  private val XDelayHeader = new AsciiString("X-Delay")
  private val XUseLogNormalDelayHeader = new AsciiString("X-UseLogNormalDelay")
  private val logNormalDistribution = new LogNormalDistribution()

  private def writeResponse(ctx: ChannelHandlerContext, response: DefaultFullHttpResponse): Unit = {
    response.headers.set(CONTENT_LENGTH, response.content.readableBytes)
    ctx.writeAndFlush(response)
    logger.debug(s"wrote response=$response")
  }

  private def writeResponse(ctx: ChannelHandlerContext, request: HttpRequest, content: Content): Unit = {
    val acceptGzip = Option(request.headers.get(ACCEPT_ENCODING)).exists(_.contains("gzip"))
    val maybeDelay = Option(request.headers.get(AppHandler.XDelayHeader)).map(_.toLong)
    val useLogNormalDelay = Option(request.headers.get(AppHandler.XUseLogNormalDelayHeader)).exists(_.toBoolean)

    val bytes = if (acceptGzip) content.compressedBytes else content.rawBytes
    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes))

    response.headers
      .set(CONTENT_TYPE, content.contentType)
      .set(CONTENT_LENGTH, bytes.length)

    if (acceptGzip) {
      response.headers.set(CONTENT_ENCODING, GZIP)
    }

    maybeDelay match {
      case Some(averageDelay) =>
        val delay = if (useLogNormalDelay) math.round(AppHandler.logNormalDistribution.sample * averageDelay) else averageDelay
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

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = cause match {
    case ioe: IOException =>
      val root = if (ioe.getCause != null) ioe.getCause else ioe
      if (!(root.getMessage != null && root.getMessage.endsWith("Connection reset by peer"))) {
        // ignore, this is just client aborting socket
        logger.error("exceptionCaught", ioe)
      }
      ctx.channel.close()
    case _ => ctx.fireExceptionCaught(cause)
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit =
    try {
      msg match {
        case request: FullHttpRequest =>
          if (request.uri == "/echo") {
            val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, request.content.retain())
            writeResponse(ctx, response)

          } else if (request.uri == "/redirect/infinite") {
            val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND)
            response.headers.add(HttpHeaderNames.LOCATION, "/redirect/infinite")
            writeResponse(ctx, response)

          } else if (request.uri == "/redirect/endpoint") {
            val redirectCookie = Option(request.headers.get(HttpHeaderNames.COOKIE))
              .map(cookie.ServerCookieDecoder.STRICT.decode)
              .flatMap(_.asScala.collectFirst { case cookie if cookie.name == "RedirectCookie" => cookie.value.toInt })
              .getOrElse(0)

            val newRedirectCookie = new cookie.DefaultCookie("RedirectCookie", (redirectCookie + 1).toString)
            newRedirectCookie.setPath("/redirect")

            val doRedirect = redirectCookie % 2 == 0

            val statusCode = if (doRedirect) HttpResponseStatus.FOUND else HttpResponseStatus.OK
            val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, statusCode)
            response.headers.add(HttpHeaderNames.SET_COOKIE, cookie.ServerCookieEncoder.STRICT.encode(newRedirectCookie))

            if (doRedirect) {
              response.headers.add(HttpHeaderNames.LOCATION, "/redirect/endpoint")
            }

            writeResponse(ctx, response)

          } else {
            request.uri match {
              case Content.HelloWorld.path => writeResponse(ctx, request, Content.HelloWorld)
              case Content.Json100.path => writeResponse(ctx, request, Content.Json100)
              case Content.Json250.path => writeResponse(ctx, request, Content.Json250)
              case Content.Json500.path => writeResponse(ctx, request, Content.Json500)
              case Content.Json1k.path => writeResponse(ctx, request, Content.Json1k)
              case Content.Json10k.path => writeResponse(ctx, request, Content.Json10k)
              case Content.Html46k.path => writeResponse(ctx, request, Content.Html46k)
              case Content.Html232k.path => writeResponse(ctx, request, Content.Html232k)
              case _ => writeResponse(ctx, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND))
            }
          }

        case _ =>
          logger.error(s"Read unexpected msg=$msg")
      }
    } finally {
      ReferenceCountUtil.release(msg)
    }
}

final class Http(clearPort: Int, securedPort: Int, sslContext: SslContext, readIdleTimeout: FiniteDuration) extends StrictLogging {

  private def httpChannelInitializer(sslContext: Option[SslContext]): ChannelInitializer[Channel] =
    (ch: Channel) => {
      val pipeline = ch.pipeline
      sslContext.foreach(ssl => pipeline.addLast(ssl.newHandler(ch.alloc, ForkJoinPool.commonPool())))
      pipeline
        .addLast("idleTimer", new CloseOnIdleReadTimeoutHandler(readIdleTimeout))
        .addLast("decoder", new HttpRequestDecoder(4096, 8192, 8192, false))
        .addLast("aggregator", new HttpObjectAggregator(30000))
        .addLast("encoder", new HttpResponseEncoder)
        .addLast("handler", AppHandler)
    }

  def boot(bootstrap: ServerBootstrap): Seq[ChannelFuture] =
    Seq(
      bootstrap.clone.childHandler(httpChannelInitializer(None)).bind(new InetSocketAddress(clearPort)).sync,
      bootstrap.clone.childHandler(httpChannelInitializer(Some(sslContext))).bind(new InetSocketAddress(securedPort)).sync
    )
}
