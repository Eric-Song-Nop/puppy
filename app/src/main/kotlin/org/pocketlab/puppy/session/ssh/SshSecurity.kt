package org.pocketlab.puppy.session.ssh

import java.security.Security
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Installs the full BouncyCastle provider for SSHJ.
 *
 * Android ships a stripped provider under the same "BC" name that lacks
 * Ed25519, X25519 and several ciphers, so it is replaced once per process.
 */
object SshSecurity {
    @Volatile private var done = false

    fun init() {
        if (done) return
        synchronized(this) {
            if (done) return
            val current = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (current == null || current.javaClass != BouncyCastleProvider::class.java) {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
            SecurityUtils.setRegisterBouncyCastle(true)
            SecurityUtils.setSecurityProvider(BouncyCastleProvider.PROVIDER_NAME)
            done = true
        }
    }
}
