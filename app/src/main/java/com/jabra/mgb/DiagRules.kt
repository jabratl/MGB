package com.jabra.mgb

/**
 * S86: Diagnose part 5a. Rule engine: facts about the app / page / downloads -> list of Finding
 * (what happened + what to do + optional one tap fix). Rules 1-20 here; rules 21-40 come in S87.
 * Pure logic, no UI, no Android calls (DiagReport collects the Facts).
 *
 * fix ids (DiagScreen.runFix): wifi, storage, webview, reload, settings, downloads, notif, appinfo.
 */
object DiagRules {
    class Finding(
        val id: String,
        val level: String,      // DiagReport.BAD / WARN / INFO
        val what: String,       // what happened (1 line)
        val todo: String,       // what to do (1-2 lines)
        val fix: String? = null,
        val fixLabel: String? = null
    )

    /** Everything the rules look at. All values are plain data, filled by DiagReport.assemble. */
    class Facts {
        var online = true               // validated internet
        var hasNetwork = true           // any active network at all
        var vpn = false
        var freeBytes = -1L
        var ramLow = false
        var wvMajor = 0                 // 0 = unknown
        var tabCrashed = false
        var pageIsWeb = false
        var pageLoading = false
        var pageErrors: List<String> = emptyList()   // msgs of tag "page" in the last 10 min ("load error -2 ..." / "http 403")
        var pageErrorUrls: List<String> = emptyList() // masked urls of those events
        var httpsOnly = false
        var adblock = false
        var folderSet = true
        var engineAnswered = true       // get-state answered (only meaningful when pageIsWeb)
        var engineErrors = 0            // engine console errors in the last 5 min
        var detected = 0
        var dlCodes: List<String> = emptyList()      // failed codes in the last 10 min (FILE_NO_SPACE ...)
        var sniffFails = 0              // sniffer probe failed in the last 10 min
        var notifDenied = false         // API 33+ and POST_NOTIFICATIONS not granted
        var bgRestricted = false        // API 28+ background restricted
    }

    private const val LOW_STORAGE = 200L * 1024 * 1024

    private fun f(id: String, level: String, what: String, todo: String, fix: String? = null, label: String? = null) =
        Finding(id, level, what, todo, fix, label)

