package com.lumina.tv

import com.lumina.tv.ui.components.posterTitleAreaHeight

/** Font-scale measurements for poster title rows. */
object M04BChecks {
    @JvmStatic
    fun main(args: Array<String>) {
        check(kotlin.math.abs(posterTitleAreaHeight(1.0f).value - 36f) < 0.01f)
        check(kotlin.math.abs(posterTitleAreaHeight(1.3f).value - 42f) < 0.01f)
        check(kotlin.math.abs(posterTitleAreaHeight(1.5f).value - 46f) < 0.01f)
        println("M04B PASS: poster title area is 36dp, 42dp, and 46dp at fontScale 1.0, 1.3, and 1.5")
    }
}
