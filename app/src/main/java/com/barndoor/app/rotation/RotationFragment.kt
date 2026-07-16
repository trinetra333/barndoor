package com.barndoor.app.rotation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.barndoor.app.BuildConfig
import com.barndoor.app.MainActivity
import com.barndoor.app.R
import com.barndoor.app.databinding.FragmentRotationBinding
import kotlinx.coroutines.launch

class RotationFragment : Fragment() {

    private var _binding: FragmentRotationBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: RotationPrefs
    private lateinit var wgManager: WireGuardManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRotationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = RotationPrefs(requireContext())
        wgManager = WireGuardManager(requireContext())

        binding.accountInput.setText(prefs.accountNumber.orEmpty())
        binding.intervalInput.setText(prefs.intervalSeconds.toString())
        binding.countryInput.setText(prefs.selectedCountryCode.orEmpty())
        binding.modeCountry.isChecked = prefs.mode == RotationMode.RANDOM_COUNTRY
        binding.modeAny.isChecked = prefs.mode == RotationMode.RANDOM_ANY
        binding.countryInputLayout.visibility =
            if (prefs.mode == RotationMode.RANDOM_COUNTRY) View.VISIBLE else View.GONE
        binding.rotationSwitch.isChecked = prefs.running

        binding.providerMullvad.isChecked = prefs.provider == VpnProvider.MULLVAD
        binding.providerWarp.isChecked = prefs.provider == VpnProvider.WARP
        showProviderSection(prefs.provider)

