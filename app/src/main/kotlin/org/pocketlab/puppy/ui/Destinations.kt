package org.pocketlab.puppy.ui

import androidx.compose.runtime.Composable
import org.pocketlab.puppy.PuppyApp

/** Screens provided by the SSH and theme packages. */
@Composable
fun Destinations(app: PuppyApp, screen: Screen, navigate: (Screen) -> Unit, back: () -> Unit) {
    PlaceholderScreen(screen.toString(), back)
}
