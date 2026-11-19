package com.lumina.tv.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy

/** A commit stages all operations before publishing them; restart sees the durable map only. */
internal class B2MemoryPrefs(initial: Map<String, Any> = emptyMap()) {
    val values = initial.toMutableMap()
    var commits = 0
    var failCommit = false
    var failAtCommit: Int? = null
    var commitAttempts = 0
    var publishFailedCommit = false
    var failAtOperation: Int? = null
    var operations = 0
    var boundary: (() -> Unit)? = null
    val sp: SharedPreferences = proxy(SharedPreferences::class.java) { name, args ->
        when (name) {
            "getString", "getBoolean", "getInt", "getLong" -> values[args[0]] ?: args[1]
            "contains" -> values.containsKey(args[0])
            "getAll" -> values.toMap()
            "edit" -> editor()
            else -> error("Unexpected preference method $name")
        }
    }

    private fun editor(): SharedPreferences.Editor {
        val staged = linkedMapOf<String, Any?>()
        lateinit var edit: SharedPreferences.Editor
        edit = proxy(SharedPreferences.Editor::class.java) { name, args ->
            when (name) {
                "remove", "putString", "putBoolean", "putInt", "putLong" -> {
                    boundary?.invoke()
                    check(++operations != failAtOperation) { "Injected process interruption" }
                    staged[args[0] as String] = if (name == "remove") null else args[1]
                    edit
                }
                "apply", "commit" -> {
                    boundary?.invoke()
                    val success = !failCommit && ++commitAttempts != failAtCommit
                    if (success || publishFailedCommit) {
                        if (success) commits++
                        staged.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        boundary?.invoke()
                    }
                    if (name == "commit") success else null
                }
                else -> error("Unexpected editor method $name")
            }
        }
        return edit
    }

    private fun <T : Any> proxy(type: Class<T>, call: (String, Array<out Any?>) -> Any?): T =
        checkNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        }))
}
