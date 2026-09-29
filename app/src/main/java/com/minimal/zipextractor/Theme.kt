package com.minimal.zipextractor

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

object Discord {
    val Blurple = Color(0xFF5865F2)
    val BlurpleHover = Color(0xFF4752C4)
    val Green = Color(0xFF23A55A)
    val Red = Color(0xFFDA373C)
    val TextPrimary = Color(0xFFF2F3F5)
    val TextMuted = Color(0xFFB5BAC1)
    val BgChat = Color(0xFF313338)
    val BgSidebar = Color(0xFF2B2D31)
    val BgDeep = Color(0xFF1E1F22)
    val Divider = Color(0xFF3F4147)
}

val DiscordColors = darkColorScheme(
    primary = Discord.Blurple,
    onPrimary = Color.White,
    primaryContainer = Discord.BlurpleHover,
    onPrimaryContainer = Color.White,
    secondary = Discord.Green,
    onSecondary = Color.White,
    background = Discord.BgChat,
    onBackground = Discord.TextPrimary,
    surface = Discord.BgSidebar,
    onSurface = Discord.TextPrimary,
    surfaceVariant = Discord.BgDeep,
    onSurfaceVariant = Discord.TextMuted,
    outline = Discord.Divider,
    error = Discord.Red,
    onError = Color.White
)
