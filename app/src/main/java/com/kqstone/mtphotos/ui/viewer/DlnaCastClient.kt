package com.kqstone.mtphotos.ui.viewer

import android.content.Context
import android.net.wifi.WifiManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

data class DlnaCastDevice(
    val id: String,
    val name: String,
    val location: String,
    val controlUrl: String
)

class DlnaCastClient(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun discover(timeoutMillis: Int = 3500): List<DlnaCastDevice> {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("mtgallery-dlna-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        return try {
            discoverLocations(timeoutMillis)
                .mapNotNull { location -> runCatching { loadDevice(location) }.getOrNull() }
                .distinctBy { it.id.ifBlank { it.controlUrl } }
        } finally {
            multicastLock?.takeIf { it.isHeld }?.release()
        }
    }

    fun play(device: DlnaCastDevice, mediaUrl: String) {
        sendAvTransportAction(
            device = device,
            action = "SetAVTransportURI",
            body = """
                <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                    <InstanceID>0</InstanceID>
                    <CurrentURI>${xmlEscape(mediaUrl)}</CurrentURI>
                    <CurrentURIMetaData></CurrentURIMetaData>
                </u:SetAVTransportURI>
            """.trimIndent()
        )
        sendAvTransportAction(
            device = device,
            action = "Play",
            body = """
                <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                    <InstanceID>0</InstanceID>
                    <Speed>1</Speed>
                </u:Play>
            """.trimIndent()
        )
    }

    private fun discoverLocations(timeoutMillis: Int): List<String> {
        val searchTargets = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1"
        )
        val locations = linkedSetOf<String>()
        val address = InetAddress.getByName(SSDP_ADDRESS)

        DatagramSocket().use { socket ->
            socket.soTimeout = 450
            socket.broadcast = true

            searchTargets.forEach { target ->
                val payload = buildSearchPayload(target).toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(payload, payload.size, address, SSDP_PORT)
                repeat(2) {
                    socket.send(packet)
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMillis
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    val message = String(response.data, 0, response.length, Charsets.UTF_8)
                    parseSsdpHeaders(message)["location"]?.let { locations += it }
                } catch (_: java.net.SocketTimeoutException) {
                    // Keep waiting until the overall discovery window expires.
                }
            }
        }

        return locations.toList()
    }

    private fun loadDevice(location: String): DlnaCastDevice? {
        val response = httpClient.newCall(Request.Builder().url(location).build()).execute()
        response.use {
            if (!it.isSuccessful) return null
            val body = it.body?.string().orEmpty()
            if (body.isBlank()) return null
            return parseDeviceDescription(location, body)
        }
    }

    private fun parseDeviceDescription(location: String, xml: String): DlnaCastDevice? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }
        val document = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

        val urlBase = document.getElementsByTagName("URLBase")
            .item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: location

        val device = document.getElementsByTagName("device")
            .asSequence()
            .mapNotNull { it as? Element }
            .firstOrNull { element ->
                element.childText("deviceType")?.contains("MediaRenderer", ignoreCase = true) == true ||
                    element.getElementsByTagName("service")
                        .asSequence()
                        .mapNotNull { service -> service as? Element }
                        .any { service -> service.childText("serviceType") == AV_TRANSPORT_SERVICE }
            }
            ?: return null

        val avTransportService = device.getElementsByTagName("service")
            .asSequence()
            .mapNotNull { it as? Element }
            .firstOrNull { it.childText("serviceType") == AV_TRANSPORT_SERVICE }
            ?: return null

        val controlPath = avTransportService.childText("controlURL")?.takeIf { it.isNotBlank() }
            ?: return null
        val controlUrl = resolveUrl(urlBase, controlPath)
        val id = device.childText("UDN").orEmpty().ifBlank { controlUrl }
        val name = device.childText("friendlyName").orEmpty().ifBlank { URI(controlUrl).host ?: controlUrl }

        return DlnaCastDevice(
            id = id,
            name = name,
            location = location,
            controlUrl = controlUrl
        )
    }

    private fun sendAvTransportAction(device: DlnaCastDevice, action: String, body: String) {
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    $body
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        val request = Request.Builder()
            .url(device.controlUrl)
            .header("SOAPAction", "\"$AV_TRANSPORT_SERVICE#$action\"")
            .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("$action failed: HTTP ${response.code}")
            }
        }
    }

    private fun buildSearchPayload(searchTarget: String): String {
        val payload = """
            M-SEARCH * HTTP/1.1
            HOST: $SSDP_ADDRESS:$SSDP_PORT
            MAN: "ssdp:discover"
            MX: 2
            ST: $searchTarget
        """.trimIndent().replace("\n", "\r\n")
        return "$payload\r\n\r\n"
    }

    private fun parseSsdpHeaders(message: String): Map<String, String> {
        return message
            .lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else {
                    line.substring(0, separator).trim().lowercase() to
                        line.substring(separator + 1).trim()
                }
            }
            .toMap()
    }

    private fun resolveUrl(base: String, path: String): String {
        return URI(base).resolve(path).toString()
    }

    private fun xmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun Element.childText(tagName: String): String? {
        val nodes = getElementsByTagName(tagName)
        return (0 until nodes.length)
            .asSequence()
            .mapNotNull { nodes.item(it) as? Element }
            .firstOrNull { it.parentNode == this }
            ?.textContent
            ?.trim()
    }

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<org.w3c.dom.Node> {
        return (0 until length).asSequence().map { item(it) }
    }

    private companion object {
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
    }
}
