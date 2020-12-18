import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel._
import io.netty.channel.epoll.{EpollEventLoopGroup, EpollServerSocketChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.ServerSocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.handler.ssl.{SslContextBuilder, SslProvider}
import io.netty.util._
import io.netty.util.internal.PlatformDependent

import scala.concurrent.duration.DurationInt

object Server extends StrictLogging {

  def main(args: Array[String]): Unit = {
    logger.info(s"os.name ${System.getProperty("os.name")}")
    logger.info(s"os.version ${System.getProperty("os.version")}")

    val config = ConfigFactory.load()
    val httpPort = config.getInt("http.ports.http")
    val httpsPort = config.getInt("http.ports.https")
    val idleTimeout = config.getInt("http.idle") millis
    val wsPort = config.getInt("ws.ports.ws")
    val wssPort = config.getInt("ws.ports.wss")

    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED)
    val useNativeTransport = !PlatformDependent.isOsx && !PlatformDependent.isWindows

    val channelClass: Class[_ <: ServerSocketChannel] = if (useNativeTransport) classOf[EpollServerSocketChannel] else classOf[NioServerSocketChannel]
    val bossGroup = if (useNativeTransport) new EpollEventLoopGroup else new NioEventLoopGroup
    val workerGroup = if (useNativeTransport) new EpollEventLoopGroup else new NioEventLoopGroup

    val sslContext = {
      val cert = new SelfSignedCertificate
      SslContextBuilder
        .forServer(cert.certificate, cert.privateKey)
        .sslProvider(SslProvider.OPENSSL)
        .protocols("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1")
        .build()
    }

    val bootstrap = new ServerBootstrap()
      .option[Integer](ChannelOption.SO_BACKLOG, 2 * 1024)
      .group(bossGroup, workerGroup)
      .channel(channelClass)

    val allWhenClose = new Http(httpPort, httpsPort, sslContext, idleTimeout).boot(bootstrap) ++ new Ws(wsPort, wssPort, sslContext).boot(bootstrap)

    logger.info(
      s"""Server started on ports $httpPort (HTTP), $httpsPort (HTTPS), $wsPort (WS) and $wssPort (WSS)
         |
         |HTTP:
         |=====
         |* /echo
         |* /redirect/endpoint
         |* /txt/hello.txt
         |* /json/(100|250|500|1k|10k).json
         |* /html/(46k|232k).html
         |
         |* "accept-encoding" header controls gzip
         |* "X-Delay" header controls delay (Int millis)
         |* "X-UseLogNormalDelay" header controls using log normal distribution for delay instead of constant value
         |
         |WebSocket:
         |==========
         |
         |* path = "/"
         |* "echo" reply immediately with "echo"
         |* "multiple?times=(.*)" replies multiple times with response1, response2...
         |* "close?delay=(.*)" closes after some delay (Int millis)
         |""".stripMargin)

    allWhenClose.foreach(_.channel.closeFuture.sync)

    logger.info("stopping")
    bossGroup.shutdownGracefully()
    workerGroup.shutdownGracefully()
  }
}
