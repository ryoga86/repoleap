package de.pdenis.repoleap

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import de.pdenis.repoleap.git.HostRule
import de.pdenis.repoleap.git.HostingType
import de.pdenis.repoleap.git.RemoteBrowserUrls
import de.pdenis.repoleap.scan.RepoIndexService
import de.pdenis.repoleap.settings.RepoLeapConfigurable
import de.pdenis.repoleap.settings.RepoLeapSettings
import de.pdenis.repoleap.ui.RepoLeapPopup
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Fixed sort order in the popup, web pages of remotes (incl. asking the server) and the remote rules settings. */
class RemoteLinksAndOrderPlatformTest : BasePlatformTestCase() {

    private val state get() = RepoLeapSettings.getInstance().state

    private fun <T> inBackground(block: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread(Callable { block() }).get(20, TimeUnit.SECONDS)

    private fun withSettings(block: () -> Unit) {
        val before = listOf(state.roots, state.pinnedRepos, state.hiddenRepos, state.recentRepos).map { it.toMutableList() }
        val rulesBefore = state.remoteRules.map { it.copy() }.toMutableList()
        val probeBefore = state.probeGitServers
        try {
            block()
        } finally {
            state.roots = before[0]
            state.pinnedRepos = before[1]
            state.hiddenRepos = before[2]
            state.recentRepos = before[3]
            state.remoteRules = rulesBefore
            state.probeGitServers = probeBefore
            RemoteBrowserUrls.getInstance().clearCache()
        }
    }

    fun testFavoritesThenCurrentWindowThenOtherWindowThenRestEvenForBetterSearchMatches() {
        val projectDir = Path.of(project.basePath!!)
        val gitDir = Files.createDirectories(projectDir.resolve(".git"))
        val others = FileUtil.createTempDirectory("repoleap-order", null).toPath()
        listOf("zeta-fav", "app", "app-tools").forEach { Files.createDirectories(others.resolve("$it/.git")) }

        withSettings {
            state.roots = mutableListOf(projectDir.toString(), others.toString())
            state.pinnedRepos = mutableListOf(others.resolve("zeta-fav").toString())
            state.hiddenRepos = mutableListOf()
            state.recentRepos = mutableListOf()
            val index = RepoIndexService.getInstance()
            index.invalidate()
            PlatformTestUtil.waitWithEventsDispatching("Scan did not finish", { !index.isScanning }, 20)

            val popup = RepoLeapPopup(project)
            try {
                popup.show()
                PlatformTestUtil.waitWithEventsDispatching("Scan did not finish", { !index.isScanning }, 20)
                val current = projectDir.fileName.toString()
                assertEquals(listOf("zeta-fav", current, "app", "app-tools"), popup.visibleRepoNamesForTest())

                // "app" matches the other repositories perfectly - the groups still win
                popup.setSearchTextForTest("a")
                assertEquals(listOf("zeta-fav", current, "app", "app-tools"), popup.visibleRepoNamesForTest().filter {
                    it in setOf("zeta-fav", current, "app", "app-tools")
                })
            } finally {
                popup.closeForTest()
                FileUtil.delete(gitDir)
            }
        }
    }

    fun testUnknownGitServerIsAskedOnceWhetherItIsABitbucketServer() {
        val requests = AtomicInteger()
        val bitbucket = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/rest/api/1.0/application-properties") { exchange ->
                requests.incrementAndGet()
                val body = """{"version":"9.4.21","buildNumber":"9004021","displayName":"Bitbucket"}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val other = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() } // 404 for everything
        try {
            withSettings {
                state.remoteRules = mutableListOf()
                state.probeGitServers = true
                val service = RemoteBrowserUrls.getInstance()
                service.clearCache()
                val bitbucketBase = "http://127.0.0.1:${bitbucket.address.port}"
                val otherBase = "http://127.0.0.1:${other.address.port}"

                assertEquals("$bitbucketBase/projects/TEAM/repos/app/browse", inBackground { service.resolve("$bitbucketBase/team/app.git") })
                assertEquals("$bitbucketBase/projects/TEAM/repos/lib/browse", inBackground { service.resolve("$bitbucketBase/team/lib.git") })
                assertEquals("asked only once per server", 1, requests.get())

                assertEquals("$otherBase/team/app", inBackground { service.resolve("$otherBase/team/app.git") })
                assertEquals(false, service.cachedAnswerForTest(otherBase))

                val unreachable = "http://127.0.0.1:1"
                assertEquals("$unreachable/team/app", inBackground { service.resolve("$unreachable/team/app.git") })
                assertNull(service.cachedAnswerForTest(unreachable))

                // switched off -> no request at all
                service.clearCache()
                state.probeGitServers = false
                assertEquals("$bitbucketBase/team/app", inBackground { service.resolve("$bitbucketBase/team/app.git") })
                assertEquals(1, requests.get())

                // a rule makes asking unnecessary
                state.remoteRules = mutableListOf(HostRule("127.0.0.1", HostingType.BITBUCKET_SERVER))
                assertEquals("$bitbucketBase/projects/TEAM/repos/app/browse", inBackground { service.resolve("$bitbucketBase/team/app.git") })
                assertEquals(1, requests.get())
            }
        } finally {
            bitbucket.stop(0)
            other.stop(0)
        }
    }

    fun testRemoteRulesAreStoredAndResetOnTheSettingsPage() {
        withSettings {
            state.remoteRules = mutableListOf(HostRule("git.old.example", HostingType.CUSTOM, "{base}/x/{repo}"))
            val configurable = RepoLeapConfigurable()
            try {
                configurable.createComponent()
                configurable.reset()
                val table = configurable.remoteRulesTableForTest()
                assertEquals(state.remoteRules, table.rules)
                assertFalse(configurable.isModified)

                table.model.addRow(HostRule(" git.example.org ", HostingType.BITBUCKET_SERVER))
                table.model.addRow(HostRule("", HostingType.STANDARD)) // empty host is dropped
                assertTrue(configurable.isModified)
                configurable.apply()
                assertEquals(
                    listOf(
                        HostRule("git.old.example", HostingType.CUSTOM, "{base}/x/{repo}"),
                        HostRule("git.example.org", HostingType.BITBUCKET_SERVER),
                    ),
                    state.remoteRules,
                )

                table.model.items[0].host = "changed.example"
                assertTrue(configurable.isModified)
                configurable.reset()
                assertEquals("git.old.example", table.rules.first().host)
                assertFalse(configurable.isModified)
            } finally {
                configurable.disposeUIResources()
            }
        }
    }
}
