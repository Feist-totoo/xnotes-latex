package com.xnotes.gl
import org.junit.Assert.*
import org.junit.Test

class PadGenerationTest {
    @Test fun queuedHideCannotTakeOwnershipAfterNextPenDown() {
        val g=PadGeneration(); val hidden=ArrayList<String>(); val queued=ArrayList<() -> Unit>()
        val old=g.next()
        queued.add { if(g.accepts(old)) hidden.add("hide") }
        val next=g.next() // The pen is down before its render-thread begin message runs.
        queued.add { assertTrue(g.accepts(next)) }
        queued.forEach { it() }
        assertTrue(hidden.isEmpty())
    }
    @Test fun lateCoverWipeAndCompletionAllExpireOnNewStroke() {
        val g=PadGeneration(); val release=g.next(); val hide=g.next()
        assertFalse(g.accepts(release)); assertTrue(g.accepts(hide))
        g.next()
        repeat(3) { assertFalse(g.accepts(hide)) }
    }
    @Test fun currentHandoverStillCompletesWhenNoNewStrokeArrives() {
        val g=PadGeneration(); val token=g.next()
        assertTrue(g.accepts(token)); assertEquals(token,g.current())
    }
}
