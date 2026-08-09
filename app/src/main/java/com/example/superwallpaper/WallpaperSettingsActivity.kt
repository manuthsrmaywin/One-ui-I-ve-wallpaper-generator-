package com.example.superwallpaper

import android.os.Bundle
import android.preference.PreferenceActivity
import android.preference.PreferenceFragment

class WallpaperSettingsActivity : PreferenceActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Load the preferences fragment safely
        fragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragment() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            
            // Set shared preferences name to match the service
            preferenceManager.sharedPreferencesName = "wallpaper_prefs"
            preferenceManager.sharedPreferencesMode = MODE_PRIVATE
            
            // Load controls from XML
            addPreferencesFromResource(R.xml.wallpaper_preferences)
        }
    }
}
