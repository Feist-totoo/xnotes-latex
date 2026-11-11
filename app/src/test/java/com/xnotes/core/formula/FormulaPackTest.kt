package com.xnotes.core.formula

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FormulaPackTest {
    private fun pack(id: String, vararg signals: Pair<String, Int>) = FormulaPack(id, 1, id, id,
        "review", "review", signals.toMap(), emptyList(), emptyList())

    @Test fun localRoutingPreservesCommandBoundariesAndAdmitsMixedSubjects() {
        val packs = listOf(pack("calculus", "\\partial" to 4, "\\int" to 4),
            pack("electromagnetism", "\\nabla" to 3, "\\vec{E}" to 5),
            pack("quantum", "\\hbar" to 8, "\\psi" to 5), pack("sets", "\\in" to 5))
        val faraday = "\\nabla \\times \\vec E = -\\frac{\\partial \\vec{B}}{\\partial t}"
        assertEquals(listOf("electromagnetism", "calculus"), FormulaPackRouter.recommend(faraday, packs).map { it.id })
        assertEquals("quantum", FormulaPackRouter.recommend("i\\hbar \\partial_t \\psi=H\\psi", packs).first().id)
        assertEquals(listOf("calculus"), FormulaPackRouter.recommend("\\int_0^1 f(x)dx", packs).map { it.id })
        assertTrue(FormulaPackRouter.recommend("x + 1 = y", packs).isEmpty())
    }

    @Test fun modularPackagesRejectTraversalUnsupportedEnginesAndUnsafeLatex() {
        fun valid() = JSONObject("""{"format":"xnotes-formula-pack","schema":1,"type":"vocabulary","minAppVersionCode":71,
            "id":"quantum","version":1,"nameZh":"量子","nameEn":"Quantum","reviewZh":"核对","reviewEn":"Review",
            "signals":{"\\hbar":8},"symbols":[],"templates":[]}""")
        assertEquals("quantum", FormulaPack.parse(valid().toString().toByteArray()).id)
        for (bad in listOf(valid().put("id", "../../outside"), valid().put("schema", 2),
            valid().put("type", "executable"), valid().put("minAppVersionCode", 999),
            valid().put("signals", JSONObject().put("x", 100)))) {
            assertThrows(IllegalArgumentException::class.java) { FormulaPack.parse(bad.toString().toByteArray()) }
        }
        val unsafe = valid().put("templates", org.json.JSONArray().put(JSONObject()
            .put("latex", "\\input{private}").put("labelZh", "坏").put("labelEn", "Bad")))
        assertThrows(IllegalArgumentException::class.java) { FormulaPack.parse(unsafe.toString().toByteArray()) }
    }
}
