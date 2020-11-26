import java.util.concurrent.TimeUnit

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.timeout.{IdleState, IdleStateEvent, IdleStateHandler}

import scala.concurrent.duration.FiniteDuration

class CloseOnIdleReadTimeoutHandler(readerIdleTimeOut: FiniteDuration) extends IdleStateHandler(readerIdleTimeOut.toSeconds, 65, 65, TimeUnit.SECONDS) {

  override def channelIdle(ctx: ChannelHandlerContext, evt: IdleStateEvent): Unit =
    evt.state match {
      case IdleState.READER_IDLE => ctx.close()
      case _                     =>
    }
}
