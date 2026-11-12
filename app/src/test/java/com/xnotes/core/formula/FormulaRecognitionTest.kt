package com.xnotes.core.formula

import com.xnotes.core.geometry.Pt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FormulaRecognitionTest {
    private fun data() = JSONObject("""{"engine":"gryphone-sat-q8-v1","scoring":"masked-quarter-v1","vocabularySha256":"bab4047952478a4ac78a8391da0bbf3ffaaec189b562dae6288d4305fbe3b07c","vocabularySize":287,"strength":0.03,"maxVisualGap":0.001,"alpha":0.25,"pairs":[[3,4,100],[3,5,1]]}""")
    private fun pack() = FormulaPack("calculus",2,"高数","Calculus","review","review",mapOf("\\int" to 5), emptyList(),emptyList(),FormulaRecognitionPrior.parse(data()))
    private fun candidate(tex: String, score: Double, modifier: Int) = FormulaCandidate(tex,score,intArrayOf(3),intArrayOf(modifier))

    @Test fun priorsOnlyRerankExistingCloseCandidatesAndRespectSelection() {
        val a=candidate("\\int x",-.001,5);val b=candidate("\\int y",-.0015,4)
        assertSame(b,FormulaCandidateRanking.rank(listOf(a,b),listOf(pack()),true).candidates.first())
        assertSame(a,FormulaCandidateRanking.rank(listOf(a,b),emptyList(),true).candidates.first())
        val far=candidate("\\int z",-.1,4)
        assertSame(a,FormulaCandidateRanking.rank(listOf(a,far),listOf(pack()),true).candidates.first())
        val plain=candidate("x",-.001,5)
        assertSame(plain,FormulaCandidateRanking.rank(listOf(plain,b),listOf(pack()),true).candidates.first())
        assertSame(b,FormulaCandidateRanking.rank(listOf(plain,b),listOf(pack()),false).candidates.first())
    }

    @Test fun incompatibleOrUnboundedStatisticsCannotAffectRecognition() {
        for (bad in listOf(data().put("engine","executable"),data().put("strength",100),data().put("maxVisualGap",1),
            data().put("vocabularySha256","other"),data().put("pairs",org.json.JSONArray("[[3,4,1],[3,4,2]]")))) {
            assertThrows(IllegalArgumentException::class.java) { FormulaRecognitionPrior.parse(bad) }
        }
    }

    @Test fun penCoordinatesAreTranslationAndScaleInvariantAndSeparateStrokesStaySeparate() {
        val strokes=listOf(listOf(Pt(0.0,0.0),Pt(20.0,20.0)),listOf(Pt(0.0,20.0),Pt(20.0,0.0)))
        val pixels=FormulaInkRaster.pixels(strokes)
        assertArrayEquals(pixels,FormulaInkRaster.pixels(strokes.map { it.map { p -> p*3.0+Pt(100.0,71.0) } }),0f)
        assertEquals(0f,pixels[224*100+50],0f)
        assertEquals(1f,pixels[224*100+100],0f)
        assertThrows(IllegalArgumentException::class.java) { FormulaInkRaster.pixels(listOf(listOf(Pt(Double.NaN,0.0)))) }
    }

    @Test fun randomMaskScheduleHasPortableUnsignedSequence() {
        val random=FormulaMaskRandom(1)
        assertEquals(270369.0/4294967296.0,random.next(),0.0)
        assertEquals(67634689.0/4294967296.0,random.next(),0.0)
        assertEquals(2647435461.0/4294967296.0,random.next(),0.0)
    }
}
