package com.github.vok.framework

import org.slf4j.LoggerFactory
import java.io.Closeable
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.Instant
import java.util.*
import java.io.*
import kotlin.reflect.KClass

/**
 * Closes [this] quietly - if [Closeable.close] fails, an INFO message is logged. The exception is not
 * rethrown.
 */
fun Closeable.closeQuietly() {
    try {
        close()
    } catch (e: Exception) {
        LoggerFactory.getLogger(javaClass).info("Failed to close $this", e)
    }
}

val Instant.toDate: Date get() = Date(toEpochMilli())

fun Iterable<String?>.filterNotBlank() = filterNotNull().filter { it.isNotBlank() }

val Int.days: Duration get() = toLong().days
val Long.days: Duration get() = Duration.ofDays(this)
val Int.hours: Duration get() = toLong().hours
val Long.hours: Duration get() = Duration.ofHours(this)
val Int.minutes: Duration get() = toLong().minutes
val Long.minutes: Duration get() = Duration.ofMinutes(this)
val Int.seconds: Duration get() = toLong().seconds
val Long.seconds: Duration get() = Duration.ofSeconds(this)

operator fun Duration.times(other: Int): Duration = multipliedBy(other.toLong())

/**
 * Allows you to add listeners for a particular event into your component like following:
 * ```
 * val onFilterChangeListeners = listeners<OnClickListener>()
 * ```
 */
inline fun <reified T: Serializable> listeners() = Listeners(T::class)

/**
 * Allows you to add listeners for a particular event into your component.
 *
 * Say that you have a click listener:
 * ```
 * interface OnClickListener { fun onClick(button: Int) }
 * ```
 * You can add support for click listeners into your Button component easily:
 * ```
 * class Button {
 *     val onClickListeners = listeners<OnClickListener>()
 * }
 * ```
 * The clients can then simply register their listeners as follows:
 * ```
 * val button = Button()
 * button.onClickListeners.add(object : OnClickListener {})
 * ```
 * The button can fire an event for all listeners as follows:
 * ```
 * onClickListeners.fire.onClick(2)
 * ```
 */
class Listeners<T: Serializable>(val listenerType: KClass<T>): Serializable {
    init {
        require(listenerType.java.isInterface) { "$listenerType must be an interface" }
    }

    private val listeners = mutableSetOf<T>()

    /**
     * Registers a new listener. Registering same listener multiple times has no further effect.
     *
     * The equality of the listener is measured by using the standard [Any.equals] and [Any.hashCode].
     */
    fun add(listener: T) {
        listeners.add(listener)
    }

    /**
     * Removes the listener. Removing same listener multiple times has no further effect. Does nothing
     * if the listener has not yet been registered.
     *
     * The equality of the listener is measured by using the standard [Any.equals] and [Any.hashCode].
     */
    fun remove(listener: T) {
        listeners.remove(listener)
    }

    /**
     * Use the returned value to fire particular event to all listeners.
     *
     * Returns a proxy of type [T]. Any method call on this proxy is propagated to all
     * listeners.
     */
    @Suppress("UNCHECKED_CAST")
    val fire: T = Proxy.newProxyInstance(listenerType.java.classLoader, arrayOf(listenerType.java)) { _, method, args ->
        for (listener in listeners) {
            method.invoke(listener, *args)
        }
    } as T
}
