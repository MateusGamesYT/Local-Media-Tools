package com.localmediatools.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = MainScope()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        setContentView(tv)
        scope.launch { tv.text = listOf(1, 2, 3).map { it * 2 }.joinToString() + " " + R.string.app_name }
    }
}
