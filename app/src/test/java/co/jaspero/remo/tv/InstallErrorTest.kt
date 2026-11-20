package co.jaspero.remo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallErrorTest {
    @Test
    fun knownCodesGetAdvice() {
        val msg = TvAdb.explain("Failure [INSTALL_FAILED_VERIFICATION_FAILURE: Package Verification Result]")
        assertTrue(msg, msg.contains("Verify apps over USB"))
        assertTrue(msg.endsWith("(INSTALL_FAILED_VERIFICATION_FAILURE)"))
        assertTrue(TvAdb.explain("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]").contains("out of space"))
    }

    @Test
    fun unknownOutputIsShownAndSilenceIsSaid() {
        assertEquals("The TV refused the app: Error: something odd", TvAdb.explain("Error: something odd\nmore"))
        assertEquals("The TV refused the app without giving a reason.", TvAdb.explain("  "))
    }
}
