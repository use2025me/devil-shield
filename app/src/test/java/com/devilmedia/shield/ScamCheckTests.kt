package com.devilmedia.shield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScamCheckTests {

    @Test
    fun dpdRedeliveryTextIsAScam() {
        val r = ScamCheck.check(
            "DPD: We couldn't deliver your parcel. Please pay the redelivery fee of €1.99 at https://dpd-redelivery-ie.com/pay"
        )
        assertEquals(ScamCheck.Verdict.LIKELY_SCAM, r.verdict)
    }

    @Test
    fun parcelTextWithGluedBrandIsAScam() {
        val r = ScamCheck.check("Your package is on hold. Update your address: dpdparcel-help.top")
        assertEquals(ScamCheck.Verdict.LIKELY_SCAM, r.verdict)
    }

    @Test
    fun realDpdLinkIsNotFlagged() {
        val r = ScamCheck.check("Your DPD parcel is arriving today between 10:00 and 11:00. Track it at https://www.dpd.ie/track")
        assertEquals(ScamCheck.Verdict.NOTHING_FOUND, r.verdict)
    }

    @Test
    fun whatsappCodeRequestIsFlagged() {
        val r = ScamCheck.check("Hi it's me, I sent you a code by mistake, can you send me the code please?")
        assertEquals(ScamCheck.Verdict.LIKELY_SCAM, r.verdict)
    }

    @Test
    fun ordinaryMessageIsFine() {
        assertEquals(ScamCheck.Verdict.NOTHING_FOUND, ScamCheck.check("See you at 7 for dinner, bring the kids").verdict)
    }

    @Test
    fun strictBlockingCatchesFakesButNotRealSites() {
        assertNotNull(ScamCheck.impersonates("dpd-redelivery-ie.com", strict = true))
        assertNotNull(ScamCheck.impersonates("aib-secure-login.info", strict = true))
        assertNotNull(ScamCheck.impersonates("anpostcustoms-fee.com", strict = true))
        assertNull(ScamCheck.impersonates("dpd.ie", strict = true))
        assertNull(ScamCheck.impersonates("track.dpd.co.uk", strict = true))
        assertNull(ScamCheck.impersonates("www.amazon.co.uk", strict = true))
        assertNull(ScamCheck.impersonates("s3.eu-west-1.amazonaws.com", strict = true))
        assertNull(ScamCheck.impersonates("www.paypalobjects.com", strict = true))
        assertNull(ScamCheck.impersonates("groups.google.com", strict = true))
        assertNull(ScamCheck.impersonates("upstream.example.com", strict = true))
        assertNull(ScamCheck.impersonates("hermes.com", strict = true))
        assertNull(ScamCheck.impersonates("www.bankofireland.com", strict = true))
    }
}
