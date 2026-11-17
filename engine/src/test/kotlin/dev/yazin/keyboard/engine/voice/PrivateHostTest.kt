package dev.yazin.keyboard.engine.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateHostTest {
    @Test fun privateNetworksAreFine() {
        for (url in listOf(
            "http://mybox:9000/", "http://10.0.2.2:9100/", "http://192.168.1.5:9000", "http://172.20.0.1",
            "http://100.101.102.103:9000/", "http://mybox.tailnet-name.ts.net:9000/", "http://nas.local:9000",
            "http://[fd7a:115c:a1e0::1]:9000/", "http://localhost:9000", "http://127.0.0.1:9000",
        )) assertFalse(url, PrivateHost.insecure(url))
    }

    @Test fun plainHttpToThePublicInternetIsFlagged() {
        for (url in listOf("http://example.com:9000/", "http://8.8.8.8/", "http://172.32.0.1/", "http://100.128.0.1/"))
            assertTrue(url, PrivateHost.insecure(url))
    }

    @Test fun httpsIsAlwaysFine() = assertFalse(PrivateHost.insecure("https://example.com/"))
}
