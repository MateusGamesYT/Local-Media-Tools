package com.localmediatools.print

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.localmediatools.core.UserFacingException
import com.localmediatools.print.core.FoundPrinter
import com.localmediatools.print.core.IppClient
import com.localmediatools.print.core.IppException
import com.localmediatools.print.core.Mdns
import com.localmediatools.print.core.PinnedTrust
import com.localmediatools.print.core.PrintRun
import com.localmediatools.print.core.PrinterAddress
import com.localmediatools.print.core.PrinterCaps
import org.json.JSONArray
import org.json.JSONObject

/** A printer the user picked: where it is, and the certificate it showed the first time (encrypted printing). */
data class SavedPrinter(
    /** Its network name (or "ip:<address>" when added by hand). */
    val id: String,
    val name: String,
    val model: String?,
    val uri: String,
    /** Unencrypted address of the same printer, used if encryption fails. */
    val fallbackUri: String? = null,
    val pin: String? = null,
    val lastUsed: Long = 0,
) {
    val manual: Boolean get() = id.startsWith("ip:")
    val host: String get() = runCatching { PrinterAddress.parse(uri).hostText }.getOrDefault(uri)

    fun toJson() = JSONObject().put("id", id).put("name", name).put("model", model).put("uri", uri).put("fallback", fallbackUri).put("pin", pin).put("used", lastUsed)

    companion object {
        fun fromJson(o: JSONObject) = SavedPrinter(o.getString("id"), o.getString("name"), o.optString("model").ifEmpty { null }, o.getString("uri"),
            o.optString("fallback").ifEmpty { null }, o.optString("pin").ifEmpty { null }, o.optLong("used"))

        fun from(f: FoundPrinter, old: SavedPrinter? = null) = SavedPrinter(f.identity, f.name, f.model, f.address.uri, f.fallback?.uri, old?.pin, old?.lastUsed ?: 0)

        /** A printer typed in by IP address (or ipp:// address). */
        fun manual(text: String): SavedPrinter {
            val a = try { PrinterAddress.parse(text) } catch (e: IllegalArgumentException) { throw UserFacingException(e.message ?: "Not a printer address") }
            // A bare IP address: encrypted if the printer offers it, else unencrypted.
            return if (text.contains("://")) SavedPrinter("ip:" + a.hostText, a.hostText, null, a.uri)
                else SavedPrinter("ip:" + a.hostText, a.hostText, null, a.copy(tls = true).uri, a.uri)
        }
    }
}

/** A printer ready to print to: how to reach it and what it can do. */
class PrinterConnection(val printer: SavedPrinter, val client: IppClient, val caps: PrinterCaps)

/**
 * Printers on the local network: remembered ones, finding new ones (multicast DNS on the Wi-Fi) and
 * connecting to them. Nothing here reaches beyond the local network (see print.core.LocalNetwork).
 */
object Printers {
    /** Test hook: what discovery finds, instead of asking the network. */
    @Volatile var discoverOverride: ((Long) -> List<FoundPrinter>)? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("printers", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<SavedPrinter> = synchronized(this) {
        val s = prefs(ctx).getString("list", null) ?: return emptyList()
        try { JSONArray(s).let { a -> (0 until a.length()).map { SavedPrinter.fromJson(a.getJSONObject(it)) } } } catch (_: Exception) { emptyList() }
    }.sortedByDescending { it.lastUsed }

    fun save(ctx: Context, p: SavedPrinter) = synchronized(this) {
        val list = all(ctx).filter { it.id != p.id } + p
        prefs(ctx).edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun remove(ctx: Context, id: String) = synchronized(this) {
        prefs(ctx).edit().putString("list", JSONArray(all(ctx).filter { it.id != id }.map { it.toJson() }).toString()).apply()
    }

    /** The printer used last (offered first). */
    fun last(ctx: Context): SavedPrinter? = all(ctx).firstOrNull()

    fun markUsed(ctx: Context, p: SavedPrinter) = save(ctx, p.copy(lastUsed = System.currentTimeMillis()))

    /** The Wi-Fi (or Ethernet) network printers can be on, if the phone is connected to one. */
    fun localNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } == true
        }
    }

    /**
     * Runs [block] with this app's connections going through the Wi-Fi: when the Wi-Fi has no
     * internet (a printer's own Wi-Fi Direct network, say) Android might otherwise send them over
     * mobile data, where no printer can be found.
     */
    fun <T> onLocalNetwork(ctx: Context, block: () -> T): T {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = localNetwork(ctx)
        synchronized(binds) {
            if (net != null && cm != null && binds[0]++ == 0) try { cm.bindProcessToNetwork(net) } catch (_: Exception) { }
        }
        try { return block() } finally {
            synchronized(binds) { if (net != null && cm != null && --binds[0] == 0) try { cm.bindProcessToNetwork(null) } catch (_: Exception) { } }
        }
    }
    private val binds = intArrayOf(0)

    /** Looks for printers on the Wi-Fi for [timeoutMs], reporting the growing list. Blocking. */
    fun discover(ctx: Context, timeoutMs: Long = 5000, cancelled: () -> Boolean = { false }, found: (List<FoundPrinter>) -> Unit = {}): List<FoundPrinter> {
        discoverOverride?.let { return it(timeoutMs).also(found) }
        if (localNetwork(ctx) == null) throw UserFacingException("Connect the phone to the same Wi-Fi as the printer to find it.")
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("LocalMediaTools printers")?.apply { setReferenceCounted(false) }
        return try {
            lock?.acquire()
            onLocalNetwork(ctx) { Mdns.discover(timeoutMs, cancelled, found) }
        } finally {
            try { lock?.release() } catch (_: Exception) { }
        }
    }

    /**
     * Connects to [p] and asks what it can do: encrypted first when it offers that, else (or if
     * encryption fails) unencrypted. If the printer can't be reached at its last address (routers
     * hand out new ones), it is looked for on the network again. Blocking.
     */
    fun connect(ctx: Context, p: SavedPrinter, rediscover: Boolean = true): PrinterConnection = onLocalNetwork(ctx) {
        var last: Exception? = null
        for (uri in listOfNotNull(p.uri, p.fallbackUri).distinct()) {
            val address = PrinterAddress.parse(uri)
            val trust = PinnedTrust(p.pin)
            val client = IppClient(address, trust)
            try {
                val caps = PrintRun.capabilities(client)
                val updated = if (address.tls && trust.pin != p.pin) p.copy(pin = trust.pin) else p
                if (updated != p) save(ctx, updated)
                return@onLocalNetwork PrinterConnection(updated, client, caps)
            } catch (e: IppException) {
                // A changed identity is a reason to stop, not to try the unencrypted way.
                if (e.message?.contains("identity changed") == true || e.cause?.message?.contains("identity changed") == true) throw e
                last = e
            }
        }
        if (rediscover && !p.manual) {
            val again = discover(ctx, 4000).firstOrNull { it.identity == p.id }
            if (again != null) {
                val moved = SavedPrinter.from(again, p)
                save(ctx, moved)
                return@onLocalNetwork connect(ctx, moved, rediscover = false)
            }
        }
        throw last ?: IppException("Can't reach the printer")
    }
}
