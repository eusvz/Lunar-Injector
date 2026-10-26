package app.lunar.injector
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

class Pack(val file: File, val uuid: UUID, val version: String, val name: String) {
  val bytes: ByteArray by lazy { file.readBytes() }
  val sha: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest(bytes) }
  val id get() = "$uuid"
  companion object {
    fun load(f: File): Pack? = runCatching {
      ZipFile(f).use { z ->
        val e = z.entries().asSequence().filter { it.name.endsWith("manifest.json") }.minByOrNull { it.name.length }!!
        val m = JSONObject(z.getInputStream(e).bufferedReader().readText())
        val h = m.getJSONObject("header")
        val v = h.getJSONArray("version")
        Pack(f, UUID.fromString(h.getString("uuid")), "${v.getInt(0)}.${v.getInt(1)}.${v.getInt(2)}", h.optString("name", f.nameWithoutExtension))
      }
    }.getOrNull()
  }
}
