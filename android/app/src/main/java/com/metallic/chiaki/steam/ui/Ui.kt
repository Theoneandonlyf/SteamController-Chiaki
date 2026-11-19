package com.metallic.chiaki.steam.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Tiny view-building helpers so the Steam screens need no XML. */
internal fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

internal fun Context.scrollColumn(): Pair<ScrollView, LinearLayout>
{
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(32)) }
    val sv = ScrollView(this).apply { addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
    return sv to col
}

internal fun LinearLayout.header(text: String)
{
    addView(TextView(context).apply {
        this.text = text; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, context.dp(20), 0, context.dp(6))
    })
}

internal fun LinearLayout.note(text: String): TextView
{
    val tv = TextView(context).apply { this.text = text; textSize = 13f; alpha = 0.75f; setPadding(0, context.dp(2), 0, context.dp(6)) }
    addView(tv)
    return tv
}

internal fun LinearLayout.label(text: String): TextView
{
    val tv = TextView(context).apply { this.text = text; textSize = 15f; setPadding(0, context.dp(8), 0, 0) }
    addView(tv)
    return tv
}

internal fun LinearLayout.button(text: String, onClick: () -> Unit): Button
{
    val b = Button(context).apply { this.text = text; isAllCaps = false; setOnClickListener { onClick() } }
    addView(b)
    return b
}

internal fun LinearLayout.buttonRow(vararg buttons: Pair<String, () -> Unit>)
{
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    buttons.forEach { (t, f) ->
        row.addView(Button(context).apply { text = t; isAllCaps = false; setOnClickListener { f() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    addView(row)
}

internal fun LinearLayout.switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch
{
    val sw = Switch(context).apply {
        this.text = text; isChecked = checked; textSize = 15f
        setPadding(0, context.dp(10), 0, context.dp(10))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
    addView(sw)
    return sw
}

internal fun LinearLayout.spinner(items: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner
{
    val sp = Spinner(context)
    sp.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
    sp.setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)), false)
    sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener
    {
        private var first = true
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long)
        {
            if(first) { first = false; if(pos == selected) return }
            onSelect(pos)
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }
    addView(sp)
    return sp
}

/** Labelled slider that reports the final value when released. */
internal fun LinearLayout.slider(title: String, value: Int, min: Int, max: Int, unit: String = "%", onDone: (Int) -> Unit)
{
    val tv = label("$title: $value$unit")
    val sb = SeekBar(context).apply {
        this.max = max - min
        progress = (value - min).coerceIn(0, max - min)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener
        {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { tv.text = "$title: ${p + min}$unit" }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { onDone(s.progress + min) }
        })
    }
    addView(sb)
}
