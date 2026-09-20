package io.github.jevandroid.sample

import android.app.Activity
import android.os.Bundle
import android.widget.*

/** A local, account-free target with an independently inspectable outcome. */
class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 180, 40, 40) }
        column.addView(TextView(this).apply { text = "Jev SDK Test Page"; textSize = 24f })
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
}
