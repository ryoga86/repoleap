package de.pdenis.repoleap.git

/** How the web page of a repository is built from its remote URL. */
enum class HostingType {
    /** `https://host/owner/repo` - GitHub, GitLab (incl. subgroups), Gitea/Forgejo, Bitbucket Cloud, SourceHut, ... */
    STANDARD,

    /** Self-hosted Bitbucket Server / Data Center: `https://host/projects/KEY/repos/repo/browse` */
    BITBUCKET_SERVER,

    /** `https://dev.azure.com/org/project/_git/repo` */
    AZURE_DEVOPS,

    /** Link into the AWS console */
    AWS_CODECOMMIT,

    /** User defined URL template, see [RemoteUrls.fillTemplate] */
    CUSTOM,
}

/**
 * User rule from the settings: remotes on [host] are of [type]. [host] may contain `*` (e.g. `*.example.com`).
 * [template] is only used for [HostingType.CUSTOM].
 *
 * Mutable with defaults so that the IntelliJ XML serializer can store it.
 */
data class HostRule(
    var host: String = "",
    var type: HostingType = HostingType.BITBUCKET_SERVER,
    var template: String = "",
) {
    fun matches(candidateHost: String): Boolean {
        val pattern = host.trim().lowercase()
            .removePrefix("https://").removePrefix("http://").removePrefix("ssh://")
            .substringAfter('@').substringBefore('/').substringBefore(':')
        if (pattern.isEmpty()) return false
        val candidate = candidateHost.lowercase()
        if ('*' !in pattern) return candidate == pattern
        val regex = pattern.split('*').joinToString(".*") { Regex.escape(it) }
        return Regex("^$regex$").matches(candidate)
    }
}

/** A remote URL taken apart. */
data class RemoteLocation(
    /** `true` for http(s) remotes - then [scheme] and [webPort] are the ones of the remote. */
    val isHttp: Boolean,
    /** Scheme of the web page: the remote's for http(s), otherwise `https`. */
    val scheme: String,
    /** Lower case, without user and port. */
    val host: String,
    /** Explicit port of an http(s) remote. */
    val webPort: Int? = null,
    /** Explicit port of an ssh remote (7999 is the Bitbucket Server default). */
    val sshPort: Int? = null,
    /** Without leading/trailing slashes and without `.git`. */
    val path: String,
) {
    val webBase: String get() = "$scheme://$host" + (webPort?.let { ":$it" } ?: "")
    val segments: List<String> get() = path.split('/').filter { it.isNotEmpty() }
}

/** Converts git remote URLs into the web page of the repository. Pure Kotlin, no network access. */
object RemoteUrls {

    data class Detection(val type: HostingType, val template: String = "")

    /** Public hosts that use the `host/owner/repo` scheme. */
    private val STANDARD_HOSTS = setOf(
        "github.com", "gitlab.com", "bitbucket.org", "codeberg.org", "git.sr.ht", "gitea.com", "gitee.com",
        "framagit.org", "salsa.debian.org", "gitlab.gnome.org", "invent.kde.org", "gitlab.freedesktop.org",
    )

    /** Host names containing one of these use the `host/owner/repo` scheme (GitHub Enterprise, self-hosted GitLab, ...). */
    private val STANDARD_HOST_HINTS = listOf("github", "gitlab", "gitea", "forgejo", "gogs")

    private val AZURE_HOSTS = setOf("ssh.dev.azure.com", "vs-ssh.visualstudio.com", "dev.azure.com")

    private val CODECOMMIT_HOST = Regex("""^git-codecommit(?:\.([a-z0-9-]+))?\.amazonaws\.com(?:\.cn)?$""")

    /** `codecommit::eu-central-1://[profile@]repo` or `codecommit://[profile@]repo` (git-remote-codecommit) */
    private val CODECOMMIT_GRC = Regex("""^codecommit(?:::([a-z0-9-]+))?://(?:[^@/]+@)?([^/]+)$""", RegexOption.IGNORE_CASE)

