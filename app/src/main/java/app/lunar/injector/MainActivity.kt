package app.lunar.injector
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

val Violet = Color(0xFF8B5CF6); val Cyan = Color(0xFF22D3EE); val Card = Color(0x14FFFFFF)

class MainActivity : ComponentActivity() {
  override fun onCreate(b: Bundle?) { super.onCreate(b)
    val dir = File(filesDir, "packs").apply { mkdirs() }
    LunarState.packs.value = dir.listFiles().orEmpty().mapNotNull { Pack.load(it) }
    setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Violet)) { Screen(dir) } }
  }
  @Composable fun Screen(dir: File) {
    val packs by LunarState.packs.collectAsState(); val live by LunarState.running.collectAsState()
    val err by LunarState.error.collectAsState()
    var addr by remember { mutableStateOf("") }; var msg by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
      u ?: return@rememberLauncherForActivityResult
      val f = File(dir, "${System.currentTimeMillis()}.zip")
      contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { i.copyTo(it) } }
      val p = Pack.load(f)
      if (p == null) { f.delete(); msg = "Not a valid Bedrock resource pack" } else { LunarState.packs.value = packs + p; msg = null }
    }
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(1f, if (live) 1.18f else 1.06f,
      infiniteRepeatable(tween(if (live) 900 else 2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B0B1A), Color(0xFF1A1033), Color(0xFF0B1A2A))))
      .statusBarsPadding().padding(20.dp)) {
      Text("Lunar Injector", fontSize = 30.sp, color = Color.White)
      Text("Custom texture packs on console servers", color = Color.White.copy(.6f))
      Spacer(Modifier.height(20.dp))
      OutlinedTextField(addr, { addr = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !live,
        label = { Text("Server address  (play.example.net:19132)") }, shape = RoundedCornerShape(16.dp))
      Spacer(Modifier.height(16.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Packs  (top wins)", color = Color.White, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !live) { Icon(Icons.Default.Add, null); Text(" Import") }
      }
      msg?.let { Text(it, color = Color(0xFFFCA5A5)) }
      LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(packs, key = { _, p -> p.file.name }) { i, p ->
          Row(Modifier.animateItem().fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}", color = Cyan, modifier = Modifier.width(24.dp))
            Text(p.name, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
            if (!live) {
              IconButton({ if (i > 0) LunarState.packs.value = packs.toMutableList().also { it.add(i - 1, it.removeAt(i)) } }) { Icon(Icons.Default.KeyboardArrowUp, null) }
              IconButton({ LunarState.packs.value = packs - p; p.file.delete() }) { Icon(Icons.Default.Delete, null) }
            }
          }
        }
      }
      AnimatedVisibility(live) { Text("Live. On your console open Friends and join \"Lunar Injector\".", color = Cyan, modifier = Modifier.padding(bottom = 8.dp)) }
      err?.let { Text("Error: $it", color = Color(0xFFFCA5A5)) }
      Box(Modifier.fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(96.dp).scale(pulse).background(Brush.linearGradient(listOf(Violet, Cyan)), CircleShape)
          .clickable(enabled = live || (addr.isNotBlank() && packs.isNotEmpty())) {
            if (live) startService(Intent(this@MainActivity, LunarService::class.java).setAction("stop"))
            else {
              val (h, p) = addr.trim().split(":").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 19132) }
              LunarState.host = h; LunarState.port = p
              startForegroundService(Intent(this@MainActivity, LunarService::class.java))
            } }, contentAlignment = Alignment.Center) {
          Icon(if (live) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(44.dp), tint = Color.White)
        }
      }
    }
  }
}
