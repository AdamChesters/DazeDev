package com.adamchesters.dazedev.mobile.data

import org.junit.Assert.*
import org.junit.Test

class ConnectionProfileTest {
    @Test
    fun `new profiles have no personal connection defaults`() {
        val profile = ConnectionProfile()
        assertEquals(AuthMethod.PrivateKey, profile.authMethod)
        assertEquals("", profile.host)
        assertEquals("", profile.username)
        assertEquals("", profile.workingDirectory)
        assertEquals(22, profile.port)
        assertNotNull(profile.validationError(""))
    }

    @Test
    fun `standard SSH accepts hostnames IPv4 IPv6 and every valid port boundary`() {
        for (host in listOf("ssh.example.com", "192.0.2.10", "2001:db8::10")) {
            for (port in listOf(1, 22, 2222, 65535)) {
                val profile = ConnectionProfile(host, port, "developer", AuthMethod.Password)
                assertNull(profile.validationError("test-password"))
            }
        }
    }

    @Test
    fun `rejects invalid endpoints and ports`() {
        val profile = ConnectionProfile("ssh.example.com", 22, "developer", AuthMethod.Password)
        for (host in listOf("", "host name", "ssh://ssh.example.com", "user@host", "host\u0000")) {
            assertNotNull(profile.copy(host = host).validationError("password"))
        }
        for (port in listOf(-1, 0, 65536)) {
            assertNotNull(profile.copy(port = port).validationError("password"))
        }
    }

    @Test
    fun `authentication choices require only their own credentials`() {
        val profile = ConnectionProfile("ssh.example.com", 22, "developer")
        assertNotNull(profile.validationError(""))
        assertNull(profile.copy(privateKeyUri = "content://test/key").validationError(""))
        assertNotNull(profile.copy(authMethod = AuthMethod.Password).validationError(""))
        assertNull(profile.copy(authMethod = AuthMethod.TailscaleSsh).validationError(""))
    }
}
