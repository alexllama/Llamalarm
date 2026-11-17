package com.llama.llamalarm.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import java.time.LocalDate
import java.time.LocalTime

fun showTimePicker(
    context: Context,
    initialHour: Int,
    initialMinute: Int,
    onPicked: (hour: Int, minute: Int) -> Unit,
) {
    TimePickerDialog(
        context,
        { _, hour, minute -> onPicked(hour, minute) },
        initialHour,
        initialMinute,
        false,
    ).show()
}

fun showDatePicker(
    context: Context,
    initial: LocalDate,
    minDate: LocalDate? = null,
    onPicked: (LocalDate) -> Unit,
) {
    val dialog = DatePickerDialog(
        context,
        { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day)) },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    )
    if (minDate != null) {
        dialog.datePicker.minDate = minDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    dialog.show()
}

fun LocalTime.hourMinute(): Pair<Int, Int> = hour to minute
