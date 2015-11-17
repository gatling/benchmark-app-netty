import java.util.concurrent.TimeUnit

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.timeout.{ IdleState, IdleStateEvent, IdleStateHandler }

class CloseOnIdleReadTimeoutHandler(readerIdleTime: Long) extends IdleStateHandler(readerIdleTime, 0L, 0L, TimeUnit.SECONDS) {

  override def channelIdle(ctx: ChannelHandlerContext, evt: IdleStateEvent): Unit =
    evt.state match {
      case IdleState.READER_IDLE => ctx.close()
      case _                     =>
    }
}
