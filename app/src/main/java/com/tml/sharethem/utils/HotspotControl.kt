package com.tml.sharethem.utils

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback
import android.net.wifi.WifiManager.LocalOnlyHotspotReservation
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.annotation.RequiresApi
import com.tml.sharethem.sender.SHAREthemActivity
import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.regex.Pattern

class HotspotControl private constructor(context: Context) {
    /**
     * Saves the existing configuration before creating SHAREthem hotspot.  Same is used to restore the user Hotspot Config when SHAREthem hotspot is disabled
     */
    private var m_original_config_backup: WifiConfiguration? = null

    /**
     * Not necessary to [HotspotControl] functionality but added here to help [SHAREthemActivity] with port number to display connection URL
     */
    var shareServerListeningPort = 0
        private set
    private val wm: WifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val deviceName: String?
    private var mReservation: LocalOnlyHotspotReservation? = null

    companion object {
        private const val TAG = "HotspotControl"
        private var getWifiApConfiguration: Method? = null
        private var getWifiApState: Method? = null
        private var isWifiApEnabled: Method? = null
        private var setWifiApEnabled: Method? = null
        private var setWifiApConfiguration: Method? = null
        private var instance: HotspotControl? = null
        const val DEFAULT_DEVICE_NAME = "Unknown"
        fun getInstance(context: Context): HotspotControl? {
            if (instance == null) {
                instance = HotspotControl(context)
            }
            return instance
        }

        private fun invokeSilently(method: Method?, receiver: Any, vararg args: Any): Any? {
            try {
                return method!!.invoke(receiver, *args)
            } catch (e: IllegalAccessException) {
                Log.e(TAG, "exception in invoking methods: " + e.message)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "exception in invoking methods: " + e.message)
            } catch (e: InvocationTargetException) {
                Log.e(TAG, "exception in invoking methods: " + e.message)
            }
            return null
        }

        val isSupported: Boolean
            get() = Utils.isOreoOrAbove || getWifiApState != null && isWifiApEnabled != null && setWifiApEnabled != null && getWifiApConfiguration != null

