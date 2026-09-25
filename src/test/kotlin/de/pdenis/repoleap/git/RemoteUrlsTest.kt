package de.pdenis.repoleap.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteUrlsTest {

    private fun browser(url: String, rules: List<HostRule> = emptyList(), isBitbucket: ((String) -> Boolean?)? = null) =
        RemoteUrls.toBrowserUrl(url, rules, isBitbucket)

    // --- standard hosts ---------------------------------------------------------------------------------------

    @Test
    fun `github, gitlab and co use host owner repo`() {
        assertEquals("https://github.com/ryoga86/repoleap", browser("https://github.com/ryoga86/repoleap.git"))
        assertEquals("https://github.com/ryoga86/repoleap", browser("https://user:token@github.com/ryoga86/repoleap.git/"))
        assertEquals("https://github.com/ryoga86/repoleap", browser("git@github.com:ryoga86/repoleap.git"))
        assertEquals("https://gitlab.com/group/sub/project", browser("git@gitlab.com:group/sub/project.git"))
        assertEquals("https://bitbucket.org/team/repo", browser("git@bitbucket.org:team/repo.git"))
        assertEquals("https://codeberg.org/user/repo", browser("ssh://git@codeberg.org/user/repo.git"))
        assertEquals("https://github.com/org/repo", browser("git+ssh://git@github.com/org/repo.git"))
        assertEquals("https://github.com/org/repo", browser("git://github.com/org/repo.git"))
    }

    @Test
    fun `self-hosted github and gitlab are recognized by name and never asked`() {
        val neverAsk: (String) -> Boolean? = { error("must not ask for $it") }
        assertEquals("https://gitlab.example.com/team/app", browser("git@gitlab.example.com:team/app.git", isBitbucket = neverAsk))
        assertEquals("https://github.example.com/org/app", browser("ssh://git@github.example.com:2222/org/app.git", isBitbucket = neverAsk))
        assertEquals("https://git.example.com:8443/team/app", browser("https://git.example.com:8443/team/app.git"))
    }

    // --- Bitbucket Server / Data Center -----------------------------------------------------------------------

    @Test
    fun `bitbucket server recognized by host name, scm path or ssh port`() {
        val neverAsk: (String) -> Boolean? = { error("must not ask for $it") }
        assertEquals(
            "https://bitbucket.internal.example.com/projects/OPS/repos/deploy-scripts/browse",
            browser("ssh://git@bitbucket.internal.example.com/ops/deploy-scripts.git", isBitbucket = neverAsk),
        )
        assertEquals(
            "https://git.example.com/projects/PROJ/repos/repo/browse",
            browser("https://user@git.example.com/scm/proj/repo.git", isBitbucket = neverAsk),
        )
        assertEquals(
            "https://git.example.com:8443/bitbucket/projects/PROJ/repos/repo/browse",
            browser("https://git.example.com:8443/bitbucket/scm/proj/repo.git", isBitbucket = neverAsk),
        )
        assertEquals(
            "https://git.example.com/projects/PROJ/repos/repo/browse",
            browser("ssh://git@git.example.com:7999/proj/repo.git", isBitbucket = neverAsk),
        )
    }

    @Test
    fun `personal bitbucket server repositories`() {
        assertEquals(
            "https://bitbucket.example.com/users/jdoe/repos/sandbox/browse",
            browser("ssh://git@bitbucket.example.com:7999/~jdoe/sandbox.git"),
        )
        assertEquals(
            "https://git.example.com/users/jdoe/repos/sandbox/browse",
            browser("https://git.example.com/scm/~jdoe/sandbox.git"),
        )
    }

    @Test
    fun `neutral host name - the server is asked and the answer decides`() {
        val asked = mutableListOf<String>()
        val url = "ssh://git@git.example.org/core/deploy-scripts.git"

        assertEquals(
            "https://git.example.org/projects/CORE/repos/deploy-scripts/browse",
            browser(url) { asked += it; true },
        )
        assertEquals(listOf("https://git.example.org"), asked)

        assertEquals("https://git.example.org/core/deploy-scripts", browser(url) { false })
        assertEquals("https://git.example.org/core/deploy-scripts", browser(url) { null })
        assertEquals("https://git.example.org/core/deploy-scripts", browser(url, isBitbucket = null))
    }

    @Test
    fun `http remotes are asked with their scheme and port`() {
        val asked = mutableListOf<String>()
        browser("http://git.intranet:7990/team/app.git") { asked += it; false }
        assertEquals(listOf("http://git.intranet:7990"), asked)
    }

    // --- rules ------------------------------------------------------------------------------------------------

    @Test
    fun `rules win over the automatic detection and are never asked`() {
        val neverAsk: (String) -> Boolean? = { error("must not ask for $it") }
        val rules = listOf(
            HostRule("git.example.org", HostingType.BITBUCKET_SERVER),
            HostRule("*.corp.example", HostingType.CUSTOM, "{base}/browse/{OWNER}/{repo}?at={host}"),
            HostRule("gitlab.special.example", HostingType.BITBUCKET_SERVER),
        )
        assertEquals(
            "https://git.example.org/projects/CORE/repos/deploy-scripts/browse",
            browser("ssh://git@git.example.org/core/deploy-scripts.git", rules, neverAsk),
        )
        assertEquals(
            "https://code.corp.example/browse/TEAM/SUB/app?at=code.corp.example",
            browser("git@code.corp.example:team/sub/app.git", rules, neverAsk),
        )
        assertEquals(
            "https://gitlab.special.example/projects/P/repos/r/browse",
            browser("git@gitlab.special.example:p/r.git", rules, neverAsk),
        )
    }

    @Test
    fun `host patterns`() {
        assertTrue(HostRule("*.example.com").matches("git.example.com"))
        assertTrue(HostRule("GIT.Example.com").matches("git.example.com"))
        assertTrue(HostRule("https://git.example.com/").matches("git.example.com"))
        assertTrue(HostRule("git.*.example.com").matches("git.eu.example.com"))
        assertEquals(false, HostRule("*.example.com").matches("example.com"))
        assertEquals(false, HostRule("").matches("example.com"))
    }

    @Test
    fun `custom template placeholders`() {
        val location = RemoteUrls.parse("https://git.example.com:8080/group/sub/repo.git")!!
        assertEquals(
            "https|git.example.com|:8080|group/sub/repo|group/sub|GROUP/SUB|repo|https://git.example.com:8080",
            RemoteUrls.fillTemplate("{scheme}|{host}|{port}|{path}|{owner}|{OWNER}|{repo}|{base}", location),
        )
    }

    // --- other providers --------------------------------------------------------------------------------------

    @Test
    fun `azure devops`() {
        assertEquals("https://dev.azure.com/org/project/_git/repo", browser("git@ssh.dev.azure.com:v3/org/project/repo"))
        assertEquals("https://dev.azure.com/org/project/_git/repo", browser("org@vs-ssh.visualstudio.com:v3/org/project/repo"))
        assertEquals("https://dev.azure.com/org/project/_git/repo", browser("https://org@dev.azure.com/org/project/_git/repo"))
        assertEquals("https://org.visualstudio.com/project/_git/repo", browser("https://org.visualstudio.com/project/_git/repo"))
    }

    @Test
    fun `aws codecommit`() {
        val expected = "https://eu-central-1.console.aws.amazon.com/codesuite/codecommit/repositories/my-repo/browse?region=eu-central-1"
        assertEquals(expected, browser("https://git-codecommit.eu-central-1.amazonaws.com/v1/repos/my-repo"))
        assertEquals(expected, browser("ssh://git-codecommit.eu-central-1.amazonaws.com/v1/repos/my-repo"))
        assertEquals(expected, browser("codecommit::eu-central-1://my-repo"))
        assertEquals(expected, browser("codecommit::eu-central-1://profile@my-repo"))
        assertEquals("https://console.aws.amazon.com/codesuite/codecommit/repositories/my-repo/browse", browser("codecommit://my-repo"))
    }

    @Test
    fun `local paths and garbage have no web page`() {
        assertNull(browser("/Users/me/repos/origin.git"))
        assertNull(browser("file:///tmp/origin.git"))
        assertNull(browser("../origin.git"))
        assertNull(browser("C:\\repos\\origin.git"))
        assertNull(browser(""))
        assertNull(browser("https://github.com/"))
        assertNull(browser("ftp://example.com/repo.git"))
    }
}
