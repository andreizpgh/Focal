package app.focal

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class InterfaceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun languageAndAppearanceCanBeChangedWithoutLeavingTheApp() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Speech language").performClick()
        compose.onNodeWithText("Русский").performClick()
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithText("Light").performClick()
        compose.onNodeWithContentDescription("Back to notes").performClick()
        compose.onNodeWithText("Русский  ·  On this device").assertExists()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Speech language").performClick()
        compose.onNodeWithText("English").performClick()
        compose.onNodeWithContentDescription("Back to notes").performClick()
    }
}
