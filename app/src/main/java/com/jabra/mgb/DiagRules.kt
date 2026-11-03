package com.jabra.mgb

/**
 * S86: Diagnose part 5a. Rule engine: facts about the app / page / downloads -> list of Finding
 * (what happened + what to do + optional one tap fix). Rules 1-20 (S86) + rules 21-40 (S87.1, see rules21to40).
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
        // ---- S87.1 (all lower-case text, already masked; empty list = nothing known)
        var novelMsgs: List<String> = emptyList()    // Diag tag novel msgs + extra, last 10 min
        var mangaMsgs: List<String> = emptyList()    // Diag tag manga, last 10 min
        var logLines: List<String> = emptyList()     // V77 mgLog last 30 lines
        var hlsNoList = 0               // hls / dash items whose quality list could not be read
        var drmItems = 0
        var blobItems = 0               // detected urls that start with blob:
        var jobErrors: List<String> = emptyList()    // error text of failed V77 jobs (masked)
        var failedJobUrls: List<String> = emptyList() // masked url of each failed job / failed download (last 10 min)
        var runningJobs = 0
        var scheduleMsgs: List<String> = emptyList() // Diag tag schedule, last 24 h
        // ---- S89.0: stable codes from V77 mgLog lines "[area] code=<id> ..." (written by dbgc in shared.js)
        var mgCodes: Map<String, Int> = emptyMap()         // key "area:code" (e.g. "manga:403-blocked") -> times seen in the last 30 min (repeats=N counted)
        var mgCodeNotes: Map<String, String> = emptyMap()  // same key -> detail of the newest line (masked, lower-case, max 200)
        fun code(key: String): Int = mgCodes[key] ?: 0
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

        rules21to40(x, out)

        return out.sortedBy { when (it.level) { DiagReport.BAD -> 0; DiagReport.WARN -> 1; else -> 2 } }
    }

    private const val MID_STORAGE = 1024L * 1024 * 1024

    /** S87.1 rules 21-40. Same fix ids as before (no new DiagScreen code needed). Text checks are lower-case "contains". */
    private fun rules21to40(x: Facts, out: ArrayList<Finding>) {
        val B = DiagReport.BAD
        val W = DiagReport.WARN
        val I = DiagReport.INFO
        fun anyHas(l: List<String>, vararg k: String) = l.any { m -> k.any { m.contains(it) } }
        val novelAll = x.novelMsgs + x.logLines.filter { it.contains("novel") }
        val mangaAll = x.mangaMsgs + x.logLines.filter { it.contains("manga") }

        // 21-27 novel
        if (anyHas(x.novelMsgs, "timed out")) out.add(f("novel-timeout", W, "The Novel grabber timed out (180 s).", "The page is slow or very long. Reload it, wait until it is fully loaded, then grab again. Wi-Fi helps.", "reload", "Reload page"))
        if (anyHas(x.novelMsgs, "reloaded")) out.add(f("novel-reloaded", W, "The page reloaded while the Novel grabber was working.", "Do not scroll away or refresh while it runs. Some sites reload by themselves: try again once the page is quiet.", "reload", "Reload page"))
        if (anyHas(x.novelMsgs, "could not load file")) out.add(f("novel-script-missing", B, "A Novel grabber script file could not be loaded.", "The app files look damaged. Update or reinstall MGB.", "appinfo", "Open app settings"))
        if (anyHas(x.novelMsgs, "executescript error")) out.add(f("novel-script-error", W, "The Novel grabber script stopped with an error on this page.", "Reload the page and try again. If it repeats, copy the report; this site may need its own rule."))
        if (anyHas(x.novelMsgs, "mgnativech unreadable")) out.add(f("novel-saved-bad", I, "Saved chapter data could not be read.", "Open the Novel window and grab the chapter list again."))
        if (anyHas(novelAll, "locked", "login required", "premium", "paywall", "coins")) out.add(f("novel-locked", W, "A chapter is locked (login, coins or premium) and the grabber stopped there.", "Log in on the site in this tab, open the chapter once, then continue. Locked text cannot be read without access.", "reload", "Reload page"))
        if (anyHas(novelAll, "no chapter list", "no chapters", "chapter list not found")) out.add(f("novel-nolist", W, "No chapter list was found on this page.", "Open the novel's main (table of contents) page, not a single chapter, then run the grabber again."))

        // 28-30 manga
        if (anyHas(x.mangaMsgs, "mgnativemg unreadable")) out.add(f("manga-saved-bad", I, "Saved manga page data could not be read.", "Open the Manga window and scan the chapter again."))
        if (anyHas(mangaAll, "no pages", "0 pages", "pages not found")) out.add(f("manga-nopages", W, "No manga pages were found.", "Scroll the whole chapter once so every image loads, then run the grabber again.", "reload", "Reload page"))
        if (anyHas(mangaAll, "lazy", "not loaded yet")) out.add(f("manga-lazy", I, "Some manga images were not loaded yet (lazy loading).", "Scroll slowly to the end of the chapter, wait a second, then grab."))

        // 31-35 video kinds
        if (x.hlsNoList > 0) out.add(f("hls-nolist", W, "A streaming (HLS / DASH) list could not be read (${x.hlsNoList}).", "Press Retry in Detected files. If it fails again the link expired: play the video again to get a fresh one.", "reload", "Reload page"))
        if (x.drmItems > 0) out.add(f("drm", W, "${x.drmItems} DRM protected video(s) were found.", "Protected streams (Widevine) cannot be downloaded by any app. Pick another file on the page, if there is one."))
        if (x.blobItems > 0) out.add(f("blob-video", I, "The video address is hidden by the site (blob:).", "Let the video play for a few seconds, then open Detected files again to look for a stream (HLS) or file entry."))
        if (anyHas(x.jobErrors, "403", "401", "expired", "token", "signature", "forbidden")) out.add(f("token-expired", W, "The link looks expired (the site wants a fresh token).", "Open the video page again, play it for a moment, then download from the new entry. Old links stop working after minutes.", "reload", "Reload page"))
        if (anyHas(x.jobErrors + x.logLines, "subtitle", ".vtt", ".srt")) out.add(f("subtitle-fail", I, "A subtitle file failed.", "The video itself is not affected. Download the subtitle again from Detected files, or skip it."))

        // 36-40 jobs / storage
        if (anyHas(x.jobErrors, "convert", "ffmpeg", "mux", "merge", "size limit", "too large", "too big")) out.add(f("convert-limit", W, "The convert / merge step failed or hit a limit (size, time or memory).", "Close other tabs, keep 1 GB free, pick a lower quality, then retry. Turn off \"merge audio + video\" if it keeps failing.", "settings", "Open Settings"))
        if (anyHas(x.scheduleMsgs, "missed")) out.add(f("schedule-missed", I, "A scheduled download did not start on time.", "Android may have stopped MGB in the background. Set battery use for MGB to Unrestricted and keep the phone on.", "appinfo", "Open app settings"))
        if (x.runningJobs > 0 && x.freeBytes in LOW_STORAGE until MID_STORAGE) out.add(f("store-job", W, "Storage is getting low while a download is running.", "The running job may run out of space. Free at least 1 GB or pause the job.", "storage", "Open storage settings"))
        if (anyHas(x.jobErrors, "already exists", "exists", "duplicate")) out.add(f("dup-file", I, "A file with the same name already exists.", "Rename or delete the old file in Downloads, then retry.", "downloads", "Open Downloads"))
        val rep = x.failedJobUrls.filter { it.isNotBlank() }.groupingBy { it }.eachCount().filter { it.value >= 2 }
        if (rep.isNotEmpty()) out.add(f("repeat-fail", W, "The same address failed ${(rep.values.maxOrNull() ?: 2)} times in the last 10 minutes.", "Retrying the same link will not help. Open the page again for a fresh link, or try Wi-Fi.", "reload", "Reload page"))
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
