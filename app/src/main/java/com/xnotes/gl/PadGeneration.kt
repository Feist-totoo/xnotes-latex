package com.xnotes.gl

import java.util.concurrent.atomic.AtomicLong

/** Issued by the caller before queuing work, never by a delayed render callback. */
internal class PadGeneration {
    private val value = AtomicLong()
    fun next(): Long = value.incrementAndGet()
    fun current(): Long = value.get()
    fun accepts(token: Long): Boolean = value.get() == token
}
