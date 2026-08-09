package com.example.superwallpaper

import android.content.Context
import android.os.Bundle
import android.preference.PreferenceActivity
import android.preference.PreferenceFragment

class WallpaperSettingsActivity : PreferenceActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragment() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            preferenceManager.sharedPreferencesName = "wallpaper_prefs"
            preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
            addPreferencesFromResource(R.xml.wallpaper_preferences)
        }
    }
}