    /** Sorted: Problem first, then Warning, then Info. Same order inside a level = rule number. */
    fun diagnose(x: Facts): List<Finding> {
        val out = ArrayList<Finding>()
        val B = DiagReport.BAD
        val W = DiagReport.WARN
        val I = DiagReport.INFO

        // 1-3 network
        if (!x.hasNetwork) out.add(f("net-none", B, "The phone has no network.", "Turn on Wi-Fi or mobile data, then try again.", "wifi", "Open Wi-Fi settings"))
        else if (!x.online) out.add(f("net-captive", B, "Connected, but the internet does not answer (captive portal or no data).", "Open the Wi-Fi sign-in page, or switch to mobile data.", "wifi", "Open Wi-Fi settings"))
        if (x.vpn && (x.pageErrors.any { it.startsWith("load error") } || x.dlCodes.any { it.startsWith("NETWORK") }))
            out.add(f("net-vpn", W, "A VPN is on and loading / downloading failed.", "Many sites and video servers block VPN addresses. Turn the VPN off and retry."))

        // 4-6 storage / memory
        if (x.freeBytes in 0 until LOW_STORAGE) out.add(f("store-low", B, "Free storage is under 200 MB.", "Free some space; downloads and merging need room for the temporary file too.", "storage", "Open storage settings"))
        if (x.dlCodes.contains("FILE_NO_SPACE")) out.add(f("dl-nospace", B, "A download stopped: the phone is out of space.", "Delete files or choose a download folder on a card with more space, then resume.", "storage", "Open storage settings"))
        if (x.ramLow) out.add(f("ram-low", W, "The phone is low on memory.", "Close other tabs and apps. Tabs get killed when memory runs out."))

        // 7-8 tab / WebView
        if (x.tabCrashed) out.add(f("tab-crash", B, "A tab crashed or was killed by the system in the last 5 minutes.", "Reload the page. If it repeats, close other tabs (low memory) or update WebView.", "reload", "Reload page"))
        if (x.wvMajor in 1..99) out.add(f("webview-old", W, "Android System WebView is old (version ${x.wvMajor}).", "Update Android System WebView (and Chrome) in Play Store. Old versions break many players.", "webview", "Open Play Store"))

        // 9-13 page errors
        val http = x.pageErrors.mapNotNull { Regex("^http (\\d{3})").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        if (http.contains(403) || http.contains(401)) out.add(f("http-403", W, "The site refused access (HTTP ${if (http.contains(403)) 403 else 401}).", "Log in on the site, reload the page, or the link has expired: open the video page again to get a fresh link.", "reload", "Reload page"))
        if (http.any { it == 404 || it == 410 }) out.add(f("http-404", W, "The page or file is gone (HTTP ${http.first { it == 404 || it == 410 }}).", "The link is old or wrong. Go back to the site and open it again."))
        if (http.any { it == 429 || it in 500..599 }) out.add(f("http-busy", W, "The site is busy or limits requests (HTTP ${http.first { it == 429 || it in 500..599 }}).", "Wait a few minutes, lower the download concurrency in Settings, then retry.", "settings", "Open Settings"))
        val le = x.pageErrors.firstOrNull { it.startsWith("load error") }
        if (le != null) {
            val code = le.removePrefix("load error ").substringBefore(' ').toIntOrNull() ?: 0
            val why = when (code) {
                -2 -> "The address could not be found (DNS). Check the spelling or your connection."
                -6 -> "The server refused the connection."
                -7 -> "The connection timed out. The site or your network is slow."
                -8 -> "The connection timed out."
                -11, -12 -> "A secure connection (SSL / certificate) problem. Check the phone date and time."
                -10 -> "The page tried to open an app link the browser cannot handle."
                else -> "The page could not load (code $code)."
            }
            out.add(f("page-load", W, why, "Check the connection and reload.", "reload", "Reload page"))
        }
        if (x.httpsOnly && x.pageErrorUrls.any { it.startsWith("http://") })
            out.add(f("https-only", W, "HTTPS-only blocked or broke a plain http:// page.", "Open the site with https://, or turn HTTPS-only off in Settings for this site.", "settings", "Open Settings"))

        // 14-16 download failure codes
        if (x.dlCodes.contains("NETWORK_TIMEOUT")) out.add(f("dl-timeout", W, "A download timed out.", "The server is slow or the connection dropped. Press Resume; if it repeats, try Wi-Fi.", "downloads", "Open Downloads"))
        if (x.dlCodes.contains("NETWORK_FAILED")) out.add(f("dl-network", W, "A download lost the connection.", "Check Wi-Fi / data and press Resume. An expired link needs a fresh one from the page.", "downloads", "Open Downloads"))
        if (x.dlCodes.contains("FILE_FAILED")) out.add(f("dl-file", B, "A download could not write the file.", "Choose the download folder again in Settings (the folder may be gone or no longer allowed).", "settings", "Open Settings"))

        // 17-19 grabber / sniffer / blocker
        if (x.sniffFails >= 3) out.add(f("sniff-fail", W, "Several media checks failed (${x.sniffFails}).", "The site blocks the checker or the links expire fast. Play the video for a few seconds, then open Detected files again."))
        if (x.pageIsWeb && !x.engineAnswered) out.add(f("engine-down", W, "Media Grabber did not answer.", "It may still be starting. Wait a moment and press refresh; if it stays, reload the page.", "reload", "Reload page"))
        else if (x.engineErrors > 0) out.add(f("engine-err", W, "Media Grabber logged ${x.engineErrors} error(s) in the last 5 minutes.", "Open Recent errors below. Copy the report if you want to send it."))
        if (x.pageIsWeb && x.adblock && !x.pageLoading && x.engineAnswered && x.detected == 0 && x.sniffFails > 0)
            out.add(f("adblock-strict", I, "Nothing was detected and the ad blocker is on.", "Some sites hide their player when ads are blocked. Turn the ad blocker off for a test.", "settings", "Open Settings"))

        // 20 setup: folder / permissions
        if (!x.folderSet) out.add(f("folder-unset", I, "No download folder was chosen.", "Files go to the default Downloads folder. Choose a folder in Settings if you want another place.", "settings", "Open Settings"))
        if (x.notifDenied) out.add(f("notif-denied", I, "Notifications are not allowed.", "Download progress will not show in the notification bar. Allow notifications for MGB.", "notif", "Open notification settings"))
        if (x.bgRestricted) out.add(f("bg-restricted", W, "Android restricts MGB in the background.", "Long downloads may be stopped. Set battery use for MGB to Unrestricted.", "appinfo", "Open app settings"))

        return out.sortedBy { when (it.level) { DiagReport.BAD -> 0; DiagReport.WARN -> 1; else -> 2 } }
    }

    /** "Why?" on one failed download row: the row's own text / code + the masked url. Always returns at least one Finding. */
    fun explain(text: String): List<Finding> {
        val t = text.lowercase()
        val out = ArrayList<Finding>()
        fun has(vararg k: String) = k.any { t.contains(it) }
        if (has("no_space", "no space", "enospc", "space")) out.add(f("x-space", DiagReport.BAD, "Out of storage space.", "Free some space or pick a folder with more room, then retry.", "storage", "Open storage settings"))
        if (has("403", "forbidden", "401", "unauthorized")) out.add(f("x-403", DiagReport.WARN, "The site refused the download (403).", "The link expired or needs a login. Open the video page again to get a fresh link, then download again."))
        if (has("404", "410", "not found", "gone")) out.add(f("x-404", DiagReport.WARN, "The file is not on the server any more (404).", "Open the page again and download from the new link."))
        if (has("429", "too many", "503", "502", "500")) out.add(f("x-busy", DiagReport.WARN, "The server is busy or limits requests.", "Wait a few minutes and retry. Lower the concurrency in Settings.", "settings", "Open Settings"))
        if (has("timeout", "timed out")) out.add(f("x-timeout", DiagReport.WARN, "The connection timed out.", "Slow server or weak signal. Retry on Wi-Fi.", "wifi", "Open Wi-Fi settings"))
        if (has("network", "connection", "unreachable", "resolve", "dns")) out.add(f("x-net", DiagReport.WARN, "The connection was lost.", "Check Wi-Fi / data, then retry. A VPN can also cause this.", "wifi", "Open Wi-Fi settings"))
        if (has("file_failed", "write", "permission", "folder")) out.add(f("x-file", DiagReport.BAD, "The file could not be written.", "Choose the download folder again in Settings.", "settings", "Open Settings"))
        if (has("drm", "protected", "widevine")) out.add(f("x-drm", DiagReport.WARN, "The video is DRM protected.", "Protected streams cannot be downloaded."))
        if (out.isEmpty()) out.add(f("x-unknown", DiagReport.INFO, "The download failed" + (if (text.isBlank()) "." else ": " + text.take(120)), "Retry once. If it fails again, open the full report and copy it."))
        return out
    }
}
