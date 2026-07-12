package com.barndoor.app.rotation

/** A single WireGuard exit relay offered by Mullvad. */
data class Relay(
    val countryName: String,
    val countryCode: String,
    val cityName: String,
    val hostname: String,
    val publicKey: String,
    val ipv4AddrIn: String,
    val active: Boolean
)

/** Result of registering (or re-using) our WireGuard public key with a Mullvad account. */
data class DeviceRegistration(
    val deviceId: String,
    val ipv4Address: String,
    val ipv6Address: String?
)
