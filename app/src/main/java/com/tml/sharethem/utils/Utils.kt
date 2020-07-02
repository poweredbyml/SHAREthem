package com.tml.sharethem.utils

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import com.tml.sharethem.sender.SHAREthemService
import org.json.JSONArray
import org.json.JSONException
import java.util.*
import kotlin.math.ln
import kotlin.math.pow

/**
 * Created by Sri on 18/12/16.
 */
object Utils {
    const val DEFAULT_PORT_OREO = 52287
    fun isShareServiceRunning(ctx: Context): Boolean {
        val manager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (SHAREthemService::class.qualifiedName == service.service.className) {
                return true
            }
        }
        return false
    }

    private const val ONE_SECOND: Long = 1000
    private const val ONE_MINUTE = ONE_SECOND * 60
    private const val ONE_HOUR = ONE_MINUTE * 60
    private const val ONE_DAY = ONE_HOUR * 24

    /**
     * converts time (in milliseconds) to human-readable format
     * "(w) days, (x) hours, (y) minutes and (z) seconds"
     */
    @JvmStatic
    fun millisToLongDHMS(duration: Long): String {

//        DateUtils.getRelativeTimeSpanString(duration)
        var duration = duration
        val res = StringBuffer()
        var isDay = false
        var isHr = false
        var isMin = false
        var temp: Long = 0
        return if (duration >= ONE_SECOND) {
            temp = duration / ONE_DAY
            if (temp > 0) {
                isDay = true
                duration -= temp * ONE_DAY
                res.append(if (temp >= 10) temp else "0$temp").append("d ")
            }
            temp = duration / ONE_HOUR
            if (temp > 0) {
                isHr = true
                duration -= temp * ONE_HOUR
                res.append(if (temp >= 10) temp else "0$temp").append("h ")
            }
            if (isDay) return res.toString() + if (temp > 0) "" else "00h"
            temp = duration / ONE_MINUTE
            if (temp > 0) {
                isMin = true
                duration -= temp * ONE_MINUTE
                res.append(if (temp >= 10) temp else "0$temp").append("m ")
            }
            if (isHr) return res.toString() + if (temp > 0) "" else "00m"
            temp = duration / ONE_SECOND
            if (temp > 0) {
                res.append(if (temp >= 10) temp else "0$temp").append("s")
            }
            res.toString() + if (temp > 0) "" else "00s"
        } else {
            "0s"
        }
    }

    @JvmStatic
    fun humanReadableByteCount(bytes: Long, si: Boolean): String {
        val unit = if (si) 1024 else 1000
        if (bytes < unit) return "$bytes B"
        val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
        val pre = (if (si) "kMGTPE" else "KMGTPE")[exp - 1].toString() + (if (si) "" else "i")
        return String.format("%.1f %sB", bytes / unit.toDouble().pow(exp.toDouble()), pre)
    }

    val randomColor: Int
        get() {
            val rnd = Random()
            return Color.argb(255, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
        }

    fun toStringArray(jArray: String?): Array<String?>? {
        return if (jArray == null) null
        else try {
            val array = JSONArray(jArray)
            val arr = arrayOfNulls<String>(array.length())
            for (i in arr.indices) {
                arr[i] = array.optString(i)
            }
            arr
        } catch (jse: JSONException) {
            null
        }
    }

    fun getTargetSDKVersion(context: Context): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.applicationInfo.targetSdkVersion
        } catch (nnf: PackageManager.NameNotFoundException) {
            -1
        }
    }

    fun isMobileDataEnabled(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, "mobile_data", 1) == 1
    }

    fun macAddressToByteArray(macString: String): ByteArray {
        val mac = macString.split("""[:\s-]""".toRegex()).toTypedArray()
        val macAddress = ByteArray(6)
        for (i in mac.indices) {
            macAddress[i] = Integer.decode("0x${mac[i]}").toByte()
        }
        return macAddress
    }

    val isOreoOrAbove: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}