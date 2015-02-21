import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.slf4j.StrictLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.{ ChannelHandlerContext, ChannelInboundHandlerAdapter, Channel, ChannelInitializer }
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http._
import HttpHeaders.Names._
import HttpHeaders.Values._
import io.netty.handler.timeout.{ IdleState, IdleStateEvent, IdleStateHandler }
import io.netty.util.ResourceLeakDetector
import io.netty.util.internal.logging.{ Slf4JLoggerFactory, InternalLoggerFactory }

import scala.io.{ Source, Codec }

object Test extends StrictLogging {

  val classLoader = getClass.getClassLoader

  implicit val codec = Codec.UTF8
  val smallJson = resourceAsBytes("json/small.json")
  val smallXml = resourceAsBytes("xml/small.xml")
  val smallHtml = resourceAsBytes("html/small.html")
  val mediumJson = resourceAsBytes("json/medium.json")
  val mediumXml = resourceAsBytes("xml/medium.xml")
  val mediumHtml = resourceAsBytes("html/medium.html")
  val largeJson = resourceAsBytes("json/large.json")
  val largeXml = resourceAsBytes("xml/large.xml")

  def resourceAsBytes(path: String) = {
    val source = Source.fromInputStream(classLoader.getResourceAsStream(path))
    try {
      source.mkString.getBytes(codec.charSet)
    } finally {
      source.close()
    }
  }

  def main(args: Array[String]): Unit = {

    InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED)

    val bossGroup = new NioEventLoopGroup
    val workerGroup = new NioEventLoopGroup

    val bootstrap = new ServerBootstrap()
      .group(bossGroup, workerGroup)
      .channel(classOf[NioServerSocketChannel])
      .childHandler(new ChannelInitializer[Channel] {
        override def initChannel(ch: Channel): Unit = {
          ch.pipeline()
            .addLast("decoder", new HttpRequestDecoder)
            .addLast("aggregator", new HttpObjectAggregator(30000))
            .addLast("encoder", new HttpResponseEncoder)
            .addLast("compressor", new HttpContentCompressor)
            .addLast("idleStateHandler", new IdleStateHandler(5, 0, 0))
            .addLast("handler", new ChannelInboundHandlerAdapter {

              override def userEventTriggered(ctx: ChannelHandlerContext, evt: AnyRef): Unit =
                evt match {
                  case e: IdleStateEvent if e.state == IdleState.READER_IDLE => ctx.close()
                  case _ =>
                }

              override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit =
                msg match {
                  case request: FullHttpRequest =>
                    request.content.release()
                    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(smallJson))
                    response.headers
                      .set(CONTENT_TYPE, "application/json")
                      .set(CONTENT_LENGTH, response.content.readableBytes)
                      .set(CONNECTION, KEEP_ALIVE)

                    val queryStringDecoder = new QueryStringDecoder(request.getUri)

                    Option(queryStringDecoder.parameters.get("latency")).map(_.get(0).toInt) match {
                      case Some(latency) =>
                        ctx.executor.schedule(new Runnable {
                          override def run(): Unit = {
                            ctx.writeAndFlush(response)
                            logger.debug(s"wrote response=$response after expected ${latency}ms")
                          }
                        }, latency, TimeUnit.MILLISECONDS)

                      case _ =>
                        ctx.writeAndFlush(response)
                        logger.debug(s"wrote response=$response")
                    }

                  case _ =>
                    logger.error(s"Read unexpected msg=$msg")
                }
            })
        }
      })

    val f = bootstrap.bind(new InetSocketAddress(8000)).sync
    f.channel.closeFuture.sync
    logger.info("stopping")
    bossGroup.shutdownGracefully()
    workerGroup.shutdownGracefully()
  }
}
