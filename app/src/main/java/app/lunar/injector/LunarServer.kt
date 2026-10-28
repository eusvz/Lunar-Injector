package app.lunar.injector
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import org.cloudburstmc.math.vector.Vector2f
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.protocol.bedrock.BedrockPong
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225
import org.cloudburstmc.protocol.bedrock.data.GamePublishSetting
import org.cloudburstmc.protocol.bedrock.data.GameType
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.ResourcePackType
import org.cloudburstmc.protocol.bedrock.data.SpawnBiomeType
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer
import org.cloudburstmc.protocol.bedrock.packet.*
import org.cloudburstmc.protocol.common.PacketSignal
import java.lang.reflect.Modifier
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

class LunarServer(private val packs: List<Pack>, private val host: String, private val port: Int) {
  private val latest: BedrockCodec = Bedrock_v2225.CODEC
  private val older: BedrockCodec = Bedrock_v2193.CODEC
  private var group: NioEventLoopGroup? = null
  private var channel: Channel? = null
  private val chunk = 128 * 1024
  @Volatile private var ip: String = host

  private fun log(m: String) {
    LunarState.error.value = (LunarState.error.value.orEmpty() + "\n" + m).trim().lines().takeLast(14).joinToString("\n")
  }
  private fun safe(name: String, f: () -> Unit): PacketSignal {
    try { f() } catch (e: Throwable) { log("ERR $name: ${e.javaClass.simpleName} ${e.message?.take(100)}") }
    return PacketSignal.HANDLED
  }

  /** Fills any empty (null) field of a packet so the library can write it. */
  private fun fillNulls(p: Any) {
    val filled = ArrayList<String>()
    for (f in p.javaClass.declaredFields) {
      if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
      try {
        f.isAccessible = true
        if (f.get(p) != null) continue
        val t = f.type
        val v: Any? = when {
          t == String::class.java -> ""
          t == UUID::class.java -> UUID(0, 0)
          t == NbtMap::class.java -> NbtMap.EMPTY
          List::class.java.isAssignableFrom(t) -> ArrayList<Any>()
          t.isEnum -> t.enumConstants?.firstOrNull()
          else -> runCatching { t.getMethod("empty").invoke(null) }.getOrNull()
            ?: runCatching { t.getField("EMPTY").get(null) }.getOrNull()
        }
        if (v != null) { f.set(p, v); filled.add(f.name) } else log("null: ${f.name}")
      } catch (e: Throwable) { log("fill ${f.name}: ${e.javaClass.simpleName}") }
    }
    if (filled.isNotEmpty()) log("filled " + filled.joinToString())
  }

