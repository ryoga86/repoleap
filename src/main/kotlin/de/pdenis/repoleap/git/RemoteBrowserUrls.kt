package de.pdenis.repoleap.git

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.io.HttpRequests
import de.pdenis.repoleap.RepoLeapBundle
import de.pdenis.repoleap.settings.RepoLeapSettings
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the web page of git remotes with the rules from the settings and - for hosts whose type can't be told
 * from the URL - one request to that git server to find out whether it is a Bitbucket Server (remembered per host
 * until the IDE is restarted). Nothing is sent anywhere else.
 */
@Service(Service.Level.APP)
class RemoteBrowserUrls {

    private class Answer(val isBitbucket: Boolean?, val timeMillis: Long)

    private val answers = ConcurrentHashMap<String, Answer>()

    /** May block for up to [TIMEOUT_MILLIS] per unknown host - call it from a background thread. */
    fun resolve(remoteUrl: String): String? {
        val state = RepoLeapSettings.getInstance().state
        val rules = state.remoteRules.map { it.copy() }
        val probe: ((String) -> Boolean?)? = if (state.probeGitServers) ::isBitbucketServer else null
        return RemoteUrls.toBrowserUrl(remoteUrl, rules, probe)
    }

    /**
     * Like [resolve], but without network access: `null` if the server would have to be asked first
     * (then call [resolve] in the background). Fast enough for the EDT.
     */
    fun resolveWithoutNetwork(remoteUrl: String): String? {
        val location = RemoteUrls.parse(remoteUrl) ?: return null
        val state = RepoLeapSettings.getInstance().state
        RemoteUrls.detect(location, state.remoteRules.map { it.copy() })?.let { return RemoteUrls.build(location, it.type, it.template) }
        if (!state.probeGitServers) return RemoteUrls.build(location, HostingType.STANDARD)
        val known = answers[location.webBase] ?: return null
        if (known.isBitbucket == null) return null
        return RemoteUrls.build(location, if (known.isBitbucket) HostingType.BITBUCKET_SERVER else HostingType.STANDARD)
    }

    /** Background thread. */
    fun resolveAll(remotes: List<Remote>): List<Remote> = remotes.map { it.copy(browserUrl = resolve(it.url)) }

    /** EDT. Resolves [remote] in a cancellable background task and opens the result in the browser. */
    fun openInBrowser(project: Project?, remote: Remote) {
        val host = RemoteUrls.parse(remote.url)?.host ?: remote.url
        object : Task.Backgroundable(project, RepoLeapBundle.message("progress.resolvingRemote", host), true) {
            private var url: String? = null

            override fun run(indicator: ProgressIndicator) {
                url = resolve(remote.url)
            }

            override fun onSuccess() {
                val resolved = url
                if (resolved != null) {
                    BrowserUtil.browse(resolved)
                } else {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP)
                        .createNotification(RepoLeapBundle.message("notification.noWebPage", remote.url), NotificationType.WARNING)
                        .notify(project)
                }
            }
        }.queue()
    }

    /** `true`/`false` if the server answered, `null` if it could not be reached (asked again after a while). */
    internal fun isBitbucketServer(webBase: String): Boolean? {
        val now = System.currentTimeMillis()
        answers[webBase]?.let { cached ->
            if (cached.isBitbucket != null || now - cached.timeMillis < RETRY_UNREACHABLE_MILLIS) return cached.isBitbucket
        }
        val answer = askServer(webBase)
        answers[webBase] = Answer(answer, now)
        return answer
    }

    private fun askServer(webBase: String): Boolean? =
        try {
            val body = HttpRequests.request(webBase + RemoteUrls.BITBUCKET_PROBE_PATH)
                .connectTimeout(TIMEOUT_MILLIS)
                .readTimeout(TIMEOUT_MILLIS)
                .productNameAsUserAgent()
                .accept("application/json")
                .readString()
            BITBUCKET_RESPONSE.containsMatchIn(body)
        } catch (_: HttpRequests.HttpStatusException) {
            false // the server answered, but it is not a Bitbucket
        } catch (_: IOException) {
            null
        }

    fun clearCache() = answers.clear()

    @TestOnly
    internal fun cachedAnswerForTest(webBase: String): Boolean? = answers[webBase]?.isBitbucket

    companion object {
        private const val NOTIFICATION_GROUP = "RepoLeap"
        const val TIMEOUT_MILLIS = 3_000
        private const val RETRY_UNREACHABLE_MILLIS = 5 * 60_000L
        private val BITBUCKET_RESPONSE = Regex(""""displayName"\s*:\s*"Bitbucket""")

        @JvmStatic
        fun getInstance(): RemoteBrowserUrls = service()
    }
}
