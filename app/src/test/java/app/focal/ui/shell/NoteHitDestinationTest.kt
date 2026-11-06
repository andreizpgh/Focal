package app.focal.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteHitDestinationTest {
    @Test fun aSearchHitSurvivesTheBackStacksEncoding() {
        val destination = Destination.Note("2b7e:note:ids", NoteHit(42, 3, 9))
        assertEquals(destination, Destination.decode(Destination.encode(destination)))
        assertEquals(Destination.Note("x"), Destination.decode(Destination.encode(Destination.Note("x"))))
        assertNull(Destination.decode("hit:1,2:x"))
    }
}
