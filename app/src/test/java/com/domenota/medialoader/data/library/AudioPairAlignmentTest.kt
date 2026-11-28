package com.domenota.medialoader.data.library

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin
import kotlin.math.PI

class AudioPairAlignmentTest {
    // Distinct spectral frames with enough variation to establish an alignment.
    private fun recording(n:Int):List<ComparisonFrame> = List(n){ index ->
        val v=FloatArray(128)
        v[index%128]=1f
        ComparisonFrame(v,.5f)
    }
    @Test fun isolatedCoarseWordEditIsRetainedForFineConfirmation() {
        val first = recording(100)
        val edited = first.toMutableList()
        // A short word edit can be 83% spectrally similar, so a diagonal
        // substitution is optimal. An unrelated frame would instead realign
        // as missing+extra content and would not test this edge case.
        edited[36] = ComparisonFrame(FloatArray(128).apply {
            this[36] = .83f; this[120] = .5577634f
        }, .5f)
        val aligned = requireNotNull(AudioPairAlignment.align(first, edited, 256))
        assertTrue("Existing 512-ms gate alone would discard the word", aligned.zones.isEmpty())
        assertEquals(1, aligned.shortCandidates.size)
        val region = aligned.shortCandidates.single()
        assertEquals(36, aligned.columns[region.start].first)
        val added = aligned.withConfirmedShortZones(setOf(region))
        assertEquals(listOf(region), added.zones)
        assertEquals(ComparisonZoneKind.SHORT_CHANGE_UNCERTAIN, added.zoneKind(region))
    }

    @Test fun twoFineFramesConfirmShortContentButSingleCodecImpulseDoesNot() {
        val base = recording(12)
        val changed = base.toMutableList()
        changed[4] = ComparisonFrame(FloatArray(128).apply { this[124] = 1f }, .5f)
        changed[5] = ComparisonFrame(FloatArray(128).apply { this[125] = 1f }, .5f)
        assertTrue(AudioPairAlignment.confirmsShortDifference(base, changed, 2, 2, 4))
        val blip = base.toMutableList()
        blip[4] = changed[4]
        assertFalse(AudioPairAlignment.confirmsShortDifference(base, blip, 2, 2, 4))
        assertFalse(AudioPairAlignment.confirmsShortDifference(base, base, 2, 2, 4))
    }

    @Test fun finePassNeedsMatchingContextAndEnoughSignal() {
        val base = recording(12)
        val unrelated = List(12) { ComparisonFrame(FloatArray(128).apply { this[100] = 1f }, .5f) }
        assertFalse(AudioPairAlignment.confirmsShortDifference(base, unrelated, 2, 2, 4))
        val quiet = base.map { ComparisonFrame(it.spectrum, .00001f) }
        val edited = quiet.toMutableList()
        edited[4] = ComparisonFrame(FloatArray(128).apply { this[120] = 1f }, .00001f)
        edited[5] = ComparisonFrame(FloatArray(128).apply { this[121] = 1f }, .00001f)
        assertFalse(AudioPairAlignment.confirmsShortDifference(quiet, edited, 2, 2, 4))
    }

    @Test fun nearThresholdWordIsQueuedButNotAutomaticallyCalledDifferent() {
        val first = recording(100)
        val second = first.toMutableList()
        second[36] = ComparisonFrame(FloatArray(128).apply {
            this[36] = .89f; this[120] = .4559605f
        }, .5f)
        val aligned = requireNotNull(AudioPairAlignment.align(first, second, 256))
        assertTrue("Borderline spectra must not become confirmed zones", aligned.zones.isEmpty())
        assertTrue("PCM refinement must get the borderline candidate",
            aligned.shortCandidates.any { region ->
                aligned.columns[region.start].first == 36 &&
                    aligned.columns[region.start].second == 36
            })
    }