    /** `scheme://[user@]host[:port][/path]` */
    private val URL_WITH_SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*)://(?:[^@/]*@)?(\[[^\]]+]|[^/:?#]+)(?::(\d*))?([^?#]*)""")

    /** scp-like syntax: `[user@]host:path`, e.g. `git@github.com:org/repo.git` */
    private val SCP_LIKE = Regex("""^(?:[^@/]+@)?([^:/\\]+):(?!//)(.+)$""")

    /** REST endpoint that every Bitbucket Server / Data Center answers without login (used to recognize it). */
    const val BITBUCKET_PROBE_PATH = "/rest/api/1.0/application-properties"

    /**
     * Resolves the web page of [remoteUrl]:
     * 1. [rules] from the settings, 2. structure of the URL and name of the host,
     * 3. [isBitbucketServer] for unknown hosts (gets the web base URL, may ask the server), 4. `host/owner/repo`.
     */
    fun toBrowserUrl(
        remoteUrl: String,
        rules: List<HostRule> = emptyList(),
        isBitbucketServer: ((webBase: String) -> Boolean?)? = null,
    ): String? {
        val location = parse(remoteUrl) ?: return null
        val detection = detect(location, rules)
            ?: if (isBitbucketServer?.invoke(location.webBase) == true) Detection(HostingType.BITBUCKET_SERVER)
            else Detection(HostingType.STANDARD)
        return build(location, detection.type, detection.template)
    }

    fun parse(remoteUrl: String): RemoteLocation? {
        val url = remoteUrl.trim()
        if (url.isEmpty() || isLocalPath(url)) return null

        CODECOMMIT_GRC.matchEntire(url)?.let { match ->
            val region = match.groupValues[1].takeIf { it.isNotEmpty() }
            val host = if (region != null) "git-codecommit.$region.amazonaws.com" else "git-codecommit.amazonaws.com"
            return RemoteLocation(isHttp = false, scheme = "https", host = host, path = "v1/repos/${match.groupValues[2]}")
        }

        val withScheme = URL_WITH_SCHEME.find(url)
        val location = if (withScheme != null) {
            val scheme = withScheme.groupValues[1].lowercase()
            val port = withScheme.groupValues[3].toIntOrNull()
            val isHttp = scheme == "http" || scheme == "https"
            if (!isHttp && scheme !in setOf("ssh", "git+ssh", "ssh+git", "git")) return null
            RemoteLocation(
                isHttp = isHttp,
                scheme = if (isHttp) scheme else "https",
                host = withScheme.groupValues[2].lowercase(),
                webPort = if (isHttp) port else null,
                sshPort = if (isHttp) null else port,
                path = cleanPath(withScheme.groupValues[4]),
            )
        } else {
            val match = SCP_LIKE.matchEntire(url) ?: return null
            RemoteLocation(isHttp = false, scheme = "https", host = match.groupValues[1].lowercase(), path = cleanPath(match.groupValues[2]))
        }
        return location.takeIf { it.host.isNotBlank() && it.path.isNotBlank() }
    }

    /** `null` if the host type can't be told from the URL (then it may be asked). */
    fun detect(location: RemoteLocation, rules: List<HostRule> = emptyList()): Detection? {
        rules.firstOrNull { it.matches(location.host) }?.let { return Detection(it.type, it.template) }

        val host = location.host
        val segments = location.segments
        val type = when {
            host in STANDARD_HOSTS -> HostingType.STANDARD
            host in AZURE_HOSTS || host.endsWith(".visualstudio.com") || "_git" in segments -> HostingType.AZURE_DEVOPS
            CODECOMMIT_HOST.matches(host) -> HostingType.AWS_CODECOMMIT
            STANDARD_HOST_HINTS.any { it in host } -> HostingType.STANDARD
            "bitbucket" in host -> HostingType.BITBUCKET_SERVER
            location.isHttp && bitbucketScmIndex(segments) >= 0 -> HostingType.BITBUCKET_SERVER
            location.sshPort == BITBUCKET_SSH_PORT -> HostingType.BITBUCKET_SERVER
            else -> null
        }
        return type?.let { Detection(it) }
    }

    fun build(location: RemoteLocation, type: HostingType, template: String = ""): String? {
        val standard = "${location.webBase}/${location.path}"
        return when (type) {
            HostingType.STANDARD -> standard
            HostingType.BITBUCKET_SERVER -> bitbucketServer(location) ?: standard
            HostingType.AZURE_DEVOPS -> azureDevOps(location) ?: standard
            HostingType.AWS_CODECOMMIT -> codeCommit(location) ?: standard
            HostingType.CUSTOM -> template.trim().takeIf { it.isNotEmpty() }?.let { fillTemplate(it, location) } ?: standard
        }
    }

    /**
     * Placeholders: `{base}` (scheme://host[:port]), `{scheme}`, `{host}`, `{port}` (`:8080` or empty), `{path}`,
     * `{owner}` (path without the last segment), `{OWNER}` (upper case), `{repo}` (last segment).
     */
    fun fillTemplate(template: String, location: RemoteLocation): String {
        val segments = location.segments
        val owner = segments.dropLast(1).joinToString("/")
        return template
            .replace("{base}", location.webBase)
            .replace("{scheme}", location.scheme)
            .replace("{host}", location.host)
            .replace("{port}", location.webPort?.let { ":$it" } ?: "")
            .replace("{path}", location.path)
            .replace("{owner}", owner)
            .replace("{OWNER}", owner.uppercase())
            .replace("{repo}", segments.lastOrNull().orEmpty())
    }

    // --- builders ---------------------------------------------------------------------------------------------

    /**
     * ssh: `host[:7999]/proj/repo`, http: `host[/context]/scm/proj/repo`
     * -> `https://host[/context]/projects/PROJ/repos/repo/browse` (`~user` -> `/users/user/repos/repo/browse`)
     */
    private fun bitbucketServer(location: RemoteLocation): String? {
        val segments = location.segments
        val scmIndex = bitbucketScmIndex(segments)
        val context = if (location.isHttp && scmIndex > 0) segments.take(scmIndex) else emptyList()
        val rest = if (scmIndex >= 0) segments.drop(scmIndex + 1) else segments
        if (rest.size != 2) return null
        val (project, repo) = rest
        val base = (listOf(location.webBase) + context).joinToString("/")
        return if (project.startsWith("~")) "$base/users/${project.drop(1)}/repos/$repo/browse"
        else "$base/projects/${project.uppercase()}/repos/$repo/browse"
    }

    /** Index of a `scm` segment that is followed by exactly `project/repo`, -1 otherwise. */
    private fun bitbucketScmIndex(segments: List<String>): Int {
        val index = segments.lastIndexOf("scm")
        return if (index >= 0 && segments.size - index - 1 == 2) index else -1
    }

    /** ssh `v3/org/project/repo` -> `https://dev.azure.com/org/project/_git/repo`; https URLs are fine as they are. */
    private fun azureDevOps(location: RemoteLocation): String? {
        val segments = location.segments
        if (segments.size == 4 && segments[0] == "v3") {
            return "https://dev.azure.com/${segments[1]}/${segments[2]}/_git/${segments[3]}"
        }
        return if ("_git" in segments) "${location.webBase}/${location.path}" else null
    }

    /** `git-codecommit.<region>.amazonaws.com/v1/repos/<repo>` -> AWS console */
    private fun codeCommit(location: RemoteLocation): String? {
        val match = CODECOMMIT_HOST.matchEntire(location.host) ?: return null
        val repo = location.segments.lastOrNull() ?: return null
        val region = match.groupValues[1].takeIf { it.isNotEmpty() }
            ?: return "https://console.aws.amazon.com/codesuite/codecommit/repositories/$repo/browse"
        return "https://$region.console.aws.amazon.com/codesuite/codecommit/repositories/$repo/browse?region=$region"
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private fun cleanPath(raw: String): String = raw.trim('/').removeSuffix(".git").trim('/')

    private fun isLocalPath(url: String): Boolean =
        url.startsWith("/") || url.startsWith("file:", ignoreCase = true) || url.startsWith(".") ||
            url.startsWith("~") || Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(url)

    private const val BITBUCKET_SSH_PORT = 7999
}
