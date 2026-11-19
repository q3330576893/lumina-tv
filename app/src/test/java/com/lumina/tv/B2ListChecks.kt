package com.lumina.tv

import com.lumina.tv.ui.screens.LIBRARY_PAGE_SIZE
import com.lumina.tv.ui.screens.libraryAppendProgress

internal object B2ListChecks {
    fun run() {
        for (expected in listOf(1, 7, 37, LIBRARY_PAGE_SIZE, 100)) {
            val full = libraryAppendProgress(123, hasMore = true, pageSize = expected, expectedPageSize = expected)
            check(full.nextIndex == 123 + expected && full.hasMore && !full.failed)
            for (size in listOf(0, expected - 1, expected + 1)) {
                val page = libraryAppendProgress(123, hasMore = true, pageSize = size, expectedPageSize = expected)
                check(page.nextIndex == 123 + size && !page.hasMore && !page.failed)
            }
            for (hasMore in listOf(false, true)) {
                val failed = libraryAppendProgress(123, hasMore, pageSize = null, expectedPageSize = expected)
                check(failed.nextIndex == 123 && failed.hasMore == hasMore && failed.failed)
            }
        }
        check(libraryAppendProgress(0, false, LIBRARY_PAGE_SIZE).hasMore)
        println("B2 PASS E7-B3: shared page size; configurable expected size; equality-only hasMore; failure cursor preserved")
    }
}
