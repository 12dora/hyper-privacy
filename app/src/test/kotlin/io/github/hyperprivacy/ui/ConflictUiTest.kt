package io.github.hyperprivacy.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import io.github.hyperprivacy.core.conflict.DeclaredScope
import io.github.hyperprivacy.core.conflict.OverlapArea
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConflictUiTest {
    @Test
    fun `modules are cleaned up, never dropped into an empty or duplicate list`() {
        val ui = ConflictUi.of(
            listOf(
                ConflictModule("com.b", " ", listOf(OverlapArea.ADS, OverlapArea.ADS)),
                ConflictModule("com.b", "dup", listOf(OverlapArea.PRIVACY)),
                ConflictModule("", "blank", listOf(OverlapArea.ADS)),
                ConflictModule("com.c", "none", emptyList()),
                ConflictModule("com.a", "A", listOf(OverlapArea.APP_LIST), DeclaredScope.AllApps),
            ),
            appListHidingOn = true,
        )!!
        assertEquals(listOf("com.a", "com.b"), ui.modules.map { it.packageName })
        assertEquals("com.b", ui.modules[1].label, "a blank label falls back to the package")
        assertEquals(listOf(OverlapArea.ADS), ui.modules[1].areas)
        assertEquals(DeclaredScope.AllApps, ui.modules[0].scope)
        assertTrue(ui.canDisableAppList)
        assertFalse(ConflictUi.of(ui.modules, appListHidingOn = false)!!.canDisableAppList)
        assertNull(ConflictUi.of(listOf(ConflictModule("com.x", "x", emptyList())), true))
    }

    @Test
    fun `the overlap page survives the saved back stack`() {
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any) = true
        }
        val stack = listOf(Route.Tabs, Route.Settings, Route.Conflicts)
        val saved = with(Route.StackSaver) { scope.save(stack) }!!
        assertEquals(stack, Route.StackSaver.restore(saved))
        assertEquals(listOf(Route.Tabs), Route.StackSaver.restore(arrayListOf("nonsense")))
    }

    @Test
    fun `pop to root keeps only the tabs`() {
        val holder = mutableStateOf(listOf(Route.Tabs, Route.Settings, Route.Conflicts))
        val nav = Navigator(holder)
        nav.popToRoot()
        assertEquals(listOf<Route>(Route.Tabs), nav.stack)
        nav.popToRoot()
        assertEquals(listOf<Route>(Route.Tabs), nav.stack)
    }
}
