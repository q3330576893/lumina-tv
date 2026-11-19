package com.lumina.tv.ui.screens

internal data class SearchErrorPresentation(
    val primary: String?,
    val append: String?,
)

internal fun searchErrorPresentation(
    visibleResultCount: Int,
    searchError: String?,
    appendError: String?,
): SearchErrorPresentation = if (visibleResultCount > 0) {
    SearchErrorPresentation(primary = null, append = appendError)
} else {
    SearchErrorPresentation(primary = searchError ?: appendError, append = null)
}
