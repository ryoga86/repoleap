package de.pdenis.repoleap.scan

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import de.pdenis.repoleap.settings.RepoLeapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps the list of discovered repositories in memory.
 *
 * The popup shows the cached list immediately and triggers a [rescan] in the background;
 * listeners are notified on the EDT once fresh results are available.
 *
 * [rescan], [invalidate], [isScanning] and the listeners are EDT-only.
 */
@Service(Service.Level.APP)
class RepoIndexService(private val scope: CoroutineScope) {

    /** Last scan result, `null` if nothing has been scanned yet. */
    @Volatile
    var repositories: List<RepoEntry>? = null
        private set

    private var scanJob: Job? = null

    /** `true` while a scan is running (EDT only). */
    var isScanning: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** [listener] is invoked on the EDT after every finished scan until [parent] is disposed. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /** Starts a new background scan; a scan that is still running is cancelled. */
    fun rescan() {
        val options = RepoLeapSettings.getInstance().scanOptions()
        scanJob?.cancel()
        isScanning = true
        scanJob = scope.launch(Dispatchers.IO) {
            val thisJob = coroutineContext.job
            val result = try {
                RepoScanner(options, checkCanceled = { ensureActive() }).scan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Scanning repository folders failed", e)
                null
            }
            withContext(Dispatchers.EDT) {
                // A newer scan was started in the meantime -> its result wins
                if (scanJob !== thisJob) return@withContext
                if (result != null) repositories = result
                isScanning = false
                listeners.forEach { it() }
            }
        }
    }

    /** Drops the cache and scans again, e.g. after the settings changed. */
    fun invalidate() {
        repositories = null
        rescan()
    }

    companion object {
        @JvmStatic
        fun getInstance(): RepoIndexService = service()
    }
}
