package com.omnidocs.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omnidocs.app.ui.theme.NotesAppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI smoke test verifying that the Compose runtime,
 * typography, colors, and Material 3 theme render correctly on the Android device runtime.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun composeThemeRendersSuccessfully() {
        composeTestRule.setContent {
            NotesAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "OmniDocs On-Device Runtime",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("OmniDocs On-Device Runtime").assertIsDisplayed()
    }
}
