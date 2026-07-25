package com.tagpulse.mobile.scan

import com.tagpulse.gateway.core.relay.DrainReport

/**
 * The coordinator's view of the outbox drain (plan §7 / M4's `Drainer`). A thin
 * seam so the [ScanCoordinator] gate tests can script a [DrainReport] (via a
 * `FakeRelay`) without standing up the whole `Drainer` + `BackendClient` +
 * `CredentialStore` stack — those fakes live in `:gateway-core`'s test source set
 * and aren't visible here.
 *
 * The production binding wraps the real `com.tagpulse.gateway.core.relay.Drainer`
 * (see [com.tagpulse.mobile.di.AppContainer]).
 */
fun interface Relay {

    /** Drain the outbox once and report the outcome (sent / rejected / failed / credentialError). */
    suspend fun drain(): DrainReport
}
