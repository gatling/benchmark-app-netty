import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.StrictLogging
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.timeout.{IdleState, IdleStateEvent, IdleStateHandler}

import scala.concurrent.duration.FiniteDuration

class CloseOnIdleReadTimeoutHandler(readerIdleTimeOut: FiniteDuration) extends IdleStateHandler(readerIdleTimeOut.toSeconds, 65, 65, TimeUnit.SECONDS) with StrictLogging {

  override def channelIdle(ctx: ChannelHandlerContext, evt: IdleStateEvent): Unit =
    evt.state match {
      case IdleState.READER_IDLE =>
        logger.info("Reader idle => closing")
        ctx.close()
      case _                     =>
        logger.info("Writer idle => closing")
        ctx.close()
    }
}