        init {
            for (method in WifiManager::class.java.declaredMethods) {
                when (method.name) {
                    "getWifiApConfiguration" -> getWifiApConfiguration = method
                    "getWifiApState" -> getWifiApState = method
                    "isWifiApEnabled" -> isWifiApEnabled = method
                    "setWifiApEnabled" -> setWifiApEnabled = method
                    "setWifiApConfiguration" -> setWifiApConfiguration = method
                }
            }
        }
    }

    val isEnabled: Boolean
        get() {
            if (Utils.isOreoOrAbove) {
                return null != mReservation && mReservation!!.wifiConfiguration != null
            }
            val result = invokeSilently(isWifiApEnabled, wm) ?: return false
            return result as Boolean
        }

    val configuration: WifiConfiguration?
        get() {
            if (Utils.isOreoOrAbove) {
                return mReservation!!.wifiConfiguration
            }
            val result = invokeSilently(getWifiApConfiguration, wm) ?: return null
            return result as WifiConfiguration
        }

    private fun setHotspotEnabled(config: WifiConfiguration?, enabled: Boolean): Boolean {
        val result = invokeSilently(setWifiApEnabled, wm, config!!, enabled) ?: return false
        return result as Boolean
    }

    private fun setHotspotConfig(config: WifiConfiguration): Boolean {
        val result = invokeSilently(setWifiApConfiguration, wm, config) ?: return false
        return result as Boolean
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    fun turnOnOreoHotspot(port: Int) {
        shareServerListeningPort = port
        wm.startLocalOnlyHotspot(object : LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: LocalOnlyHotspotReservation) {
                super.onStarted(reservation)
                Log.d(TAG, "Wifi Hotspot is on now")
                mReservation = reservation
            }

            override fun onStopped() {
                super.onStopped()
                Log.d(TAG, "onStopped: ")
                mReservation = null
            }

            override fun onFailed(reason: Int) {
                super.onFailed(reason)
                Log.d(TAG, "onFailed: ")
                mReservation = null
            }
        }, Handler())
    }

    /**
     * Creates a new OPEN [WifiConfiguration] and invokes [WifiManager]'s method via `Reflection` to enable Hotspot
     *
     * @param name Open HotSpot SSID
     * @param port Port number assigned to [com.tml.sharethem.sender.SHAREthemServer], not used anywhere in [HotspotControl] but helps [SHAREthemActivity] to display port info
     * @return true if [WifiManager]'s method is successfully called
     */
    fun turnOnPreOreoHotspot(name: String, port: Int): Boolean {
        wm.isWifiEnabled = false
        shareServerListeningPort = port
        m_original_config_backup = configuration

        //Create new Open Wifi Configuration
        val wifiConf = WifiConfiguration()
        wifiConf.SSID = "\"$name\""
        wifiConf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
        wm.addNetwork(wifiConf)

        //save it
        wm.saveConfiguration()
        return setHotspotEnabled(wifiConf, true)
    }

    //    /**
    //     * Calls {@link WifiManager}'s method via reflection to enabled Hotspot with existing configuration
    //     *
    //     * @return
    //     */
    //    public boolean enable() {
    //        wm.setWifiEnabled(false);
    //        return setHotspotEnabled(getConfiguration(), true);
    //    }
    fun disable(): Boolean {
        if (Utils.isOreoOrAbove) {
            if (mReservation != null) {
                mReservation!!.close()
                mReservation = null
                return true
            }
            return false
        } else {
            //restore original hotspot config if available
            if (null != m_original_config_backup) setHotspotConfig(m_original_config_backup!!)
            shareServerListeningPort = 0
            return setHotspotEnabled(m_original_config_backup, false)
        }
    }

    val hostIpAddress: String?
        get() {
            if (!isEnabled) {
                return null
            }
            val inet4 = getInetAddress(Inet4Address::class.java)
            if (null != inet4) return inet4.toString()
            val inet6 = getInetAddress(Inet6Address::class.java)
            return inet6?.toString()
        }

    private fun <T : InetAddress?> getInetAddress(addressType: Class<T>): T? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (iface.name != deviceName) {
                    continue
                }
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addressType.isInstance(addr)) {
                        return addressType.cast(addr)
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "exception in fetching inet address: " + e.message)
        }
        return null
    }

    data class WifiScanResult(var ip: String)

    fun getConnectedWifiClients(timeout: Int,
                                listener: WifiClientConnectionListener): List<WifiScanResult>? {
        val wifiScanResults = wifiClients
        if (wifiScanResults == null) {
            listener.onWifiClientsScanComplete()
            return null
        }
        val latch = CountDownLatch(wifiScanResults.size)
        val es = Executors.newCachedThreadPool()
        for (c in wifiScanResults) {
            es.submit {
                try {
                    val ip = InetAddress.getByName(c.ip)
                    if (ip.isReachable(timeout)) {
                        listener.onClientConnectionAlive(c)
                        Thread.sleep(1000)
                    } else listener.onClientConnectionDead(c)
                } catch (e: IOException) {
                    Log.e(TAG, "io exception while trying to reach client, ip: " + c.ip)
                } catch (ire: InterruptedException) {
                    Log.e(TAG, "InterruptedException: " + ire.message)
                }
                latch.countDown()
            }
        }
        Thread {
            try {
                latch.await()
            } catch (e: InterruptedException) {
                Log.e(TAG, "listing clients countdown interrupted", e)
            }
            listener.onWifiClientsScanComplete()
        }.start()
        return wifiScanResults
    }

    // Basic sanity checks
    val wifiClients: List<WifiScanResult>?
        get() {
            if (!isEnabled) {
                return null
            }
            val result = mutableListOf<WifiScanResult>()

            // Basic sanity checks
            val macPattern = Pattern.compile("..:..:..:..:..:..")

            try {
                BufferedReader(FileReader("/proc/net/arp")).use { bufferedReader ->
                    var line: String
                    while (bufferedReader.readLine().also { line = it } != null) {
                        val parts = line.split(" +".toRegex()).toTypedArray()
                        if (parts.size < 4 || !macPattern.matcher(parts[3]).find()) {
                            continue
                        }
                        result.add(WifiScanResult(parts[0]))
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "exception in getting clients", e)
            }
            return result
        }

    interface WifiClientConnectionListener {
        fun onClientConnectionAlive(c: WifiScanResult)
        fun onClientConnectionDead(c: WifiScanResult)
        fun onWifiClientsScanComplete()
    }

    init {
        deviceName = WifiUtils.getDeviceName(wm)
    }
}