import java.net.InetSocketAddress

import com.typesafe.scalalogging.StrictLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled.{copiedBuffer, unreleasableBuffer}
import io.netty.buffer.{ByteBuf, ByteBufUtil, Unpooled}
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpServerUpgradeHandler.{UpgradeCodec, UpgradeCodecFactory}
import io.netty.handler.codec.http._
import io.netty.handler.codec.http2._
import io.netty.handler.ssl.{ApplicationProtocolNames, ApplicationProtocolNegotiationHandler, SslContext}
import io.netty.util.{AsciiString, CharsetUtil, ReferenceCountUtil}

final class Http2(clearPort: Int, securedPort: Int, sslContext: SslContext) extends StrictLogging {

  private final val ResponseBytes = unreleasableBuffer(
    copiedBuffer("Hello World", CharsetUtil.UTF_8)
  ).asReadOnly

  private class Http1Handler(establishApproach: String) extends SimpleChannelInboundHandler[FullHttpRequest] {
    override def channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest): Unit = {
      if (HttpUtil.is100ContinueExpected(req)) {
        ctx.write(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER))
      }

      val content = ctx.alloc().buffer()
      content.writeBytes(ResponseBytes.duplicate())
      ByteBufUtil.writeAscii(content, " - via " + req.protocolVersion() + " (" + establishApproach + ")")

      val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
      response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())

      val keepAlive = HttpUtil.isKeepAlive(req)
      if (keepAlive) {
        if (req.protocolVersion().equals(HttpVersion.HTTP_1_0)) {
          response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        }
        ctx.write(response)
      } else {
        // Tell the client we're going to close the connection.
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        ctx.write(response).addListener(ChannelFutureListener.CLOSE)
      }
    }

    override def channelReadComplete(ctx: ChannelHandlerContext): Unit =
      ctx.flush()

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
      cause.printStackTrace()
      ctx.close()
    }
  }

  private class Http2OrHttpHandler extends ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
    override def configurePipeline(ctx: ChannelHandlerContext, protocol: String): Unit =
      if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
        ctx.pipeline().addLast(Http2FrameCodecBuilder.forServer().build(), new Http2Handler)
      } else if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
        ctx
          .pipeline()
          .addLast(
            new HttpServerCodec(),
            new HttpObjectAggregator(1024 * 100),
            new Http1Handler("ALPN Negotiation")
          )
      } else {
        throw new IllegalStateException("Unknown protocol: " + protocol)
      }
  }

  private final class Http2Handler extends ChannelDuplexHandler {

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
      super.exceptionCaught(ctx, cause)
      logger.error("exceptionCaught", cause)
      ctx.close()
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
      msg match {
        case headers: Http2HeadersFrame => onHeadersRead(ctx, headers)
        case data: Http2DataFrame       => onDataRead(ctx, data)
        case _                          => super.channelRead(ctx, msg)
      }

    override def channelReadComplete(ctx: ChannelHandlerContext): Unit =
      ctx.flush()

    private def onDataRead(ctx: ChannelHandlerContext, data: Http2DataFrame): Unit = {
      val stream = data.stream()
      if (data.isEndStream) {
        sendResponse(ctx, stream, data.content())
      } else {
        data.release()
      }

      ctx.write(new DefaultHttp2WindowUpdateFrame(data.initialFlowControlledBytes()).stream(stream))
    }

    private def onHeadersRead(ctx: ChannelHandlerContext, headers: Http2HeadersFrame): Unit =
      if (headers.isEndStream) {
        val content = ctx.alloc().buffer()
        content.writeBytes(ResponseBytes.duplicate())
        ByteBufUtil.writeAscii(content, " - via HTTP/2")
        sendResponse(ctx, headers.stream(), content)
      }

    private def sendResponse(ctx: ChannelHandlerContext, stream: Http2FrameStream, payload: ByteBuf): Unit = {
      // Send a frame for the response status
      val headers = new DefaultHttp2Headers().status(HttpResponseStatus.OK.codeAsText())
      ctx.write(new DefaultHttp2HeadersFrame(headers).stream(stream))
      ctx.write(new DefaultHttp2DataFrame(payload, true).stream(stream))
    }
  }

  private def upgradeCodecFactory =
    new UpgradeCodecFactory {
      override def newUpgradeCodec(protocol: CharSequence): UpgradeCodec =
        if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
          new Http2ServerUpgradeCodec(
            Http2FrameCodecBuilder.forServer.build(),
            new Http2Handler
          )
        } else null
    }

  private def userEventLogger: ChannelInboundHandlerAdapter =
    new ChannelInboundHandlerAdapter {
      override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
        logger.debug("user event triggered: " + evt)
        ctx.fireUserEventTriggered(evt)
      }
    }

  private def http2ChannelInitializer(sslContext: Option[SslContext]): ChannelInitializer[SocketChannel] =
    (ch: SocketChannel) => {
      sslContext match {
        case Some(sslContext) =>
          ch.pipeline().addLast(sslContext.newHandler(ch.alloc), new Http2OrHttpHandler)
        case _ =>
          val p = ch.pipeline
          val sourceCodec = new HttpServerCodec

          p.addLast(sourceCodec)
          p.addLast(new HttpServerUpgradeHandler(sourceCodec, upgradeCodecFactory))
          p.addLast(new SimpleChannelInboundHandler[HttpMessage] {
            override def channelRead0(ctx: ChannelHandlerContext, msg: HttpMessage): Unit = {
              // If this handler is hit then no upgrade has been attempted and the client is just talking HTTP.
              logger.debug("Directly talking: " + msg.protocolVersion + " (no upgrade was attempted)")
              val pipeline = ctx.pipeline
              pipeline.addAfter(ctx.name, null, new Http1Handler("Direct. No Upgrade Attempted."))
              pipeline.replace(this, null, new HttpObjectAggregator(16 * 1024))
              ctx.fireChannelRead(ReferenceCountUtil.retain(msg))
            }
          })
          p.addLast(userEventLogger)
      }
    }

  def boot(bootstrap: ServerBootstrap): Seq[ChannelFuture] =
    Seq(
      bootstrap.clone.childHandler(http2ChannelInitializer(None)).bind(new InetSocketAddress(clearPort)).sync,
      bootstrap.clone.childHandler(http2ChannelInitializer(Some(sslContext))).bind(new InetSocketAddress(securedPort)).sync
    )
}
