package com.barndoor.app.whois

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.barndoor.app.databinding.FragmentWhoisBinding
import kotlinx.coroutines.launch
import java.net.InetAddress

class WhoisFragment : Fragment() {

    private var _binding: FragmentWhoisBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWhoisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.whoisMap.settings.javaScriptEnabled = true

        binding.whoisLookupButton.setOnClickListener { runLookup() }
        binding.whoisInput.setOnEditorActionListener { _, _, _ -> runLookup(); true }
    }

    private fun runLookup() {
        val query = binding.whoisInput.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return

        binding.whoisProgress.visibility = View.VISIBLE
        binding.whoisGeoCard.visibility = View.GONE
        binding.whoisRawTitle.visibility = View.GONE
        binding.whoisRawText.text = ""

        viewLifecycleOwner.lifecycleScope.launch {
            val whoisResult = runCatching { WhoisClient.lookup(query) }
            val ipForGeo = runCatching {
                if (isIpAddress(query)) query else InetAddress.getByName(query).hostAddress
            }.getOrNull()

            val geo = ipForGeo?.let { runCatching { GeoClient.lookup(it) }.getOrNull() }

            if (!isAdded) return@launch
            binding.whoisProgress.visibility = View.GONE

            whoisResult.onSuccess { text ->
                binding.whoisRawTitle.visibility = View.VISIBLE
                binding.whoisRawText.text = text.ifBlank { "No WHOIS data returned." }
            }.onFailure { e ->
                binding.whoisRawTitle.visibility = View.VISIBLE
                binding.whoisRawText.text = "WHOIS lookup failed: ${e.message}"
            }

            if (geo != null) showGeo(geo) else if (ipForGeo == null) {
                binding.whoisRawText.text = "Could not resolve \"$query\" to an IP address.\n\n" + binding.whoisRawText.text
            }
        }
    }

    private fun showGeo(geo: GeoInfo) {
        binding.whoisGeoCard.visibility = View.VISIBLE
        binding.whoisGeoTitle.text = geo.ip
        val locationLine = listOfNotNull(geo.city, geo.region, geo.country).joinToString(", ")
        val orgLine = listOfNotNull(geo.isp ?: geo.org, geo.asn?.let { "AS$it" }).joinToString(" \u2022 ")
        binding.whoisGeoDetails.text = listOf(locationLine, orgLine).filter { it.isNotBlank() }.joinToString("\n")

        if (geo.latitude != null && geo.longitude != null) {
            binding.whoisMap.visibility = View.VISIBLE
            loadMap(geo.latitude, geo.longitude, geo.city ?: geo.ip)
        } else {
            binding.whoisMap.visibility = View.GONE
        }
    }

    private fun loadMap(lat: Double, lon: Double, label: String) {
        val safeLabel = label.replace("\"", "'")
        val html = """
            <!DOCTYPE html>
            <html><head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
              <style>html,body,#map{height:100%;margin:0;padding:0;background:#1B1E27;}</style>
            </head><body>
              <div id="map"></div>
              <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
              <script>
                var map = L.map('map').setView([$lat, $lon], 9);
                L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                  attribution: '&copy; OpenStreetMap contributors'
                }).addTo(map);
                L.marker([$lat, $lon]).addTo(map).bindPopup("$safeLabel").openPopup();
              </script>
            </body></html>
        """.trimIndent()
        binding.whoisMap.loadDataWithBaseURL("https://openstreetmap.org", html, "text/html", "utf-8", null)
    }

    private fun isIpAddress(value: String): Boolean =
        value.split(".").let { parts -> parts.size == 4 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true } }

    override fun onDestroyView() {
        binding.whoisMap.loadUrl("about:blank")
        super.onDestroyView()
        _binding = null
    }
}
