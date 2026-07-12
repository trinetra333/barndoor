package com.barndoor.app.rotation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
        binding.intervalInput.setText(prefs.intervalMinutes.toString())
        binding.countryInput.setText(prefs.selectedCountryCode.orEmpty())
        binding.modeCountry.isChecked = prefs.mode == RotationMode.RANDOM_COUNTRY
        binding.modeAny.isChecked = prefs.mode == RotationMode.RANDOM_ANY
        binding.countryInputLayout.visibility =
            if (prefs.mode == RotationMode.RANDOM_COUNTRY) View.VISIBLE else View.GONE
        binding.rotationSwitch.isChecked = prefs.running

        binding.modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val isCountry = checkedId == R.id.modeCountry
            binding.countryInputLayout.visibility = if (isCountry) View.VISIBLE else View.GONE
            prefs.mode = if (isCountry) RotationMode.RANDOM_COUNTRY else RotationMode.RANDOM_ANY
        }

        binding.registerButton.setOnClickListener { registerDevice() }

        binding.rotationSwitch.setOnCheckedChangeListener { switchView, checked ->
            if (!switchView.isPressed) return@setOnCheckedChangeListener // avoid loop on programmatic set
            if (checked) startRotationFlow() else stopRotation()
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun registerDevice() {
        val account = binding.accountInput.text?.toString()?.trim().orEmpty()
        if (account.isBlank()) {
            binding.registrationStatus.text = "Enter your Mullvad account number first."
            return
        }
        prefs.accountNumber = account
        binding.registerButton.isEnabled = false
        binding.registrationStatus.text = "Registering device…"

        val (_, pubKey) = wgManager.ensureKeyPair(prefs)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { MullvadApi.registerDevice(account, pubKey) }
            binding.registerButton.isEnabled = true
            result.onSuccess { registration ->
                prefs.deviceId = registration.deviceId
                prefs.tunnelIpv4 = registration.ipv4Address
                prefs.tunnelIpv6 = registration.ipv6Address
                binding.registrationStatus.text =
                    "Registered \u2022 tunnel address ${registration.ipv4Address}"
            }.onFailure { e ->
                binding.registrationStatus.text = "Registration failed: ${e.message}"
            }
        }
    }

    private fun saveIntervalAndCountry() {
        val minutes = binding.intervalInput.text?.toString()?.trim()?.toIntOrNull() ?: 2
        prefs.intervalMinutes = minutes.coerceIn(1, 120)
        val country = binding.countryInput.text?.toString()?.trim().orEmpty()
        prefs.selectedCountryCode = country.ifBlank { null }
    }

    private fun startRotationFlow() {
        if (!prefs.isDeviceRegistered()) {
            binding.registrationStatus.text = getString(R.string.rotation_not_registered)
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
            !prefs.isDeviceRegistered() -> getString(R.string.rotation_not_registered)
            prefs.running -> prefs.currentRelayLabel?.let { "Connected \u2022 $it" }
                ?: getString(R.string.tile_rotating)
            else -> getString(R.string.rotation_status_idle)
        }
        binding.registrationStatus.text = if (prefs.isDeviceRegistered()) {
            "Registered \u2022 tunnel address ${prefs.tunnelIpv4}"
        } else {
            getString(R.string.rotation_not_registered)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
