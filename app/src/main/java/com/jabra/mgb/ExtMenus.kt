package com.jabra.mgb

import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.0.116 (S4): chrome.contextMenus of INSTALLED extensions in the page menus.
 *  - long press on a link / image: TabManager.showLinkMenu adds rows(...) after its own rows
 *  - page / selection: tray > "Page menu" (showPage) because a long press on text starts the normal text selection
 *  - a tap sends contextMenus.onClicked(info, tab) to the worker (ExtApi.menuClicked starts a stopped worker)
 * Not here: separators, real submenus (a child is shown as "Parent > Child"), editable / frame contexts, private tabs (no extension rows there).
 */
object ExtMenus {
    private const val MAX_ROWS = 30

    private fun ctxList(o: JSONObject): List<String> {
        val v = o.opt("contexts")
        return when (v) {
            is JSONArray -> (0 until v.length()).map { v.optString(it) }
            is String -> listOf(v)
            else -> listOf("page")
        }
    }

    private fun pats(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }

    private fun applies(o: JSONObject, link: String?, img: String?, sel: String?, pageUrl: String): Boolean {
        if (!o.optBoolean("enabled", true) || !o.optBoolean("visible", true)) return false
        if (o.optString("type", "normal") == "separator") return false
        val cs = ctxList(o)
        val ok = "all" in cs || (link != null && "link" in cs) || (img != null && "image" in cs) ||
            (!sel.isNullOrEmpty() && "selection" in cs) || (link == null && img == null && "page" in cs)
        if (!ok) return false
        val doc = pats(o, "documentUrlPatterns")
        if (doc.isNotEmpty() && !ContentInjector.urlAllowed(doc, pageUrl)) return false
        val target = pats(o, "targetUrlPatterns")
        if (target.isNotEmpty()) {
            val t = link ?: img ?: return false
            if (!ContentInjector.urlAllowed(target, t)) return false
        }
        return true
    }

    private fun titleOf(o: JSONObject, sel: String?, fallback: String): String {
        val raw = o.optString("title", "")
        val s = if (sel.isNullOrEmpty()) "" else if (sel.length > 40) sel.take(40) + "..." else sel
        val t = raw.replace("%s", s).trim()
        return t.ifEmpty { fallback }
    }

    /** Rows of all enabled extensions for this hit. link / img / sel = null when not hit. Empty in private tabs. */
    fun rows(tab: TabManager.Tab, link: String?, img: String?, sel: String?): ArrayList<MenuSheet.Item> {
        val out = ArrayList<MenuSheet.Item>()
        if (tab.priv) return out
        val pageUrl = tab.url
        for (info in ExtRegistry.enabled()) {
            if (info.id == ContentInjector.EXT_ID || "contextMenus" !in info.permissions) continue
            val items = ExtApi.menuItems(info.id)
            if (items.isEmpty()) continue
            val parents = items.mapNotNull { it.optString("parentId", "").ifEmpty { null } }.toSet()
            val byId = items.associateBy { it.optString("id") }
            for (o in items) {
                if (out.size >= MAX_ROWS) return out
                val id = o.optString("id")
                if (id in parents) continue // a parent is only the header of its children
                if (!applies(o, link, img, sel, pageUrl)) continue
                var label = titleOf(o, sel, info.name)
                var p = o.optString("parentId", "")
                var depth = 0
                while (p.isNotEmpty() && depth++ < 5) {
                    val po = byId[p] ?: break
                    label = titleOf(po, sel, info.name) + " > " + label
                    p = po.optString("parentId", "")
                }
                val type = o.optString("type", "normal")
                if ((type == "checkbox" || type == "radio") && o.optBoolean("checked", false)) label = "\u2713 " + label
                val xid = info.id
                out.add(MenuSheet.Item(info.name + ": " + label, R.drawable.ic_list) { click(tab, xid, id, link, img, sel) })
            }
        }
        return out
    }

    private fun click(tab: TabManager.Tab, xid: String, id: String, link: String?, img: String?, sel: String?) {
        val info = JSONObject().put("pageUrl", tab.url).put("frameUrl", tab.url).put("frameId", 0).put("editable", false)
        if (link != null) info.put("linkUrl", link)
        if (img != null) info.put("srcUrl", img).put("mediaType", "image")
        if (!sel.isNullOrEmpty()) info.put("selectionText", sel)
        ExtApi.menuToggle(xid, id, info) // parentMenuItemId, checked / wasChecked
        ExtApi.menuClicked(xid, id, info, TabsApi.tabJson(tab))
    }

    /** true if the tray should show "Page menu": some extension has an item for a page / selection (or all). */
    fun hasPageItems(): Boolean {
        for (info in ExtRegistry.enabled()) {
            if (info.id == ContentInjector.EXT_ID || "contextMenus" !in info.permissions) continue
            for (o in ExtApi.menuItems(info.id)) {
                val cs = ctxList(o)
                if ("page" in cs || "selection" in cs || "all" in cs) return true
            }
        }
        return false
    }

    /** Tray > Page menu: reads the selected text of the tab on screen, then shows the rows. */
    fun showPage(act: Activity) {
        val tab = TabsApi.activeTab()
        if (tab == null || tab.isExt || tab.home || !tab.url.startsWith("http")) { Util.toast(act, "Open a web page first"); return }
        tab.web.evaluateJavascript("(function(){try{return String(window.getSelection())}catch(e){return ''}})()") { raw ->
            val sel = try {
                if (raw == null || raw == "null") "" else JSONArray("[$raw]").optString(0, "").take(20000)
            } catch (e: Exception) { "" }
            val rows = rows(tab, null, null, sel.ifEmpty { null })
            if (rows.isEmpty()) Util.toast(act, "No extension menu for this page")
            else MenuSheet.showLink(act, "Extension menus", tab.url, rows)
        }
    }
}
