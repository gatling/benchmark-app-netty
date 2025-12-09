import scala.concurrent.duration.DurationInt

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel._
import io.netty.channel.epoll.{ Epoll, EpollIoHandler, EpollServerSocketChannel }
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.ServerSocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.uring.{ IoUring, IoUringIoHandler, IoUringServerSocketChannel }
import io.netty.handler.codec.http2.Http2SecurityUtil
import io.netty.handler.ssl.{ ApplicationProtocolConfig, ApplicationProtocolNames, SslContextBuilder, SslProvider, SupportedCipherSuiteFilter }
import io.netty.handler.ssl.ApplicationProtocolConfig.{ Protocol, SelectedListenerFailureBehavior, SelectorFailureBehavior }
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.util._

object Server extends StrictLogging {

  def main(args: Array[String]): Unit = {
    logger.info(s"os.name ${System.getProperty("os.name")}")
    logger.info(s"os.version ${System.getProperty("os.version")}")

    val config = ConfigFactory.load()
    val httpPort = config.getInt("http.ports.http")
    val httpsPort = config.getInt("http.ports.https")
    val http2ClearPort = config.getInt("http2.ports.clear")
    val http2SecuredPort = config.getInt("http2.ports.secured")
    val idleTimeout = config.getInt("http.idle") millis
    val wsPort = config.getInt("ws.ports.ws")
    val wssPort = config.getInt("ws.ports.wss")
    val useEpoll = config.getBoolean("transport.epoll") && Epoll.isAvailable
    val useIoUring = config.getBoolean("transport.iouring") && IoUring.isAvailable
    val useBoringSsl = config.getBoolean("tls.boringssl")

    if (useEpoll) {
      Epoll.ensureAvailability()
    } else if (useIoUring) {
      IoUring.ensureAvailability()
    }

    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED)

    val channelClass: Class[_ <: ServerSocketChannel] =
      if (useEpoll) classOf[EpollServerSocketChannel]
      else if (useIoUring) classOf[IoUringServerSocketChannel]
      else classOf[NioServerSocketChannel]

    val ioHandlerFactory =
      if (useEpoll) EpollIoHandler.newFactory
      else if (useIoUring) IoUringIoHandler.newFactory
      else NioIoHandler.newFactory

    val bossGroup = new MultiThreadIoEventLoopGroup(1, ioHandlerFactory)
    val workerGroup = new MultiThreadIoEventLoopGroup(Runtime.getRuntime.availableProcessors, ioHandlerFactory)

    val transportName =
      if (useEpoll) "epoll"
      else if (useIoUring) "iouring"
      else "nio"

    val cert = new SelfSignedCertificate
    val sslContext =
      SslContextBuilder
        .forServer(cert.certificate, cert.privateKey)
        .sslProvider(if (useBoringSsl) SslProvider.OPENSSL else SslProvider.JDK)
        .protocols("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1")
        .build()
    val http2SslContext =
      SslContextBuilder
        .forServer(cert.certificate, cert.privateKey)
        .sslProvider(SslProvider.OPENSSL)
        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
        .applicationProtocolConfig(
          new ApplicationProtocolConfig(
            Protocol.ALPN,
            SelectorFailureBehavior.NO_ADVERTISE,
            SelectedListenerFailureBehavior.ACCEPT,
            ApplicationProtocolNames.HTTP_2,
            ApplicationProtocolNames.HTTP_1_1
          )
        )
        .build()

    val bootstrap = new ServerBootstrap()
      .option[Integer](ChannelOption.SO_BACKLOG, 15 * 1024)
      .group(bossGroup, workerGroup)
      .channel(channelClass)

    val allWhenClose =
      new Http(httpPort, httpsPort, sslContext, idleTimeout).boot(bootstrap) ++
        new Http2(http2ClearPort, http2SecuredPort, http2SslContext).boot(bootstrap) ++
        new Ws(wsPort, wssPort, sslContext).boot(bootstrap)

    logger.info(s"""Server started on ports using transport $transportName:
                   |* HTTP/1.1: $httpPort (unsecured), $httpsPort (TLS)
                   |* HTTP/2: $http2ClearPort (H2C), $http2SecuredPort (TLS)
                   |* WebSockets: $wsPort (WS) and $wssPort (WSS)
                   |
                   |HTTP/1.1:
                   |=========
                   |
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
                   |HTTP/2:
                   |=======
                   |
                   |* /echo
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