  fun start() {
    ip = try { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress ?: host } catch (e: Exception) { host }
    log("target $host -> $ip:$port")
    val pong = BedrockPong().edition("MCPE").motd("Lunar Injector").subMotd("Join to load packs")
      .playerCount(0).maximumPlayerCount(10).gameType("Survival").nintendoLimited(false)
      .protocolVersion(latest.protocolVersion).version(latest.minecraftVersion)
      .ipv4Port(19132).ipv6Port(19132).serverId(System.nanoTime())
    val g = NioEventLoopGroup(); group = g
    channel = ServerBootstrap()
      .channelFactory(RakChannelFactory.server(NioDatagramChannel::class.java))
      .group(g)
      .option(RakChannelOption.RAK_ADVERTISEMENT, pong.toByteBuf())
      .childHandler(object : BedrockServerInitializer() {
        override fun initSession(session: BedrockServerSession) {
          log("session created")
          session.packetHandler = Handler(session)
          session.peer.channel.pipeline().addLast(object : ChannelDuplexHandler() {
            override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
              promise.addListener(ChannelFutureListener { f ->
                if (!f.isSuccess) {
                  val root = generateSequence<Throwable>(f.cause()) { it.cause }.last()
                  log("SEND FAIL root: ${root.javaClass.simpleName}: ${root.message?.take(80)}")
                  log("at " + root.stackTrace.take(3).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
                }
              })
              ctx.write(msg, promise)
            }
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
              log("IN ERR: ${cause.toString().take(120)}"); ctx.fireExceptionCaught(cause)
            }
          })
        }
      })
      .bind(InetSocketAddress("0.0.0.0", 19132)).syncUninterruptibly().channel()
  }
  fun stop() { channel?.close(); group?.shutdownGracefully() }

  private fun startGame() = StartGamePacket().apply {
    uniqueEntityId = 1; runtimeEntityId = 1
    playerGameType = GameType.SURVIVAL
    playerPosition = Vector3f.from(0f, 66f, 0f)
    rotation = Vector2f.ZERO
    seed = 0L
    spawnBiomeType = SpawnBiomeType.DEFAULT
    customBiomeName = ""
    dimensionId = 0
    generatorId = 1
    levelGameType = GameType.SURVIVAL
    difficulty = 0
    defaultSpawn = Vector3i.from(0, 66, 0)
    isAchievementsDisabled = true
    educationProductionId = ""
    currentTick = 0
    rainLevel = 0f; lightningLevel = 0f
    isMultiplayerGame = true; isBroadcastingToLan = true
    platformBroadcastMode = GamePublishSetting.PUBLIC
    xblBroadcastMode = GamePublishSetting.PUBLIC
    isCommandsEnabled = true
    isTexturePacksRequired = false
    defaultPlayerPermission = PlayerPermission.MEMBER
    serverChunkTickRange = 4
    levelId = ""
    premiumWorldTemplateId = ""
    multiplayerCorrelationId = ""
    vanillaVersion = "*"
    serverEngine = ""
    playerPropertyData = NbtMap.EMPTY
    worldTemplateId = UUID(0, 0)
    isInventoriesServerAuthoritative = true
    fillNulls(this)
  }

  private inner class Handler(val s: BedrockServerSession) : BedrockPacketHandler {
    private var cd: BedrockCodec = latest
    private var transferred = false
    private fun transfer(why: String) {
      if (transferred) return
      transferred = true
      s.sendPacket(TransferPacket().apply { address = ip; port = this@LunarServer.port })
      log("11 transfer ($why) to $ip:${this@LunarServer.port}")
    }

    override fun handle(p: RequestNetworkSettingsPacket) = safe("settings") {
      log("1 network settings, client protocol ${p.protocolVersion}")
      cd = if (p.protocolVersion >= latest.protocolVersion) latest else older
      s.codec = cd
      s.sendPacketImmediately(NetworkSettingsPacket().apply {
        compressionThreshold = 0; compressionAlgorithm = PacketCompressionAlgorithm.ZLIB })
      s.setCompression(PacketCompressionAlgorithm.ZLIB)
    }
    override fun handle(p: LoginPacket) = safe("login") {
      log("2 login")
      s.sendPacket(PlayStatusPacket().apply { status = PlayStatusPacket.Status.LOGIN_SUCCESS })
      s.sendPacket(ResourcePacksInfoPacket().apply {
        isForcedToAccept = true
        worldTemplateId = UUID(0, 0); worldTemplateVersion = ""
        packs.forEach { resourcePackInfos.add(ResourcePacksInfoPacket.Entry(it.uuid, it.version, it.bytes.size.toLong(), "", "", it.id, false, false, false, "")) }
      })
      log("3 sent packs info (${packs.size})")
    }
    override fun handle(p: ResourcePackClientResponsePacket) = safe("response") {
      log("4 client says ${p.status}")
      when (p.status) {
        ResourcePackClientResponsePacket.Status.SEND_PACKS -> p.packIds.forEach { id ->
          val k = packs.find { id.startsWith(it.id, true) }
          if (k == null) log("no match for $id") else {
            s.sendPacket(ResourcePackDataInfoPacket().apply {
              packId = k.uuid; packVersion = k.version; maxChunkSize = chunk.toLong()
              chunkCount = ((k.bytes.size + chunk - 1) / chunk).toLong()
              compressedPackSize = k.bytes.size.toLong(); hash = k.sha; type = ResourcePackType.RESOURCES })
            log("5 sending pack ${k.name}")
          }
        }
        ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS -> {
          s.sendPacket(ResourcePackStackPacket().apply {
            isForcedToAccept = true; gameVersion = cd.minecraftVersion
            packs.asReversed().forEach { resourcePacks.add(ResourcePackStackPacket.Entry(it.id, it.version, "")) } })
          log("7 sent stack")
        }
        ResourcePackClientResponsePacket.Status.COMPLETED -> {
          s.sendPacket(startGame())
          log("8 sent start game")
          s.peer.channel.eventLoop().schedule(Runnable { safe("fallback") { transfer("fallback 6s") } }, 6, TimeUnit.SECONDS)
        }
        else -> {}
      }
    }
    override fun handle(p: ResourcePackChunkRequestPacket) = safe("chunk") {
      val k = packs.find { it.uuid == p.packId }
      if (k == null) { log("chunk for unknown pack"); return@safe }
      val from = p.chunkIndex * chunk; val to = minOf(from + chunk, k.bytes.size)
      s.sendPacket(ResourcePackChunkDataPacket().apply {
        packId = k.uuid; packVersion = k.version; chunkIndex = p.chunkIndex
        progress = from.toLong(); data = Unpooled.wrappedBuffer(k.bytes, from, to - from) })
    }
    override fun handle(p: RequestChunkRadiusPacket) = safe("radius") {
      s.sendPacket(ChunkRadiusUpdatedPacket().apply { radius = p.radius })
      s.sendPacket(PlayStatusPacket().apply { status = PlayStatusPacket.Status.PLAYER_SPAWN })
      log("9 radius ${p.radius}, spawn sent")
    }
    override fun handle(p: SetLocalPlayerAsInitializedPacket) = safe("init") {
      log("10 player initialized")
      s.peer.channel.eventLoop().schedule(Runnable { safe("transfer") { transfer("initialized") } }, 1500, TimeUnit.MILLISECONDS)
    }
  }
}
