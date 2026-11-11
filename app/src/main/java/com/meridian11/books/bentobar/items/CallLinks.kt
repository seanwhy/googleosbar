package com.meridian11.books.bentobar.items

import java.util.Locale

/**
 * Finds video-call links in an event's location and description. Pure Kotlin (no Android), so
 * it's unit-tested on the JVM. Only real meeting links count: a Todoist task synced into the
 * calendar carries a Todoist URL, and that must not turn into a Join button.
 */
object CallLinks {
    private val urlRe = Regex("""https?://[^\s<>"')\]]+""")

    /**
     * Video-call hosts. A host matches itself and its subdomains ("webex.com" matches
     * "acme.webex.com"). With a path prefix, the link's path must start with one of them.
     */
    private class CallHost(val host: String, val paths: List<String> = emptyList(), val subdomainsOnly: Boolean = false)

    private val callHosts = listOf(
        CallHost("meet.google.com"),
        // zoom.us/j/… and zoom.us/my/… are meetings; the rest of zoom.us (and www) is Zoom's website.
        CallHost("zoom.us", paths = listOf("/j/", "/my/")),
        CallHost("www.zoom.us", paths = listOf("/j/", "/my/")),
        // Company and data-center hosts such as us02web.zoom.us or acme.zoom.us.
        CallHost("zoom.us", subdomainsOnly = true),
        CallHost("teams.microsoft.com", paths = listOf("/l/meetup-join", "/meet/")),
        CallHost("teams.live.com", paths = listOf("/meet")),
        CallHost("webex.com", subdomainsOnly = true),
        CallHost("whereby.com"),
        CallHost("meet.jit.si"),
        CallHost("chime.aws"),
        CallHost("gotomeet.me"),
        CallHost("meet.goto.com"),
    )

    /**
     * Task apps that sync tasks into Google Calendar as events (Todoist two-way sync lands in the
     * user's own account, fully editable, so access level alone can't tell them from meetings).
     * Each such event links to its task in the description.
     */
    private val taskHosts = listOf(
        CallHost("todoist.com", paths = listOf("/app/task/", "/showTask")),
        CallHost("app.todoist.com", paths = listOf("/app/task/", "/showTask")),
        CallHost("tasks.google.com"),
        CallHost("ticktick.com", paths = listOf("/webapp/")),
        CallHost("any.do", subdomainsOnly = true),
    )

    /** True when [text] (an event's description or location) links to a task in a task app. */
    fun isTask(text: String): Boolean = urlRe.findAll(text).map { it.value.trimEnd('.', ',', ';') }.any { u ->
        val (host, path) = split(u) ?: return@any false
        taskHosts.any { c ->
            val hostOk = if (c.subdomainsOnly) host.endsWith("." + c.host) else host == c.host || host.endsWith("." + c.host)
            hostOk && (c.paths.isEmpty() || c.paths.any { path.startsWith(it) })
        }
    }

    /** The first video-call link in [text], or null when there is none (other URLs don't count). */
    fun find(text: String): String? =
        urlRe.findAll(text).map { it.value.trimEnd('.', ',', ';') }.firstOrNull { isCall(it) }

    /** True when [url] is a meeting link on one of [callHosts]. */
    fun isCall(url: String): Boolean {
        val (host, path) = split(url) ?: return false
        return callHosts.any { c ->
            val hostOk = if (c.subdomainsOnly) host.endsWith("." + c.host) && host != "www." + c.host
                else host == c.host || (c.paths.isEmpty() && host.endsWith("." + c.host))
            hostOk && (c.paths.isEmpty() || c.paths.any { path.startsWith(it) })
        }
    }

    /** True when [text] holds a web link (a location that is a URL gets no Directions). */
    fun hasUrl(text: String): Boolean = urlRe.containsMatchIn(text)

    /** Lowercased host (no user info or port) and the raw path of an http(s) URL. */
    private fun split(url: String): Pair<String, String>? {
        // A browser ends the host at a backslash, so what follows one says nothing about where the link goes.
        if ('\\' in url) return null
        val rest = url.substringAfter("://", "").ifEmpty { return null }
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val host = rest.substring(0, end).substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT)
        val path = if (end < rest.length && rest[end] == '/') rest.substring(end).substringBefore('?').substringBefore('#') else "/"
        return if (host.isEmpty()) null else host to path
    }
}
