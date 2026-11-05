package io.github.jonjonesbr.jangada.chat

import android.content.Context
import android.text.format.DateUtils
import io.github.jonjonesbr.jangada.R

/** Nome curto de uma janela para avisos: "limite de 5 horas", "limite semanal" ou o nome que o agente deu. */
internal fun limitKindName(context: Context, w: LimitWindow): String = when (w.kind) {
    LimitWindow.Kind.FIVE_HOUR -> context.getString(R.string.limit_name_five_hour)
    LimitWindow.Kind.WEEKLY -> context.getString(R.string.limit_name_weekly)
    LimitWindow.Kind.OTHER -> w.label.orEmpty()
}

/** Horário de renovação: só a hora se for hoje; senão, dia da semana e hora. */
internal fun formatLimitReset(context: Context, ms: Long): String {
    val flags = if (DateUtils.isToday(ms)) {
        DateUtils.FORMAT_SHOW_TIME
    } else {
        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_TIME
    }
    return DateUtils.formatDateTime(context, ms, flags)
}
