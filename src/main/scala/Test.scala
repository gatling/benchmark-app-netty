import java.io.{ByteArrayOutputStream, IOException}
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

import com.typesafe.scalalogging.slf4j.StrictLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel._
import io.netty.channel.epoll.{EpollEventLoopGroup, EpollServerSocketChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.ServerSocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http._
import HttpHeaders.Names._
import HttpHeaders.Values._
import io.netty.handler.timeout.{ IdleState, IdleStateEvent }
import io.netty.util._
import io.netty.util.internal.logging.{InternalLoggerFactory, Slf4JLoggerFactory}
import scala.io.{Codec, Source}

object Test extends StrictLogging {

  private val TextPlainContentType = HttpHeaders.newEntity("text/plain")
  private val ApplicationJsonContentType = HttpHeaders.newEntity("application/json")

  implicit val codec = Codec.UTF8

  private val port = 8000

  private val VsctJson =
    """{"status":"SUCCESS","comment":null,"appModifier":"TRN","idTech":234224,"login":"JeanPierreToto9@yopmail.com","lastConnexionDate":null,"lastBlockDate":null,"appCreator":null,"title":"mr","lastName":"Leboucher","firstName":"Sebastien","email":"JeanPierreToto9@yopmail.com","address":"37 rue des sazières","address2":"chemin","zip":"92700","city":"Elbeuf","souscription":"Navigo_Annuel","cardType":"Enfans_Familles_Nombreuses","zone":"ZONE1_3","inscriptionNewsletter":true,"cgu":true,"blockFlag":false,"codePays":"FRANCE","codeLanguage":"FRANCAIS","creationDate":1466664699000,"lastModificationDate":1478526894000,"birthdate":null,"phoneNumber":null,"inscriptionPanel":true,"mobility":null,"transportation":null,"pmrPref":null,"accesPlus":true,"infosMedia":{}}"""

  private val Json1k =
    """{"flavors":[
      |{"id":"1","links":[{"href":"http://openstack.example.com/v2/openstack/flavors/1","rel":"self"},{"href":"http://openstack.example.com/openstack/flavors/1","rel":"bookmark"}],"name":"m1.tiny"},
      |{"id":"2","links":[{"href":"http://openstack.example.com/v2/openstack/flavors/2","rel":"self"},{"href":"http://openstack.example.com/openstack/flavors/2","rel":"bookmark"}],"name":"m1.small"},
      |{"id":"3","links":[{"href":"http://openstack.example.com/v2/openstack/flavors/3","rel":"self"},{"href":"http://openstack.example.com/openstack/flavors/3","rel":"bookmark"}],"name":"m1.medium"},
      |{"id":"4","links":[{"href":"http://openstack.example.com/v2/openstack/flavors/4","rel":"self"},{"href":"http://openstack.example.com/openstack/flavors/4","rel":"bookmark"}],"name":"m1.large"},
      |{"id":"5","links":[{"href":"http://openstack.example.com/v2/openstack/flavors/5","rel":"self"},{"href":"http://openstack.example.com/openstack/flavors/5","rel":"bookmark"}],"name":"m1.xlarge"}]}""".stripMargin

  // 13330
  private val Json10k =
    """{
      |"flavors":[
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "class":"standard1",
      |         "disk_io_index":"2",
      |         "number_of_data_disks":"0"
      |      },
      |      "name":"512MB Standard Instance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/2",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/2",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":512,
      |      "vcpus":1,
      |      "swap":512,
      |      "rxtx_factor":80.0,
      |      "OS-FLV-EXT-DATA:ephemeral":0,
      |      "disk":20,
      |      "id":"2"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "class":"standard1",
      |         "disk_io_index":"2",
      |         "number_of_data_disks":"0"
      |      },
      |      "name":"1GB Standard Instance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/3",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/3",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":1024,
      |      "vcpus":1,
      |      "swap":1024,
      |      "rxtx_factor":120.0,
      |      "OS-FLV-EXT-DATA:ephemeral":0,
      |      "disk":40,
      |      "id":"3"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "class":"standard1",
      |         "disk_io_index":"2",
      |         "number_of_data_disks":"0"
      |      },
      |      "name":"2GB Standard Instance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/4",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/4",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":2048,
      |      "vcpus":2,
      |      "swap":2048,
      |      "rxtx_factor":240.0,
      |      "OS-FLV-EXT-DATA:ephemeral":0,
      |      "disk":80,
      |      "id":"4"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "class":"standard1",
      |         "disk_io_index":"2",
      |         "number_of_data_disks":"0"
      |      },
      |      "name":"4GB Standard Instance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/5",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/5",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":4096,
      |      "vcpus":2,
      |      "swap":2048,
      |      "rxtx_factor":400.0,
      |      "OS-FLV-EXT-DATA:ephemeral":0,
      |      "disk":160,
      |      "id":"5"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "class":"standard1",
      |         "disk_io_index":"2",
      |         "number_of_data_disks":"0"
      |      },
      |      "name":"8GB Standard Instance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/6",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/6",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":8192,
      |      "vcpus":4,
      |      "swap":2048,
      |      "rxtx_factor":600.0,
      |      "OS-FLV-EXT-DATA:ephemeral":0,
      |      "disk":320,
      |      "id":"6"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "class":"standard1",
      |         "disk_io_index":"2",
      |         "number_of_data_disks":"0"
      |      },
      |      "name":"15GB Standard Instance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/7",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/7",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":15360,
      |      "vcpus":6,
      |      "swap":2048,
      |      "rxtx_factor":800.0,
      |      "OS-FLV-EXT-DATA:ephemeral":0,
      |      "disk":620,
      |      "id":"7"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "class":"standard1",
      |         "disk_io_index":"2",
      |         "number_of_data_disks":"0"
      |      },
      |      "name":"30GB Standard Instance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/8",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/8",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":30720,
      |      "vcpus":8,
      |      "swap":2048,
      |      "rxtx_factor":1200.0,
      |      "OS-FLV-EXT-DATA:ephemeral":0,
      |      "disk":1200,
      |      "id":"8"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "quota_resources":"instances=onmetal-compute-v1-instances,ram=onmetal-compute-v1-ram",
      |         "class":"onmetal",
      |         "policy_class":"onmetal_flavor"
      |      },
      |      "name":"OnMetal Compute v1",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/onmetal-compute1",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/onmetal-compute1",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":32768,
      |      "vcpus":20,
      |      "swap":"",
      |      "rxtx_factor":20000.0,
      |      "OS-FLV-EXT-DATA:ephemeral":0,
      |      "disk":32,
      |      "id":"onmetal-compute1"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "quota_resources":"instances=onmetal-io-v1-instances,ram=onmetal-io-v1-ram",
      |         "class":"onmetal",
      |         "policy_class":"onmetal_flavor"
      |      },
      |      "name":"OnMetal I/O v1",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/onmetal-io1",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/onmetal-io1",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":131072,
      |      "vcpus":40,
      |      "swap":"",
      |      "rxtx_factor":20000.0,
      |      "OS-FLV-EXT-DATA:ephemeral":3200,
      |      "disk":32,
      |      "id":"onmetal-io1"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "quota_resources":"instances=onmetal-memory-v1-instances,ram=onmetal-memory-v1-ram",
      |         "class":"onmetal",
      |         "policy_class":"onmetal_flavor"
      |      },
      |      "name":"OnMetal Memory v1",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/onmetal-memory1",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/onmetal-memory1",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":524288,
      |      "vcpus":24,
      |      "swap":"",
      |      "rxtx_factor":20000.0,
      |      "OS-FLV-EXT-DATA:ephemeral":0,
      |      "disk":32,
      |      "id":"onmetal-memory1"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "resize_policy_class":"performance_flavor",
      |         "class":"performance1",
      |         "disk_io_index":"40",
      |         "number_of_data_disks":"0"
      |      },
      |      "name":"1 GB Performance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/performance1-1",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/performance1-1",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":1024,
      |      "vcpus":1,
      |      "swap":"",
      |      "rxtx_factor":200.0,
      |      "OS-FLV-EXT-DATA:ephemeral":0,
      |      "disk":20,
      |      "id":"performance1-1"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "resize_policy_class":"performance_flavor",
      |         "class":"performance1",
      |         "disk_io_index":"40",
      |         "number_of_data_disks":"1"
      |      },
      |      "name":"2 GB Performance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/performance1-2",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/performance1-2",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":2048,
      |      "vcpus":2,
      |      "swap":"",
      |      "rxtx_factor":400.0,
      |      "OS-FLV-EXT-DATA:ephemeral":20,
      |      "disk":40,
      |      "id":"performance1-2"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "resize_policy_class":"performance_flavor",
      |         "class":"performance1",
      |         "disk_io_index":"40",
      |         "number_of_data_disks":"1"
      |      },
      |      "name":"4 GB Performance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/performance1-4",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/performance1-4",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":4096,
      |      "vcpus":4,
      |      "swap":"",
      |      "rxtx_factor":800.0,
      |      "OS-FLV-EXT-DATA:ephemeral":40,
      |      "disk":40,
      |      "id":"performance1-4"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "resize_policy_class":"performance_flavor",
      |         "class":"performance1",
      |         "disk_io_index":"40",
      |         "number_of_data_disks":"1"
      |      },
      |      "name":"8 GB Performance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/performance1-8",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/performance1-8",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":8192,
      |      "vcpus":8,
      |      "swap":"",
      |      "rxtx_factor":1600.0,
      |      "OS-FLV-EXT-DATA:ephemeral":80,
      |      "disk":40,
      |      "id":"performance1-8"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "resize_policy_class":"performance_flavor",
      |         "class":"performance2",
      |         "disk_io_index":"80",
      |         "number_of_data_disks":"4"
      |      },
      |      "name":"120 GB Performance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/performance2-120",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/performance2-120",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":122880,
      |      "vcpus":32,
      |      "swap":"",
      |      "rxtx_factor":10000.0,
      |      "OS-FLV-EXT-DATA:ephemeral":1200,
      |      "disk":40,
      |      "id":"performance2-120"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "resize_policy_class":"performance_flavor",
      |         "class":"performance2",
      |         "disk_io_index":"40",
      |         "number_of_data_disks":"1"
      |      },
      |      "name":"15 GB Performance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/performance2-15",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/performance2-15",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":15360,
      |      "vcpus":4,
      |      "swap":"",
      |      "rxtx_factor":1250.0,
      |      "OS-FLV-EXT-DATA:ephemeral":150,
      |      "disk":40,
      |      "id":"performance2-15"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "resize_policy_class":"performance_flavor",
      |         "class":"performance2",
      |         "disk_io_index":"40",
      |         "number_of_data_disks":"1"
      |      },
      |      "name":"30 GB Performance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/performance2-30",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/performance2-30",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":30720,
      |      "vcpus":8,
      |      "swap":"",
      |      "rxtx_factor":2500.0,
      |      "OS-FLV-EXT-DATA:ephemeral":300,
      |      "disk":40,
      |      "id":"performance2-30"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "resize_policy_class":"performance_flavor",
      |         "class":"performance2",
      |         "disk_io_index":"60",
      |         "number_of_data_disks":"2"
      |      },
      |      "name":"60 GB Performance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/performance2-60",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/performance2-60",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":61440,
      |      "vcpus":16,
      |      "swap":"",
      |      "rxtx_factor":5000.0,
      |      "OS-FLV-EXT-DATA:ephemeral":600,
      |      "disk":40,
      |      "id":"performance2-60"
      |   },
      |   {
      |      "OS-FLV-WITH-EXT-SPECS:extra_specs":{
      |         "resize_policy_class":"performance_flavor",
      |         "class":"performance2",
      |         "disk_io_index":"70",
      |         "number_of_data_disks":"3"
      |      },
      |      "name":"90 GB Performance",
      |      "links":[
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/v2/728975/flavors/performance2-90",
      |            "rel":"self"
      |         },
      |         {
      |            "href":"https://iad.servers.api.rackspacecloud.com/728975/flavors/performance2-90",
      |            "rel":"bookmark"
      |         }
      |      ],
      |      "ram":92160,
      |      "vcpus":24,
      |      "swap":"",
      |      "rxtx_factor":7500.0,
      |      "OS-FLV-EXT-DATA:ephemeral":900,
      |      "disk":40,
      |      "id":"performance2-90"
      |   }
      |]
      |}""".stripMargin

  private val Json100k = Iterable.fill(10)(Json10k).mkString("[", ", ", "]")

  private val Json1M = Iterable.fill(10)(Json100k).mkString("[", ", ", "]")

  case class Content(text: String, contentType: CharSequence) {

    val rawBytes = text.getBytes(codec.charSet)

    val compressedBytes: Array[Byte] = {
        val baos = new ByteArrayOutputStream
        val gzip = new GZIPOutputStream(baos)
        gzip.write(rawBytes)
        gzip.close()
        baos.toByteArray
    }
  }

  private val HelloWorldContent = Content("Hello, World!", TextPlainContentType)
  private val VsctJsonContent = Content(VsctJson, ApplicationJsonContentType)
  private val Json1kContent = Content(Json1k, ApplicationJsonContentType)
  private val Json10kContent = Content(Json10k, ApplicationJsonContentType)
  private val Json100kContent = Content(Json100k, ApplicationJsonContentType)
  private val Json1MContent = Content(Json1M, ApplicationJsonContentType)


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
        timer.newTimeout(new TimerTask {
          override def run(timeout: Timeout): Unit =
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

    InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE)

    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED)
    val useNativeTransport = java.lang.Boolean.getBoolean("gatling.useNativeTransport")

    val bossGroup = if (useNativeTransport) new EpollEventLoopGroup else new NioEventLoopGroup
    val workerGroup = if (useNativeTransport) new EpollEventLoopGroup else new NioEventLoopGroup

    val channelClass: Class[_ <: ServerSocketChannel] = if (useNativeTransport) classOf[EpollServerSocketChannel] else classOf[NioServerSocketChannel]

    val timer = new HashedWheelTimer(10, TimeUnit.MILLISECONDS)
    timer.start()

    val bootstrap = new ServerBootstrap()
      .option[Integer](ChannelOption.SO_BACKLOG, 2 * 1024)
      .group(bossGroup, workerGroup)
      .channel(channelClass)
      .childHandler(new ChannelInitializer[Channel] {
        override def initChannel(ch: Channel): Unit = {
          ch.pipeline()
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
                  case request: FullHttpRequest if request.getUri == "/echo" =>
                    val content = request.content()
                    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
                    response.headers().add(HttpHeaders.Names.CONTENT_LENGTH, content.readableBytes)
                    writeResponse(ctx, response)

                  case request: FullHttpRequest =>
                    ReferenceCountUtil.release(request) // FIXME is this necessary?

                    request.getUri match {
                      case "/hello" => writeResponse(ctx, request, HelloWorldContent, timer)
                      case "/json1k" => writeResponse(ctx, request, Json1kContent, timer)
                      case "/json10k" => writeResponse(ctx, request, Json10kContent, timer)
                      case "/json100k" =>  writeResponse(ctx, request, Json100kContent, timer)
                      case "/json1M" =>  writeResponse(ctx, request, Json1MContent, timer)
                      case "/vsct" => writeResponse(ctx, request, VsctJsonContent, timer)

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
