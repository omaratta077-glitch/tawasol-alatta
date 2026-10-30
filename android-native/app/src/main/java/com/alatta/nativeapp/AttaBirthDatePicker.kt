package com.alatta.nativeapp

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

// يُدخل تاريخ الميلاد ويُتحقق منه ويُحفظ بتنسيق YYYY-MM-DD. يُستنتج العمر دائمًا،
// ولا يثق الخادم المحدّث برقم عمر يرسله التطبيق.
internal fun attaAgeFromBirthDate(raw: String): Int? = try {
    val birthday = LocalDate.parse(raw)
    val now = LocalDate.now()
    val age = Period.between(birthday, now).years
    age.takeIf { it in 13..120 && !birthday.isAfter(now) }
} catch (_: Exception) { null }

@Composable
internal fun AttaBirthDatePicker(value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val oldest = today.minusYears(120)
    val latest = today.minusYears(13)
    val initial = try { LocalDate.parse(value) } catch (_: Exception) { today.minusYears(20) }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        border = BorderStroke(1.dp, Color(0xFFE6B94E)),
        onClick = {
            DatePickerDialog(context, { _, year, month, day ->
                val picked = LocalDate.of(year, month + 1, day)
                if (!picked.isBefore(oldest) && !picked.isAfter(latest)) onChange(picked.toString())
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
                datePicker.minDate = oldest.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                datePicker.maxDate = latest.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                setTitle("اختر تاريخ الميلاد")
            }.show()
        }
    ) {
        Text(if (value.isBlank()) "تاريخ الميلاد"
             else "تاريخ الميلاد: ${value.substring(8, 10)}/${value.substring(5, 7)}/${value.substring(0, 4)}",
             color = if (value.isBlank()) Color(0xFFD0C7B6) else Color(0xFFE6B94E),
             fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}
