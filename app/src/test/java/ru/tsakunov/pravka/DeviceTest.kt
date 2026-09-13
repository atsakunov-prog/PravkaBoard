package ru.tsakunov.pravka

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tsakunov.pravka.domain.isEinkDevice

class DeviceTest {
    @Test
    fun readersAreRecognized() {
        assertTrue(isEinkDevice("ONYX", "Onyx", "Tab Ultra C"))
        assertTrue(isEinkDevice("Boox", null, "GoColor7"))
        assertTrue(isEinkDevice("PocketBook", "PocketBook", "InkPad Color 3"))
        assertTrue(isEinkDevice("bigme", "Bigme", "B751C"))
    }

    @Test
    fun phonesAreNot() {
        assertFalse(isEinkDevice("samsung", "samsung", "SM-F956B"))
        assertFalse(isEinkDevice("Google", "google", "Pixel 9"))
        assertFalse(isEinkDevice(null, null, null))
    }
}
