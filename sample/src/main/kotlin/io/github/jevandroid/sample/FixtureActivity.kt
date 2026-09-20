package io.github.jevandroid.sample

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.widget.*

/** A local, account-free target with an independently inspectable outcome. */
class FixtureActivity : Activity() {
    companion object { const val EXTRA_KIND = "fixture_kind" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 180, 40, 40) }
        column.addView(TextView(this).apply { text = "Jev SDK Test Page"; textSize = 24f })
        if (intent.getStringExtra(EXTRA_KIND) == FixtureKind.LONG_PRESS.name) {
            addLongPressFixture(column)
            setContentView(column)
            return
        }
        val input = EditText(this).apply { hint = "Content to save"; contentDescription = "Content input field" }
        val result = TextView(this).apply { text = "Not saved yet"; textSize = 20f }
        column.addView(input)
        column.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { result.text = "Saved: ${input.text}"; input.clearFocus() }
        })
        column.addView(result)
        setContentView(column)
    }

    private fun addLongPressFixture(column: LinearLayout) {
        val result = TextView(this).apply { text = "Waiting for a long press"; textSize = 20f }
        val stats = TextView(this).apply { text = "Completed holds: 0; duration: 0 ms" }
        var completedHolds = 0
        column.addView(Button(this).apply {
            text = "Hold to confirm"
            setOnClickListener { result.text = "A tap is not a long press" }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP -> {
                        val duration = event.eventTime - event.downTime
                        if (duration >= 1_500) {
                            completedHolds++
                            result.text = "Long press confirmed"
                            stats.text = "Completed holds: $completedHolds; duration: $duration ms"
                        } else view.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> result.text = "Hold cancelled"
                }
                true
            }
        })
        column.addView(Button(this).apply {
            text = "Native long click"
            setOnClickListener { result.text = "A tap is not a long press" }
            setOnLongClickListener { result.text = "Native long click confirmed"; true }
        })
        column.addView(result)
        column.addView(stats)
    }
}