    @Test fun oneSecondLongerIntroDoesNotMarkRemainingRecording() {
        val original=recording(80)
        val intro=List(4){i->ComparisonFrame(FloatArray(128).apply{this[100+i]=1f},.4f)}
        val result=requireNotNull(AudioPairAlignment.align(original,intro+original,256))
        assertEquals(listOf(ComparisonZone(0,4)),result.zones)
        assertEquals(4,result.columns.first{it.first==0}.second)
        assertTrue(result.columns.drop(4).none{it.different})
    }
    @Test fun missingMiddleSectionRealignsLaterFrames() {
        val original=recording(90)
        val short=original.take(30)+original.drop(38)
        val result=requireNotNull(AudioPairAlignment.align(original,short,256))
        assertEquals(listOf(ComparisonZone(30,38)),result.zones)
        assertEquals(30,result.columns.first{it.first==38}.second)
    }
    @Test fun differentLevelDoesNotChangeNormalizedSpectrum() {
        val power=DoubleArray(64){(it+1)*(it+1).toDouble()}
        val a=AudioPairAlignment.normalizedSpectrum(power)
        val b=AudioPairAlignment.normalizedSpectrum(DoubleArray(64){power[it]*.04})
        assertArrayEquals(a,b,.00001f)
    }
    @Test fun unrelatedOrStationarySpectraDoNotInventConfirmedAlignment() {
        val first=recording(60)
        val unrelated=List(60){ComparisonFrame(FloatArray(128).apply{this[64+it]=1f},.5f)}
        assertNull(AudioPairAlignment.align(first,unrelated,256))
        val tone=List(80){first.first()}
        assertNull(AudioPairAlignment.align(tone,tone,256))
    }
    @Test fun insertedFrameAndRemovedFrameAreBothLocalized() {
        val first=recording(90)
        val extra=ComparisonFrame(FloatArray(128).apply{this[120]=1f},.5f)
        val second=first.take(20)+listOf(extra)+first.subList(20,50)+first.drop(51)
        val result=requireNotNull(AudioPairAlignment.align(first,second,256))
        assertEquals(2,result.zones.size)
        assertEquals(20,result.columns.first{it.first==20}.second?.minus(1))
        assertEquals(51,result.columns.first{it.first==51}.second)
    }
    @Test fun gainChangeOnDecodedSpectralFramesIsNotAnAcousticChange() {
        fun frames(gain:Float):List<ComparisonFrame>{
            val b=AcousticFingerprints.ComparisonBuilder(256){}
            repeat(8000*12){i->
                val hz=220+(i/2048)%17*93
                b.accept((gain*sin(2*PI*hz*i/8000)).toFloat())
            }
            return b.finish()
        }
        val result=requireNotNull(AudioPairAlignment.align(frames(.6f),frames(.12f),256))
        assertTrue(result.zones.isEmpty())
    }

    @Test fun actualPcmOneSecondPrefixIsNotPaintedRedAcrossEntireTrack() {
        fun frames(prefix:Boolean):List<ComparisonFrame>{
            val builder=AcousticFingerprints.ComparisonBuilder(256){}
            if(prefix)repeat(8000){i->builder.accept((.6*sin(2*PI*1800*i/8000)).toFloat())}
            repeat(8000*18){i->
                val hz=220+(i/2048)%17*93
                builder.accept((.6*sin(2*PI*hz*i/8000)).toFloat())
            }
            return builder.finish()
        }
        val result=requireNotNull(AudioPairAlignment.align(frames(false),frames(true),256))
        assertTrue(result.zones.isNotEmpty())
        assertTrue(result.zones.first().start<=1)
        assertTrue(result.zones.first().endExclusive in 3..6)
        assertTrue(result.columns.drop(6).count{it.different}<=3)
    }
    @Test fun distributedSmallDriftDoesNotMarkResamplingGapsAsEdits() {
        val first=recording(240)
        val second=buildList{
            first.forEachIndexed{i,frame->add(frame);if(i in setOf(39,79,119,159,199))add(frame)}
        }
        val result=requireNotNull(AudioPairAlignment.align(first,second,256))
        assertTrue(result.zones.isEmpty())
    }
    @Test fun adaptiveBandKeepsOffsetAndAvoidsFullGridForSimilarTracks() {
        val first=recording(1600)
        val silence=List(4){ComparisonFrame(FloatArray(0),0f)}
        val result=requireNotNull(AudioPairAlignment.align(first,silence+first,256))
        assertEquals(listOf(ComparisonZone(0,4)),result.zones)
        assertEquals(ComparisonZoneKind.SILENT_PADDING,result.zoneKind(result.zones.single()))
        assertTrue(result.evaluatedCells < first.size.toLong()*(first.size+4)/10)
        assertTrue(result.columns.drop(4).none{it.different})
    }

