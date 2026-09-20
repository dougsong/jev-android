package io.github.jevandroid.sample

import org.junit.Assert.*
import org.junit.Test

class ProviderSelectionTest {
    @Test fun switchingProviderDoesNotReuseKeyOrModel() {
        val selection = ProviderSelection()
        val fields = selection.select(ModelBackend.DEEPSEEK, "fake-typesafe-key", "custom-jev")
        assertEquals("", fields.apiKey)
        assertEquals("deepseek-flash", fields.model)
        assertEquals(ModelBackend.DEEPSEEK, selection.backend)
    }

    @Test fun switchingBackRestoresOnlyThatProvidersDraft() {
        val selection = ProviderSelection()
        selection.select(ModelBackend.DEEPSEEK, "fake-typesafe-key", "custom-jev")
        val jev = selection.select(ModelBackend.JEV, "fake-deepseek-key", "deepseek-v4-pro")
        assertEquals(ProviderFields("fake-typesafe-key", "custom-jev"), jev)
        val deepseek = selection.select(ModelBackend.DEEPSEEK, jev.apiKey, jev.model)
        assertEquals(ProviderFields("fake-deepseek-key", "deepseek-v4-pro"), deepseek)
    }

    @Test fun clearedKeyDoesNotComeBackFromAnOlderDraft() {
        val selection = ProviderSelection()
        selection.select(ModelBackend.DEEPSEEK, "old-key", "jev-latest")
        selection.select(ModelBackend.JEV, "", "deepseek-flash")
        selection.select(ModelBackend.DEEPSEEK, "", "jev-latest")
        assertEquals("", selection.select(ModelBackend.JEV, "", "deepseek-flash").apiKey)
    }

    @Test fun newSelectionHasNoPreviousActivitiesCredentials() {
        val old = ProviderSelection()
        old.select(ModelBackend.DEEPSEEK, "private-key", "custom-jev")
        val fresh = ProviderSelection()
        assertEquals(ProviderFields("", "deepseek-flash"), fresh.select(ModelBackend.DEEPSEEK, "", "jev-latest"))
    }
}
