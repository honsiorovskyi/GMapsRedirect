package app.gmapsredirect

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

const val LOG_NOTIFICATIONS_CHANNEL_ID = "default"

class Response (val url: String, val status: Int, val redirect: String?, val body: String?)

class URLHandlerActivity : Activity() {
    private val job = SupervisorJob()
    private val ioScope by lazy { CoroutineScope(job + Dispatchers.IO)}

    private fun parseLocation(url : String?) : String? {
        if (url == null) {
            return null
        }

        val xCoordinate = Regex("data=.*!3d(-?\\d{1,3}\\.\\d+)")
        val yCoordinate = Regex("data=.*!4d(-?\\d{1,3}\\.\\d+)")

        val xMatch = xCoordinate.find(url)
        val yMatch = yCoordinate.find(url)

        if (xMatch == null || yMatch == null) {
            return null
        }

        return "geo:${xMatch.groupValues[1]},${yMatch.groupValues[1]}?q=${xMatch.groupValues[1]},${yMatch.groupValues[1]}"
    }

    private fun parseContent(ctx: LogContext, text: String?) : String? {
        if (text.isNullOrBlank()) {
            return null
        }

        val coordinates = Regex("/maps/preview/place.*@(-?\\d{1,3}\\.\\d+),(-?\\d{1,3}\\.\\d+)")
        val match = coordinates.find(text) ?: return null
        val geo = "geo:${match.groupValues[1]},${match.groupValues[2]}?q=${match.groupValues[1]},${match.groupValues[2]}"

        ctx.log("-> $geo")
        return geo
    }

    private fun fetch(url: String) : Response {
        val resp: Response

        with(URL(url).openConnection() as HttpURLConnection) {
            instanceFollowRedirects = false
            addRequestProperty("Cookie", "SOCS=CAESEwgDEgk1NjE2NDA4NTIaAmVuIAEaBgiA9smnBg")
            addRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.60 Safari/537.36")

            resp = Response(url, responseCode, getHeaderField("Location"), inputStream.bufferedReader().readText())
        }

        return resp
    }

    private fun resolveGMaps(ctx: LogContext, url: String?): String? {
        if (url.isNullOrBlank()) {
            return null
        }

        ctx.log("-> $url")

        // try to parse location from URL
        val geo: String? = parseLocation(url)
        if (geo != null) {
            ctx.log("-> $geo")
            return geo
        }

        // if not, fetch it and try again
        val resp = fetch(url)
        return when(resp.status) {
            302 -> resolveGMaps(ctx, resp.redirect)
            in 200..299 -> parseContent(ctx, resp.body)
            else -> {
                ctx.log("error: ${resp.status} @ ${resp.url}")
                null
            }
        }
    }

    private fun openURL(url : String) {
        val i = Intent(Intent.ACTION_VIEW)
        i.data = Uri.parse(url)
        startActivity(i)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            val log = LogNotification(this)

            if (intent == null || intent.data == null) {
                return
            }

            val ctx = LogContext()
            val url = intent.data.toString()
            ioScope.launch {
                val geo = resolveGMaps(ctx, url)
                if (geo == null) {
                    ctx.log("err: unable to resolve geo :(")
                    toast("unable to resolve geo :(")
                } else {
                    openURL(geo)
                }

                ctx.log("-> $url")
                log.logContext(ctx)
            }
        } finally {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
    }


    @Suppress("SameParameterValue")
    private fun toast(text: String?) {
        runOnUiThread(fun() {
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
        })
    }
}

class LogNotification(private val activity: Activity) {
    init {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = activity.getString(R.string.log_notifications_channel_name)
            val descriptionText = activity.getString(R.string.log_notifications_channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(LOG_NOTIFICATIONS_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun logContext(ctx: LogContext) {
        activity.runOnUiThread(fun() {
            val builder = NotificationCompat.Builder(activity, "default")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentText(ctx.toString())
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setSilent(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.toString()))


            with(NotificationManagerCompat.from(activity)) {
                // notificationId is a unique int for each notification that you must define
                notify((Math.random() * 1000).toInt(), builder.build())
            }
        })
    }
}

class LogContext {
    private var lines = Collections.synchronizedList(mutableListOf<String>())
    fun log(text: String?) {
        if (text.isNullOrBlank()) {
            return
        }
        lines.add(text)
    }

    override fun toString(): String {
        return lines.joinToString("\n")
    }
}
