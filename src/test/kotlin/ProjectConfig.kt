// Kotest 6 does not scan for config: this exact package and class name is where it looks.
package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.engine.concurrency.SpecExecutionMode
import io.kotest.engine.coroutines.ThreadPerSpecCoroutineContextFactory

/** Specs are independent (own tempdir, own mock server, unique process markers), so run them side by side. */
class ProjectConfig : AbstractProjectConfig() {
    override val specExecutionMode = SpecExecutionMode.Concurrent
    // Concurrent specs alone share one thread; the tests block (sleep, waitFor, HTTP), so each needs its own.
    override val coroutineDispatcherFactory = ThreadPerSpecCoroutineContextFactory
}