    @Test fun largeInternalOffsetWidensSearchAndKeepsBothRealEdits() {
        val first=List(400){i->ComparisonFrame(FloatArray(512).apply{this[i]=1f},.5f)}
        val insert=List(70){ComparisonFrame(FloatArray(512).apply{this[511]=1f},.5f)}
        val second=first.take(80)+insert+first.subList(80,250)+first.drop(320)
        val result=requireNotNull(AudioPairAlignment.align(first,second,256))
        assertEquals(listOf(ComparisonZone(80,150),ComparisonZone(320,390)),result.zones)
        assertEquals(ComparisonZoneKind.EXTRA_IN_VERSION,result.zoneKind(result.zones[0]))
        assertEquals(ComparisonZoneKind.MISSING_IN_VERSION,result.zoneKind(result.zones[1]))
        assertEquals(320,result.columns.first{it.first==320}.second)
    }

    @Test fun lowLevelNonSilentTerminalDifferenceIsUncertainNotChangedContent() {
        val music=recording(72)
        val a=music+List(9){ComparisonFrame(FloatArray(128).apply{this[117]=1f},.00010f)}
        val b=music+List(9){ComparisonFrame(FloatArray(128).apply{this[118]=1f},.00012f)}
        val result=requireNotNull(AudioPairAlignment.align(a,b,256))
        assertTrue(result.zones.isNotEmpty())
        assertEquals(result.columns.size,result.zones.last().endExclusive)
        assertEquals(ComparisonZoneKind.QUIET_EDGE_UNCERTAIN,result.zoneKind(result.zones.last()))
    }

    @Test fun realQuietAudioOnBoundaryRemainsAReportedDifference() {
        val music=recording(72)
        val a=music+List(8){ComparisonFrame(FloatArray(128).apply{this[117]=1f},.002f)}
        val b=music+List(8){ComparisonFrame(FloatArray(128).apply{this[118]=1f},.002f)}
        val result=requireNotNull(AudioPairAlignment.align(a,b,256))
        assertTrue(result.zones.isNotEmpty())
        assertNotEquals(ComparisonZoneKind.QUIET_EDGE_UNCERTAIN,result.zoneKind(result.zones.last()))
        assertNotEquals(ComparisonZoneKind.SILENT_PADDING,result.zoneKind(result.zones.last()))
    }

    @Test fun veryQuietInternalEditIsNeverClassifiedAsTerminalPadding() {
        val music=recording(72)
        val a=music.take(28)+List(8){ComparisonFrame(FloatArray(128).apply{this[117]=1f},.0001f)}+music.drop(28)
        val b=music.take(28)+List(8){ComparisonFrame(FloatArray(128).apply{this[118]=1f},.0001f)}+music.drop(28)
        val result=requireNotNull(AudioPairAlignment.align(a,b,256))
        assertTrue(result.zones.isNotEmpty())
        assertTrue(result.zones.all{result.zoneKind(it)!=ComparisonZoneKind.QUIET_EDGE_UNCERTAIN})
    }

    @Test fun silenceInsideTrackAndQuietAudibleIntroAreNotDismissedAsPadding() {
        val first=recording(90)
        val gap=List(4){ComparisonFrame(FloatArray(0),0f)}
        val middle=requireNotNull(AudioPairAlignment.align(first,first.take(30)+gap+first.drop(30),256))
        assertEquals(ComparisonZoneKind.EXTRA_IN_VERSION,middle.zoneKind(middle.zones.single()))
        val audible=List(4){i->ComparisonFrame(FloatArray(128).apply{this[100+i]=1f},.002f)}
        val intro=requireNotNull(AudioPairAlignment.align(first,audible+first,256))
        assertEquals(ComparisonZoneKind.EXTRA_IN_VERSION,intro.zoneKind(intro.zones.single()))
    }

}
