package app.lunar.injector
import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.thread

object LunarState {
  val packs = MutableStateFlow<List<Pack>>(emptyList())
  val running = MutableStateFlow(false)
  val error = MutableStateFlow<String?>(null)
  var host = ""; var port = 19132
}

class LunarService : Service() {
  private var server: LunarServer? = null
  private var lock: WifiManager.MulticastLock? = null
  override fun onBind(i: Intent?): IBinder? = null
  override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
    if (i?.action == "stop") { stopSelf(); return START_NOT_STICKY }
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("lunar", "Lunar Injector", NotificationManager.IMPORTANCE_LOW))
    startForeground(1, Notification.Builder(this, "lunar").setContentTitle("Lunar Injector is live")
      .setContentText("Join the LAN game from your console").setSmallIcon(android.R.drawable.ic_media_play).build())
    lock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("lunar").apply { acquire() }
    thread {
      runCatching { server = LunarServer(LunarState.packs.value, LunarState.host, LunarState.port).also { it.start() }
        LunarState.running.value = true; LunarState.error.value = null }
        .onFailure { LunarState.error.value = it.message; stopSelf() }
    }
    return START_NOT_STICKY
  }
  override fun onDestroy() { server?.stop(); lock?.release(); LunarState.running.value = false }
}
