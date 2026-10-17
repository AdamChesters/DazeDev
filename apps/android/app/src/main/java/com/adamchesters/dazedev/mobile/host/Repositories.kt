package com.adamchesters.dazedev.mobile.host

import org.json.JSONArray
import org.json.JSONObject

data class RepositoryInfo(
    val path: String,
    val name: String,
    val branch: String,
    val dirty: Boolean,
    val remote: String,
)

data class GithubRepository(
    val fullName: String,
    val description: String,
)

/** What `inventory` reports for the Repositories screen. */
data class RepositoryInventory(
    val repositories: List<RepositoryInfo>,
    val identities: List<String>,
    val activeIdentity: String,
    val home: String,
    val warnings: List<String>,
)

/** Reads the helper's repository replies and builds clone and worktree requests. */
object Repositories {
    private val fullName = Regex("[\\w.-]+/[\\w.-]+")

    fun info(value: JSONObject) =
        RepositoryInfo(
            path = value.getString("path"),
            name = value.optString("name").ifEmpty { value.getString("path").trimEnd('/').substringAfterLast('/') },
            branch = value.optString("branch"),
            dirty = value.optBoolean("dirty"),
            remote = value.optString("remote"),
        )

    fun inventory(value: JSONObject): RepositoryInventory {
        val repos = value.optJSONArray("repos") ?: JSONArray()
        val identities = value.optJSONArray("identities") ?: JSONArray()
        val logins = (0 until identities.length()).mapNotNull { identities.optJSONObject(it) }.filter { Regex("[A-Za-z0-9-]+").matches(it.optString("login")) }
        val warnings = value.optJSONArray("warnings") ?: JSONArray()
        return RepositoryInventory(
            repositories = (0 until repos.length()).map { info(repos.getJSONObject(it)) }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            identities = logins.map { it.getString("login") },
            activeIdentity = logins.firstOrNull { it.optBoolean("active") }?.getString("login") ?: logins.firstOrNull()?.getString("login").orEmpty(),
            home = value.optString("home"),
            warnings = (0 until warnings.length()).map { warnings.getString(it) },
        )
    }

    /** github_repos returns pages of repositories (gh api --paginate --slurp); invalid names are dropped. */
    fun github(value: Any): List<GithubRepository> {
        val pages = value as? JSONArray ?: error("The host returned an invalid repository list.")
        val all = ArrayList<GithubRepository>()
        for (i in 0 until pages.length()) {
            val page = pages.opt(i)
            val items = if (page is JSONArray) (0 until page.length()).map { page.opt(it) } else listOf(page)
            for (item in items) {
                if (item !is JSONObject) continue
                val name = item.optString("full_name")
                if (fullName.matches(name)) all.add(GithubRepository(name, item.optString("description").takeIf { it != "null" }.orEmpty()))
            }
        }
        return all.distinctBy { it.fullName }
    }

    /** The folder `clone` creates: the repository's name inside the first repository root. */
    fun cloneDestination(
        root: String,
        repository: String,
    ): String {
        require(fullName.matches(repository)) { "Choose a GitHub repository as owner/name." }
        return root.trimEnd('/') + "/" + repository.substringAfter('/')
    }

    /** A suggested worktree folder next to the repository: <repository>-<branch>. */
    fun worktreeDestination(
        repository: String,
        branch: String,
    ): String {
        val path = repository.trimEnd('/')
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val suffix = branch.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-')
        return "$parent/$name-${suffix.ifEmpty { "worktree" }}"
    }

    /** A first check of a new branch name; the host runs git check-ref-format as well. */
    fun branchProblem(branch: String): String? =
        when {
            branch.isBlank() -> "Enter a branch name."
            branch.startsWith("-") || branch.startsWith("/") || branch.endsWith("/") || branch.endsWith(".") || branch.endsWith(".lock") -> "Branch names cannot start with - or /, or end with /, . or .lock."
            branch.contains("..") || branch.contains("@{") || branch.contains("//") || branch.any { it.code < 33 || it.code == 127 || it in "~^:?*[\\" } -> "Branch names cannot contain spaces, .., @{ or any of ~ ^ : ? * [ \\."
            else -> null
        }

    fun cloneArgs(
        repository: String,
        root: String,
        identity: String,
    ): JSONObject = JSONObject().put("repo", repository).put("root", root).put("identity", identity)

    /** [runScript] stays unset until the host asks whether to run the repository's worktree script. */
    fun worktreeArgs(
        repository: String,
        branch: String,
        destination: String,
        identity: String,
        runScript: Boolean? = null,
    ): JSONObject {
        branchProblem(branch)?.let { throw IllegalArgumentException(it) }
        require(destination.isNotBlank() && !destination.startsWith("-")) { "Choose a worktree folder that does not start with -." }
        val args =
            JSONObject()
                .put("path", repository)
                .put("branch", branch)
                .put("destination", destination)
                .put("identity", identity)
        if (runScript != null) args.put("runScript", runScript)
        return args
    }
}
