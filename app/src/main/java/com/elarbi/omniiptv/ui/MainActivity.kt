package com.elarbi.omniiptv.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.home.HomeSyncWorker

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frame)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.frame, MainFragment()).commit()
            HomeSyncWorker.runNow(this)
        }
    }
}
