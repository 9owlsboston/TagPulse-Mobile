package com.tagpulse.gateway.core.relay

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Secret hygiene (AGENTS §2): the ingest API key must never surface in an
 * outcome/log message, a `toString()`, or committed source.
 */
class SecretHygieneTest {

    private lateinit var server: MockWebServer
    private val secret = "tp_acme_SUPERSECRET_KEY_do_not_leak"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `401 credential-error message never echoes the api key`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody(secret /* even if the server echoed it */))
        val credentials = FakeCredentialStore(baseUrl = server.url("/").toString(), apiKey = secret)
        val client = OkHttpBackendClient(credentials)

        val result = client.postTagReadsBatch(emptyList())

        assertTrue(result is BatchResult.CredentialError)
        assertFalse(
            "credential-error reason must not leak the API key",
            (result as BatchResult.CredentialError).reason.contains(secret),
        )
    }

    @Test
    fun `no hardcoded tp_ api key literal is committed in main sources`() {
        val mainRoot = locateMainSources()
        val offenders = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines().any { line ->
                    // A Kotlin string literal beginning with a tp_/tpd_ token — i.e. a
                    // committed credential. KDoc mentions of `tp_{slug}` (no quote) are fine.
                    line.contains("\"tp_") || line.contains("\"tpd_")
                }
            }
            .map { it.name }
            .toList()

        assertTrue("hardcoded credential literal(s) found in: $offenders", offenders.isEmpty())
    }

    /** The gateway-core main source root (the unit-test working dir is the module). */
    private fun locateMainSources(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("gateway-core/src/main/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("could not locate gateway-core main sources from ${File(".").absolutePath}")
    }
}
