package com.metallic.chiaki.steam.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.steam.*

/** Map every Steam input (including Guide, paddles, grips, pad clicks/touch) to a PlayStation target. */
class SteamMappingActivity : AppCompatActivity()
{
    private lateinit var store: SteamStore
    private lateinit var profile: SteamProfile

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        store = SteamStore(this)
        SteamController.init(applicationContext)
        val (sv, col) = scrollColumn()
        setContentView(sv)
        profile = store.activeProfile()
        title = "Buttons – ${profile.name}"

        col.note("Choose what each Steam input does on the PlayStation. Triggers are analog and always drive L2 / R2; the \"full pull\" entries are the extra digital switch at the end of the pull.")
        col.buttonRow("Reset to defaults" to {
            profile = profile.copy(mapping = SteamProfile.defaultMapping())
            save()
            recreate()
        })

        val targets = PsTarget.values()
        var lastGroup = ""
        for(src in SteamSource.values())
        {
            if(src.group != lastGroup) { col.header(src.group); lastGroup = src.group }
            col.label(src.label)
            col.spinner(targets.map { it.label }, profile.target(src).ordinal) { pos ->
                profile = profile.copy(mapping = profile.mapping + (src to targets[pos]))
                save()
            }
        }
    }

    private fun save()
    {
        store.saveProfile(profile)
        SteamController.reloadProfile()
    }
}
