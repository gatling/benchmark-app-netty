import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.StrictLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel._
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx._
import io.netty.handler.ssl.SslContext
import io.netty.util.ReferenceCountUtil

class Ws(clearPort: Int, securedPort: Int, sslContext: SslContext) extends StrictLogging {

  private val multipleTimes = "multiple\\?times=(.*)".r

  private val crashAfterDelay = "close\\?delay=(.*)".r

  private def wsChannelInitializer(sslContext: Option[SslContext]): ChannelInitializer[Channel] =
    (ch: Channel) => {
      val pipeline = ch.pipeline
      sslContext.foreach(ssl => pipeline.addLast(ssl.newHandler(ch.alloc)))
      pipeline
        .addLast(new HttpServerCodec())
        .addLast(new HttpObjectAggregator(65536))
        .addLast(new WebSocketServerProtocolHandler("/", null, true))
        .addLast("handler", new ChannelInboundHandlerAdapter {

          override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = cause match {
            case ioe: IOException =>
              val root = if (ioe.getCause != null) ioe.getCause else ioe
              if (!(root.getMessage != null && root.getMessage.endsWith("Connection reset by peer"))) {
                // ignore, this is just client aborting
                logger.error("exceptionCaught", ioe)
              }
              ctx.channel.close()
            case _ => ctx.fireExceptionCaught(cause)
          }

          override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit =
          try {
            msg match {
              case txtFrame: TextWebSocketFrame =>
                val txt = txtFrame.text
                logger.debug(s"Received TextWebSocketFrame=$txt")

                txt match {
                  case "echo" =>
                    logger.info("Replying echo")
                    ctx.writeAndFlush(new TextWebSocketFrame("echo"))

                  case multipleTimes(times) =>
                    val timesInt = times.toInt
                    for (i <- 0 until timesInt) {
                      ctx.executor().schedule(
                        new Runnable {
                          override def run(): Unit = {
                            val resp = s"response${i + 1}"
                            logger.info(s"Replying $resp")
                            ctx.writeAndFlush(new TextWebSocketFrame(resp))
                          }
                        },
                        i,
                        TimeUnit.SECONDS
                      )
                    }

                  case "close" =>
                    logger.info("Closing WebSocket")
                    ctx.close()

                  case crashAfterDelay(delayString) =>
                    val delay = delayString.toInt
                    ctx.writeAndFlush(new TextWebSocketFrame("close"))
                    ctx.executor().schedule(
                      new Runnable {
                        override def run(): Unit = {
                          logger.info("Closing WebSocket after delay")
                          ctx.close()
                        }
                      },
                      delay,
                      TimeUnit.MILLISECONDS
                    )

                  case _ =>
                    logger.error(s"Unknown text msg=$txt")
                }

              case _ =>
                logger.error(s"Read unexpected msg=$msg")
            }
          } finally {
            ReferenceCountUtil.release(msg)
          }
        })
    }

  def boot(bootstrap: ServerBootstrap): Seq[ChannelFuture] =
    Seq(
      bootstrap.clone.childHandler(wsChannelInitializer(None)).bind(new InetSocketAddress(clearPort)).sync,
      bootstrap.clone.childHandler(wsChannelInitializer(Some(sslContext))).bind(new InetSocketAddress(securedPort)).sync
    )
}
