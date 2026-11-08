package io.github.jackwener.dsh.core.adb

/** Transport diagnostics are deliberately suppressed: ADB traffic can carry page credentials. */
internal object AdbLog {
    fun d(message: String, vararg args: Any?) = Unit
    fun w(message: String, vararg args: Any?) = Unit
    fun i(message: String, vararg args: Any?) = Unit
}
