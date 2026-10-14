package org.pocketlab.puppy.session.ssh

import org.pocketlab.puppy.data.KnownHost

/** What to do with a host key the server presented. */
sealed interface HostKeyDecision {
    /** No confirmed key of this algorithm: ask the user to confirm the fingerprint. */
    data object Unknown : HostKeyDecision

    /** Matches the confirmed key: continue silently. */
    data object Match : HostKeyDecision

    /** Differs from the confirmed key: possible attack, never accepted silently. */
    data class Mismatch(val previous: KnownHost) : HostKeyDecision
}

object HostKeys {
    /**
     * Compares a presented key (algorithm + base64 blob) with the confirmed
     * entries for the same host and port. Keys are matched per algorithm,
     * as OpenSSH does; SSHJ is told the known algorithms up front so the
     * server negotiates one we can check.
     */
    fun decide(known: List<KnownHost>, algorithm: String, publicKeyBase64: String): HostKeyDecision {
        val sameAlgorithm = known.filter { it.algorithm == algorithm }
        if (sameAlgorithm.isEmpty()) return HostKeyDecision.Unknown
        return if (sameAlgorithm.any { it.publicKey == publicKeyBase64 }) {
            HostKeyDecision.Match
        } else {
            HostKeyDecision.Mismatch(sameAlgorithm.first())
        }
    }
}

/** Field-level problems in a host form. */
data class HostValidation(
    val label: String? = null,
    val hostname: String? = null,
    val port: String? = null,
    val username: String? = null,
) {
    val isValid get() = label == null && hostname == null && port == null && username == null

    companion object {
        private val HOST = Regex("^[A-Za-z0-9._:%\\[\\]-]+$")
        private val USER = Regex("^[^\\s@:]+$")

        fun validate(label: String, hostname: String, port: String, username: String): HostValidation {
            val h = hostname.trim()
            val p = port.trim().toIntOrNull()
            return HostValidation(
                label = if (label.isBlank()) "Required" else null,
                hostname = when {
                    h.isEmpty() -> "Required"
                    !HOST.matches(h) -> "Not a valid host name or address"
                    else -> null
                },
                port = when {
                    port.isBlank() -> "Required"
                    p == null || p !in 1..65535 -> "Port must be 1–65535"
                    else -> null
                },
                username = when {
                    username.isBlank() -> "Required"
                    !USER.matches(username.trim()) -> "No spaces, @ or :"
                    else -> null
                },
            )
        }
    }
}