        binding.providerRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val provider = if (checkedId == R.id.providerWarp) VpnProvider.WARP else VpnProvider.MULLVAD
            if (prefs.running && provider != prefs.provider) {
                // Switching providers while connected would leave the old tunnel
                // dangling — stop it first, user can re-enable under the new provider.
                RotationService.stop(requireContext())
                binding.rotationSwitch.isChecked = false
            }
            prefs.provider = provider
            showProviderSection(provider)
            updateStatus()
        }

        binding.modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val isCountry = checkedId == R.id.modeCountry
            binding.countryInputLayout.visibility = if (isCountry) View.VISIBLE else View.GONE
            prefs.mode = if (isCountry) RotationMode.RANDOM_COUNTRY else RotationMode.RANDOM_ANY
        }

        binding.registerButton.setOnClickListener {
            registerMullvad(binding.accountInput.text?.toString()?.trim().orEmpty())
        }
        binding.warpRegisterButton.setOnClickListener { registerWarp() }

        binding.intervalInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveIntervalAndCountry()
        }

        binding.rotationSwitch.setOnCheckedChangeListener { switchView, checked ->
            if (!switchView.isPressed) return@setOnCheckedChangeListener // avoid loop on programmatic set
            if (checked) startRotationFlow() else stopRotation()
        }

        updateStatus()
        maybeAutoRegisterMullvadFromBuildConfig()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun showProviderSection(provider: VpnProvider) {
        binding.mullvadSection.visibility = if (provider == VpnProvider.MULLVAD) View.VISIBLE else View.GONE
        binding.warpSection.visibility = if (provider == VpnProvider.WARP) View.VISIBLE else View.GONE
        binding.intervalHint.text = when (provider) {
            VpnProvider.MULLVAD -> "Picks a new random server (respecting the country setting above) every interval."
            VpnProvider.WARP -> "WARP has no server list to rotate through \u2014 this just reconnects on the same timer, which can (not guaranteed) land on a different Cloudflare edge IP."
        }
    }

    /** If a Mullvad account was baked in at build time (see README) and nothing is
     *  registered yet, register automatically and hide the manual-entry form so the
     *  tab actually feels like zero typing, not just a pre-filled field. */
    private fun maybeAutoRegisterMullvadFromBuildConfig() {
        val baked = BuildConfig.MULLVAD_ACCOUNT
        if (baked.isBlank()) {
            binding.manualAccountEntry.visibility = View.VISIBLE
            return
        }
        binding.accountInput.setText(baked)
        binding.manualAccountEntry.visibility = View.GONE
        if (!prefs.isMullvadRegistered()) {
            binding.registrationStatus.text = "Registering with the account configured at build time\u2026"
            registerMullvad(baked)
        }
    }

    private fun registerMullvad(account: String) {
        if (account.isBlank()) {
            binding.registrationStatus.text = "Enter your Mullvad account number first."
            return
        }
        prefs.accountNumber = account
        binding.registerButton.isEnabled = false
        binding.registrationStatus.text = "Registering device…"

        val existingPriv = prefs.mullvadPrivateKeyBase64
        val existingPub = prefs.mullvadPublicKeyBase64
        val pubKey = if (!existingPriv.isNullOrBlank() && !existingPub.isNullOrBlank()) {
            existingPub
        } else {
            val (priv, pub) = wgManager.generateKeyPair()
            prefs.mullvadPrivateKeyBase64 = priv
            prefs.mullvadPublicKeyBase64 = pub
            pub
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { MullvadApi.registerDevice(account, pubKey) }
            if (!isAdded) return@launch
            binding.registerButton.isEnabled = true
            result.onSuccess { registration ->
                prefs.mullvadDeviceId = registration.deviceId
                prefs.mullvadTunnelIpv4 = registration.ipv4Address
                prefs.mullvadTunnelIpv6 = registration.ipv6Address
                binding.registrationStatus.text =
                    "Registered \u2022 tunnel address ${registration.ipv4Address}"
            }.onFailure { e ->
                binding.registrationStatus.text = "Registration failed: ${e.message}"
                binding.manualAccountEntry.visibility = View.VISIBLE
            }
        }
    }

    private fun registerWarp() {
        binding.warpRegisterButton.isEnabled = false
        binding.warpStatus.text = "Generating anonymous identity\u2026"

        val existingPriv = prefs.warpPrivateKeyBase64
        val existingPub = prefs.warpPublicKeyBase64
        val pubKey = if (!existingPriv.isNullOrBlank() && !existingPub.isNullOrBlank()) {
            existingPub
        } else {
            val (priv, pub) = wgManager.generateKeyPair()
            prefs.warpPrivateKeyBase64 = priv
            prefs.warpPublicKeyBase64 = pub
            pub
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { WarpApi.register(pubKey) }
            if (!isAdded) return@launch
            binding.warpRegisterButton.isEnabled = true
            result.onSuccess { registration ->
                prefs.warpDeviceId = registration.deviceId
                prefs.warpTunnelIpv4 = registration.tunnelIpv4
                prefs.warpTunnelIpv6 = registration.tunnelIpv6
                prefs.warpPeerPublicKey = registration.peerPublicKey
                prefs.warpPeerEndpoint = registration.peerEndpoint
                binding.warpStatus.text = "\u2713 Ready \u2022 tunnel address ${registration.tunnelIpv4}"
            }.onFailure { e ->
                binding.warpStatus.text = "Setup failed: ${e.message}"
            }
            updateStatus()
        }
    }

    private fun saveIntervalAndCountry() {
        val seconds = binding.intervalInput.text?.toString()?.trim()?.toIntOrNull() ?: 120
        val clamped = seconds.coerceIn(10, 1800)
        prefs.intervalSeconds = clamped
        if (clamped != seconds) binding.intervalInput.setText(clamped.toString())

        val country = binding.countryInput.text?.toString()?.trim().orEmpty()
        prefs.selectedCountryCode = country.ifBlank { null }
    }

    private fun startRotationFlow() {
        if (!prefs.isReadyToConnect()) {
            binding.rotationStatus.text = when (prefs.provider) {
                VpnProvider.MULLVAD -> getString(R.string.rotation_not_registered)
                VpnProvider.WARP -> getString(R.string.rotation_warp_not_registered)
            }
            binding.rotationSwitch.isChecked = false
            return
        }
        saveIntervalAndCountry()

        val activity = activity as? MainActivity ?: return
        val consentIntent = wgManager.permissionIntent(requireActivity())
        activity.requestVpnConsent(consentIntent) {
            RotationService.start(requireContext())
            updateStatus()
        }
    }

    private fun stopRotation() {
        RotationService.stop(requireContext())
        updateStatus()
    }

    private fun updateStatus() {
        binding.rotationStatus.text = when {
            !prefs.isReadyToConnect() -> when (prefs.provider) {
                VpnProvider.MULLVAD -> getString(R.string.rotation_not_registered)
                VpnProvider.WARP -> getString(R.string.rotation_warp_not_registered)
            }
            prefs.running -> {
                val seconds = prefs.intervalSeconds
                val warning = if (seconds < 30) "\n\n\u26A0\uFE0F ${getString(R.string.rotation_fast_warning)}" else ""
                (prefs.currentRelayLabel?.let { "Connected \u2022 $it" } ?: getString(R.string.tile_rotating)) +
                    " \u2022 every ${seconds}s" + warning
            }
            else -> getString(R.string.rotation_status_idle)
        }

        binding.registrationStatus.text = if (prefs.isMullvadRegistered()) {
            "Registered \u2022 tunnel address ${prefs.mullvadTunnelIpv4}"
        } else {
            getString(R.string.rotation_not_registered)
        }

        binding.warpStatus.text = if (prefs.isWarpRegistered()) {
            "\u2713 Ready \u2022 tunnel address ${prefs.warpTunnelIpv4}"
        } else {
            getString(R.string.rotation_warp_not_registered)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
