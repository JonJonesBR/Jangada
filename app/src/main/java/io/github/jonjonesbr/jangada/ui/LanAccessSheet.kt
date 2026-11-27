package io.github.jonjonesbr.jangada.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.TerminalService
import java.net.URLEncoder

/**
 * Painel inferior (Sheet) para ligar, compartilhar, copiar ou desligar o acesso pelo computador (LAN).
 */
object LanAccessSheet {

    private val IPV4_HOST = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * Converte URL com IP local (ex.: `http://192.168.0.103:8765`) para `sslip.io`
     * (`http://192-168-0-103.sslip.io:8765`), que o WhatsApp reconhece como link clicável inteiro.
     */
    fun toShareableUrl(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
        val host = uri.host ?: return url
        if (!IPV4_HOST.matches(host)) return url
        val sslipHost = host.replace('.', '-') + ".sslip.io"
        val scheme = uri.scheme ?: "http"
        val portPart = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$sslipHost$portPart"
    }

    fun directLink(status: TerminalService.LanStatus, chatKey: String? = null): String? {
        val url = status.url ?: return null
        val pin = status.pin ?: return null
        val base = "$url/?pin=$pin"
        if (chatKey.isNullOrEmpty()) return base
        return "$base&chat=${URLEncoder.encode(chatKey, "UTF-8")}"
    }

    fun shareLink(status: TerminalService.LanStatus, chatKey: String? = null): String? {
        val url = status.url ?: return null
        val pin = status.pin ?: return null
        val shareUrl = toShareableUrl(url)
        val base = "$shareUrl/?pin=$pin"
        if (chatKey.isNullOrEmpty()) return base
        return "$base&chat=${URLEncoder.encode(chatKey, "UTF-8")}"
    }

    fun copyText(context: Context, label: String, text: String, toastRes: Int) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, toastRes, Toast.LENGTH_SHORT).show()
    }

    fun shareAccess(activity: Activity, status: TerminalService.LanStatus, chatKey: String? = null) {
        val link = shareLink(status, chatKey) ?: return
        val text = activity.getString(R.string.lan_share_text, link)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.lan_share_link)))
    }

    fun show(
        activity: Activity,
        service: TerminalService,
        chatKey: String? = null,
        onChanged: () -> Unit = {},
    ) {
        val status = service.lanStatus()
        val sheet = Sheet(activity).title(activity.getString(R.string.lan_dialog_title))

        if (!status.running) {
            sheet.message(activity.getString(R.string.lan_dialog_off))
                .row(
                    Sheet.Row(
                        title = activity.getString(R.string.lan_turn_on),
                        description = activity.getString(R.string.lan_turn_on_desc),
                    ) {
                        service.startLanServer()
                        onChanged()
                        show(activity, service, chatKey, onChanged)
                    },
                )
                .show()
            return
        }

        val url = status.url
        val pin = status.pin.orEmpty()
        val link = directLink(status, chatKey)

        sheet.content(buildStatusCard(activity, status, url, pin))

        if (link != null && url != null) {
            sheet.row(
                Sheet.Row(
                    title = activity.getString(R.string.lan_share_link),
                    description = activity.getString(R.string.lan_share_link_desc),
                ) {
                    shareAccess(activity, status, chatKey)
                },
            )
            sheet.row(
                Sheet.Row(
                    title = activity.getString(R.string.lan_copy_link),
                    description = activity.getString(R.string.lan_copy_link_desc),
                ) {
                    copyText(activity, "Jangada LAN", link, R.string.lan_copied)
                },
            )
            sheet.row(
                Sheet.Row(
                    title = activity.getString(R.string.lan_copy_address),
                    description = activity.getString(R.string.lan_copy_address_desc),
                ) {
                    copyText(activity, "Jangada LAN URL", url, R.string.lan_address_copied)
                },
            )
        }

        sheet.row(
            Sheet.Row(
                title = activity.getString(R.string.lan_new_pin),
                description = activity.getString(R.string.lan_new_pin_desc),
            ) {
                service.regenerateLanPin()
                onChanged()
                show(activity, service, chatKey, onChanged)
            },
        )

        sheet.row(
            Sheet.Row(
                title = activity.getString(R.string.lan_turn_off),
                description = activity.getString(R.string.lan_turn_off_desc),
                destructive = true,
            ) {
                service.stopLanServer()
                onChanged()
            },
        )

        sheet.show()
    }

    private fun buildStatusCard(
        context: Context,
        status: TerminalService.LanStatus,
        url: String?,
        pin: String,
    ): LinearLayout {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val textColor = ContextCompat.getColor(context, R.color.setup_text)
        val mutedColor = ContextCompat.getColor(context, R.color.setup_muted_text)
        val activeColor = ContextCompat.getColor(context, R.color.setup_done)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val connText = if (status.connectedClients <= 0) {
            context.getString(R.string.lan_connected_none)
        } else {
            context.getString(R.string.lan_connected_count, status.connectedClients)
        }
        card.addView(
            TextView(context).apply {
                text = connText
                setTextColor(activeColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
            },
        )

        card.addView(
            TextView(context).apply {
                text = context.getString(R.string.lan_address_label)
                setTextColor(mutedColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(10), 0, 0)
            },
        )

        card.addView(
            TextView(context).apply {
                text = url ?: context.getString(R.string.lan_status_no_ip, status.port, pin)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            },
        )

        card.addView(
            TextView(context).apply {
                text = context.getString(R.string.lan_pin_label)
                setTextColor(mutedColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(10), 0, 0)
            },
        )

        val spacedPin = if (pin.length == 6) "${pin.substring(0, 3)} ${pin.substring(3)}" else pin
        card.addView(
            TextView(context).apply {
                text = spacedPin
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setTextIsSelectable(true)
            },
        )

        card.addView(
            TextView(context).apply {
                text = context.getString(R.string.lan_security_note)
                setTextColor(mutedColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(10), 0, 0)
            },
        )

        return card
    }
}
