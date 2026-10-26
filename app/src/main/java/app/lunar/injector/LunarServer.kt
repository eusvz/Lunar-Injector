package app.lunar.injector
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.protocol.bedrock.BedrockPong
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.data.ResourcePackType
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer
import org.cloudburstmc.protocol.bedrock.packet.*
import org.cloudburstmc.protocol.common.PacketSignal
import java.net.InetSocketAddress

class LunarServer(private val packs: List<Pack>, private val host: String, private val port: Int) {
  private val codec = Bedrock_v2193.CODEC
  private var group: NioEventLoopGroup? = null
  private var channel: Channel? = null
  private val chunk = 128 * 1024

  fun start() {
    val pong = BedrockPong().edition("MCPE").motd("Lunar Injector").subMotd("Join to load packs")
      .playerCount(0).maximumPlayerCount(10).gameType("Survival").nintendoLimited(false)
      .protocolVersion(codec.protocolVersion).version(codec.minecraftVersion)
      .ipv4Port(19132).ipv6Port(19132).serverId(System.nanoTime())
    val g = NioEventLoopGroup(); group = g
    channel = ServerBootstrap()
      .channelFactory(RakChannelFactory.server(NioDatagramChannel::class.java))
      .group(g)
      .option(RakChannelOption.RAK_ADVERTISEMENT, pong.toByteBuf())
      .childHandler(object : BedrockServerInitializer() {
        override fun initSession(session: BedrockServerSession) { session.packetHandler = Handler(session) }
      })
      .bind(InetSocketAddress("0.0.0.0", 19132)).syncUninterruptibly().channel()
  }
  fun stop() { channel?.close(); group?.shutdownGracefully() }

  private inner class Handler(val s: BedrockServerSession) : BedrockPacketHandler {
    override fun handle(p: RequestNetworkSettingsPacket): PacketSignal {
      s.codec = codec
      s.sendPacketImmediately(NetworkSettingsPacket().apply {
        compressionThreshold = 0; compressionAlgorithm = PacketCompressionAlgorithm.ZLIB })
      s.setCompression(PacketCompressionAlgorithm.ZLIB)
      return PacketSignal.HANDLED
    }
    override fun handle(p: LoginPacket): PacketSignal {
      s.sendPacket(PlayStatusPacket().apply { status = PlayStatusPacket.Status.LOGIN_SUCCESS })
      s.sendPacket(ResourcePacksInfoPacket().apply {
        isForcedToAccept = true
        packs.forEach { resourcePackInfos.add(ResourcePacksInfoPacket.Entry(it.uuid, it.version, it.bytes.size.toLong(), "", "", it.id, false, false, false, "")) }
      })
      return PacketSignal.HANDLED
    }
    override fun handle(p: ResourcePackClientResponsePacket): PacketSignal {
      when (p.status) {
        ResourcePackClientResponsePacket.Status.SEND_PACKS -> p.packIds.forEach { id ->
          packs.find { id.startsWith(it.id, true) }?.let { k ->
            s.sendPacket(ResourcePackDataInfoPacket().apply {
              packId = k.uuid; packVersion = k.version; maxChunkSize = chunk.toLong()
              chunkCount = ((k.bytes.size + chunk - 1) / chunk).toLong()
              compressedPackSize = k.bytes.size.toLong(); hash = k.sha; type = ResourcePackType.RESOURCES })
          }
        }
        ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS -> s.sendPacket(ResourcePackStackPacket().apply {
          isForcedToAccept = true; gameVersion = codec.minecraftVersion
          packs.forEach { resourcePacks.add(ResourcePackStackPacket.Entry(it.id, it.version, "")) } })
        ResourcePackClientResponsePacket.Status.COMPLETED ->
          s.sendPacket(TransferPacket().apply { address = host; port = this@LunarServer.port })
        else -> {}
      }
      return PacketSignal.HANDLED
    }
    override fun handle(p: ResourcePackChunkRequestPacket): PacketSignal {
      val k = packs.find { it.uuid == p.packId } ?: return PacketSignal.HANDLED
      val from = p.chunkIndex * chunk; val to = minOf(from + chunk, k.bytes.size)
      s.sendPacket(ResourcePackChunkDataPacket().apply {
        packId = k.uuid; packVersion = k.version; chunkIndex = p.chunkIndex
        progress = from.toLong(); data = Unpooled.wrappedBuffer(k.bytes, from, to - from) })
      return PacketSignal.HANDLED
    }
  }
}
