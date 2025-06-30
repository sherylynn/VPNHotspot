package be.mygod.vpnhotspot.webserver

import android.app.Service
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.SoftApConfiguration
import android.os.IBinder
import be.mygod.vpnhotspot.net.TetherType
import be.mygod.vpnhotspot.net.TetheringManagerCompat
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

class WebService : Service() {

    private var server: NanoServer? = null
    // To store the callback and prevent it from being garbage collected prematurely if startTethering is async.
    private var tetheringCallback: TetheringManagerCompat.OnStartTetheringCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        server = NanoServer()
        try {
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private inner class NanoServer : NanoHTTPD(9999) {
        override fun serve(session: IHTTPSession): Response {
            val action = session.parameters["action"]?.get(0)
            if (session.method == Method.POST && action == "toggle_hotspot") {
                return try {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val tetheringManager = TetheringManagerCompat(applicationContext) {
                        // OnStartTetheringCallback, we might not need to do anything specific here for stopping
                    }

                    var isWifiTetheringActive = false
                    val tetheredIfaces = TetheringManagerCompat.getTetheredIfaces(applicationContext)
                    for (iface in tetheredIfaces) {
                        if (TetherType.ofInterface(iface) == TetherType.WIFI) {
                            isWifiTetheringActive = true
                            break
                        }
                    }

                    if (isWifiTetheringActive) {
                        tetheringManager.stopTethering(TetheringManagerCompat.TETHERING_WIFI)
                        newFixedLengthResponse("Wi-Fi Hotspot stopping...")
                    } else {
                        // To start tethering, we might need a configuration.
                        // For simplicity, we'll try to start without a specific configuration first.
                        // This might start hotspot with default settings or last used config.
                        // Actual implementation might require fetching or creating a SoftApConfiguration.
                        val klassischenStart = TetheringManagerCompat::class.java.getDeclaredMethod("startTethering",
                            Int::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            TetheringManagerCompat.OnStartTetheringCallback::class.java,
                            SoftApConfiguration::class.java // This might be problematic if null is not allowed or specific config is needed
                        )
                        try {
                            tetheringCallback = object : TetheringManagerCompat.OnStartTetheringCallback {
                                override fun onTetheringStarted() {
                                    // Log or update status if needed
                                    // Consider sending a broadcast or using LiveData if UI needs to react
                                }
                                override fun onTetheringFailed(error: Int) {
                                    // Log or update status if needed
                                    // Consider sending a broadcast or using LiveData if UI needs to react
                                }
                            }
                            tetheringManager.startTethering(TetheringManagerCompat.TETHERING_WIFI, false, tetheringCallback!!)
                            newFixedLengthResponse("Wi-Fi Hotspot starting...")
                        } catch (e: Exception) {
                            e.printStackTrace()
                            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error starting hotspot: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error toggling hotspot: ${e.message}")
                }
            }

            // Serve the HTML page
            // Basic check for tethering status to update button text - this is very simplified
            var buttonText = "Toggle Hotspot"
            try {
                val tetheredIfaces = TetheringManagerCompat.getTetheredIfaces(applicationContext)
                for (iface in tetheredIfaces) {
                    if (TetherType.ofInterface(iface) == TetherType.WIFI) {
                        buttonText = "Turn Off Hotspot"
                        break
                    }
                }
                 if (buttonText == "Toggle Hotspot") buttonText = "Turn On Hotspot" // If not active, default to "Turn On"
            } catch (e: Exception) {
                // Ignore, keep default button text
            }

            val html = """
                <html>
                <head>
                    <title>VPN Hotspot Control</title>
                    <script>
                        async function toggleHotspot() {
                            const response = await fetch('/?action=toggle_hotspot', { method: 'POST' });
                            const result = await response.text();
                            document.getElementById('status').innerText = result;
                        }
                    </script>
                </head>
                <body>
                    <h1>VPN Hotspot Control</h1>
                    <button onclick="toggleHotspot()">Toggle Hotspot</button>
                    <p id="status"></p>
                </body>
                </html>
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
    }
}
