package com.bbsvoice.tts.edge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handshake token is the one piece of the protocol that cannot be checked by making a request and
 * looking at the answer, so it is pinned to golden values produced by the reference client
 * ({@code tools/edge_tts.js}), which is the implementation the live service was verified against.
 */
class SecMsGecTest
{
    private static final String T0 = "7ECB79D14E3AA576D2D79E6D487A1388156D91E614B1BE11C64226A29BC8DD8C";
    private static final String T2023 = "42301B335578FEFDAE2637DED1ABD614505D432559EC08032B82048483726AFF";
    private static final String T2026 = "42D1947403FD94975436C65DBFCA8003073F9CB5C3F1CE25AF8961A11D7C3DFE";

    @Test
    void matchesTheReferenceClient()
    {
        assertEquals(T0, SecMsGec.tokenFor(0L));
        assertEquals(T2023, SecMsGec.tokenFor(1700000000000L));
        assertEquals(T2026, SecMsGec.tokenFor(1767225600000L));
    }

    @Test
    void isStableInsideAFiveMinuteBucket()
    {
        /*
         * This is the property the whole handshake rests on: the client and the service only agree if
         * both land on the same bucket, so a clock that is off by seconds must not change the token.
         */
        assertEquals(SecMsGec.tokenFor(1790000300000L), SecMsGec.tokenFor(1790000299999L));
    }

    @Test
    void movesToANewTokenInTheNextBucket()
    {
        assertNotEquals(SecMsGec.tokenFor(1790000300000L), SecMsGec.tokenFor(1790000600000L));
    }

    @Test
    void theNativeTokenIsUsable()
    {
        /* generate() is what the client actually calls; a 403 in game would come from here. */
        assertEquals(SecMsGec.tokenFor(System.currentTimeMillis()), SecMsGec.generate());
    }

    @Test
    void isUppercaseSha256Hex()
    {
        for (long millis : new long[] { 0L, 1700000000000L, System.currentTimeMillis() })
        {
            String token = SecMsGec.tokenFor(millis);

            assertEquals(64, token.length(), token);
            assertTrue(token.matches("[0-9A-F]{64}"), token);
        }
    }
}
