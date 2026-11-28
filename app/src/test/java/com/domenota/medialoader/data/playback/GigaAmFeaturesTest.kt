package com.domenota.medialoader.data.playback

import com.domenota.medialoader.data.playback.alignment.GigaAmFeatures
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class GigaAmFeaturesTest {
    @Test fun originalFiltersAndFft320MatchIndependentNumpyGolden() {
        val raw=RuntimeEnvironment.getApplication().assets.open("ai/gigaam_v3_filters.b64").bufferedReader().use{Base64.decode(it.readText(),Base64.DEFAULT)}
        val b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val window=FloatArray(320).also{b.get(it)};val mel=FloatArray(161*64).also{b.get(it)}
        val samples=FloatArray(640){i->(.25*kotlin.math.sin(2*Math.PI*440*i/16000)+.1*kotlin.math.cos(2*Math.PI*910*i/16000)).toFloat()}
        val result=GigaAmFeatures(window,mel).extract(samples){}
        // Original NumPy rfft(n=320), float32 power/mel, natural log. Synthetic tones only.
        val expected=ByteBuffer.wrap(Base64.decode("t48lweO9HMHjvRzBmqARwcbOCMHGzgjB2hIYwcvpEMHL6RDBc+kbwWPAFMFjwBTBDUoBwZ5P9cCeT/XAILvswOZq7cDmau3AtaDVwFmr1sBZq9bAOPGpwKk1q8CpNavAtchdwAy3W8AMt1vA5Hc9v3nCPb95wj2/NVaCQDhbgkA4W4JA0x+nQBcip0AXIqdA5323QAV8t0AFfLdAnQ50QLgQdEC4EHRAdxvXv4PY0r+D2NK/tS2JwJ63g8Cet4PAtMTOwNTXtcDU17XAJvH9wFNdv8BTXb/AQ3mRwL6ohsC+qIbAKzqyP3vpsj976bI/EjF0QEoGdEBKBnRAbThxQO8mcUDvJnFA2NGVPybClT8mwpU/+kSswJXzrMCV86zAI1LswKaR98CmkffAtuMXwVgaF8FYGhfB57UXwR/DK8EfwyvB7503wU5HMMFORzDBqEM4wdMLNcHTCzXBX106wTD5OcEw+TnBggcwwedgQcHnYEHBmZUrwVBoQ8FQaEPBlXU2wbmMKcG5jCnB3KUtwdefM8HXnzPBuwZFwfmuPcH5rj3BMFJAwW/+KMFv/ijB4XZHwdpkLsHaZC7Bm940wXHTNcFx0zXBcmUvwevVPcHr1T3BQAErwQ6kOsEOpDrBklQ6wbmgKsG5oCrBQdtAwRflPMEX5TzBg+0rwdzWK8Hc1ivBUBI4wTrmOcE65jnBkt07wTs4P8E7OD/BrIU/web+K8Hm/ivBoB8ywfJOK8HyTivB4JsywdvZLsHb2S7BZ74wwR6FMcEehTHBb74owS52J8EudifBy10lwcjpGcHI6RnB5B0awXSxKMF0sSjBUdojwRu4L8EbuC/BdEQXwc5FLcHORS3BDacbwezxHMHs8RzBeSgWwagPJsGoDybBqF0dwcvMGsHLzBrB8X0fwaDSH8Gg0h/BtBUYwQJ1I8ECdSPBvfEZwZT4LMGU+CzBEbobwSt0MsErdDLBcBwgwTANHMEwDRzBoMcmwe/WGsHv1hrBRfkewVmTJMFZkyTB",Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        assertEquals(64,result.size)
        for(m in 0 until 64){assertEquals(3,result[m].size);for(t in 0 until 3)assertEquals(expected.get().toDouble(),result[m][t].toDouble(),1e-4)}
    }
}
