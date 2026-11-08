package io.github.jackwener.dsh.core.adb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Transient UI state only; never contains a pairing code. */
enum class PairingPhase { Idle, Searching, Found, Pairing, Connecting, Connected, Failed, Stopped }
data class PairingStatus(val phase: PairingPhase = PairingPhase.Idle, val message: Int? = null) {
    val active get() = phase in setOf(PairingPhase.Searching, PairingPhase.Found, PairingPhase.Pairing, PairingPhase.Connecting)
}
@Singleton
class AdbPairingState @Inject constructor() {
    private val mutable = MutableStateFlow(PairingStatus())
    val status = mutable.asStateFlow()
    internal fun update(phase: PairingPhase, message: Int? = null) { mutable.value = PairingStatus(phase, message) }
}
