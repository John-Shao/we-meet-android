package com.we.meet.feature.docs.util

/**
 * Docs deep-link helpers (设计文档 §4.7.2): every entry point hands us a docs
 * URL; the document id is the UUID embedded in it. Resolution is deliberately
 * URL-shape agnostic — chat cards, search hits and meeting-note links may each
 * carry slightly different query strings.
 */
object DocLinks {

    private val UUID_REGEX = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
    )

    /** Extracts the document UUID from any docs URL, or null when none is present. */
    fun docIdFromUrl(url: String): String? = UUID_REGEX.find(url)?.value

    /** Canonical docs-web URL for a document — used by the WebView fallback. */
    fun webUrl(docsBaseUrl: String, docId: String): String =
        "${docsBaseUrl.trimEnd('/')}/docs/$docId/"

    /**
     * 编辑画布 URL(设计文档 §4.6):`?chrome=editor` 让 docs 收敛到「纯编辑器」。
     * 镜像尚未理解该值前会退化为完整文档页(对可编辑用户即编辑器本身),不破坏加载;
     * 待镜像支持后即为无站点壳的画布。`lang` 走 UA 兜底(docs 的 i18next 已随 UA 命中)。
     */
    fun editorUrl(docsBaseUrl: String, docId: String): String =
        "${docsBaseUrl.trimEnd('/')}/docs/$docId/?embed=1&chrome=editor"
}
