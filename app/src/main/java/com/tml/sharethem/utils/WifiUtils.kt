package com.tml.sharethem.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.UnknownHostException
import java.util.*
import kotlin.experimental.and
import kotlin.experimental.or

/**
 * Created by Sri on 18/12/16.
 */
object WifiUtils {
    const val SENDER_WIFI_NAMING_SALT = "stl"
    private const val TAG = "WifiUtils"
    var isConnectToHotSpotRunning = false

    /**
     *  Max priority of network to be associated.
     */
    private const val MAX_PRIORITY = 999999

    /**
     * Method for Connecting  to WiFi Network (hotspot)
     *
     * @param netSSID       of WiFi Network (hotspot)
     * @param disableOthers
     */
    fun connectToOpenWifi(ctx: Context?, mWifiManager: WifiManager?, netSSID: String, disableOthers: Boolean): Boolean {
        isConnectToHotSpotRunning = true
        val wifiConf = WifiConfiguration()
        if (mWifiManager!!.isWifiEnabled) {
            wifiConf.SSID = "\"$netSSID\""
            wifiConf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            val res = mWifiManager.addNetwork(wifiConf)
            Log.d(TAG, "added network id: $res")
            mWifiManager.disconnect()
            if (disableOthers) {
                enableNetworkAndDisableOthers(ctx, mWifiManager, netSSID)
            } else {
                enableShareThemNetwork(ctx, mWifiManager, netSSID)
            }
            isConnectToHotSpotRunning = false
            return mWifiManager.setWifiEnabled(true)
        }
        isConnectToHotSpotRunning = false
        return false
    }

    fun enableNetworkAndDisableOthers(ctx: Context?, wifiManager: WifiManager?, ssid: String): Boolean {
        var state = false
        if (ActivityCompat.checkSelfPermission(ctx!!, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return false
        }
        val networks = wifiManager!!.configuredNetworks
        for (wifiConfig in networks) {
            if (wifiConfig.SSID == "\"" + ssid + "\"") state = wifiManager.enableNetwork(wifiConfig.networkId, true) else wifiManager.disableNetwork(wifiConfig.networkId)
        }
        Log.d(TAG, "turnOnPreOreoHotspot wifi result: $state")
        wifiManager.reconnect()
        return state
    }

    fun removeSTWifiAndEnableOthers(ctx: Context?, wifiManager: WifiManager?, ssid: String?): Boolean {
        var state = false
        if (ActivityCompat.checkSelfPermission(ctx!!, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return false
        }
        val networks = wifiManager!!.configuredNetworks
        for (wifiConfig in networks) {
            if (wifiConfig.SSID == "\"" + ssid + "\"") {
                wifiManager.removeNetwork(wifiConfig.networkId)
                wifiManager.disableNetwork(wifiConfig.networkId)
            } else  // if targetSdkVersion > 20
            //      If an application's target SDK version is LOLLIPOP or newer, network communication may not use Wi-Fi even if Wi-Fi is connected;
            //      For more info: https://developer.android.com/reference/android/net/wifi/WifiManager.html#enableNetwork(int, boolean)
                state = wifiManager.enableNetwork(wifiConfig.networkId, true)
        }
        wifiManager.saveConfiguration()
        wifiManager.reconnect()
        return state
    }

    fun enableShareThemNetwork(ctx: Context?, wifiManager: WifiManager?, ssid: String): Boolean {
        var state = false
        if (ActivityCompat.checkSelfPermission(ctx!!, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return false
        }
        val list = wifiManager!!.configuredNetworks
        if (list != null && list.size > 0) {
            for (i in list) {
                if (i.SSID != null && i.SSID == "\"" + ssid + "\"") {
                    var newPri = getMaxPriority(ctx, wifiManager) + 1
                    if (newPri >= MAX_PRIORITY) {
                        // We have reached a rare situation.
                        newPri = shiftPriorityAndSave(ctx, wifiManager)
                    }
                    i.priority = newPri
                    wifiManager.updateNetwork(i)
                    wifiManager.saveConfiguration()
                    // if targetSdkVersion > 20
                    //      If an application's target SDK version is LOLLIPOP or newer, network communication may not use Wi-Fi even if Wi-Fi is connected;
                    //      For more info: https://developer.android.com/reference/android/net/wifi/WifiManager.html#enableNetwork(int, boolean)
                    state = wifiManager.enableNetwork(i.networkId, true)
                    wifiManager.reconnect()
                    break
                }
            }
        }
        return state
    }

    /**
     * removes Configured wifi Network By SSID
     *
     * @param ssid of wifi Network
     */
    fun removeWifiNetwork(ctx: Context?, wifiManager: WifiManager?, ssid: String?) {
        if (ActivityCompat.checkSelfPermission(ctx!!, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        val configs = wifiManager!!.configuredNetworks
        if (configs != null) {
            for (config in configs) {
                if (ssid!! in config.SSID) {
                    wifiManager.disableNetwork(config.networkId)
                    wifiManager.removeNetwork(config.networkId)
                    Log.d(TAG, "removed wifi network with ssid: $ssid")
                }
            }
        }
        wifiManager.saveConfiguration()
    }

    private fun getMaxPriority(ctx: Context?, wifiManager: WifiManager?): Int {
        if (ActivityCompat.checkSelfPermission(ctx!!, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return -1
        }
        val configurations = wifiManager!!.configuredNetworks
        return configurations.maxBy { it.priority }?.priority ?: 0

    }

    private fun sortByPriority(configurations: MutableList<WifiConfiguration>) {
        configurations.sortWith(Comparator { object1, object2 -> object1.priority - object2.priority })
    }

    private fun shiftPriorityAndSave(ctx: Context?, wifiManager: WifiManager?): Int {
        if (ActivityCompat.checkSelfPermission(ctx!!, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return -1
        }
        val configurations = wifiManager!!.configuredNetworks
        sortByPriority(configurations)
        val size = configurations.size
        for (i in 0 until size) {
            val config = configurations[i]
            config.priority = i
            wifiManager.updateNetwork(config)
        }
        wifiManager.saveConfiguration()
        return size
    }

    /**
     * Method to Get Network Security Mode
     *
     * @param scanResult
     * @return OPEN PSK EAP OR WEP
     */
    fun getSecurityMode(scanResult: ScanResult): String {
        val cap = scanResult.capabilities
        val modes = arrayOf("WPA", "EAP", "WEP")
        for (i in modes.indices.reversed()) {
            if (cap.contains(modes[i])) {
                return modes[i]
            }
        }
        return "OPEN"
    }

    fun isWifiConnectedToSTAccessPoint(context: Context): Boolean {
        return isConnectedOnWifi(context, true) && isShareThemSSID((context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.ssid)
    }

    fun isAndroidAP(ssid: String): Boolean {
        Log.d(TAG, "is this ssid mathcing to ST hotspot: $ssid")
        val splits = ssid.split("-".toRegex()).toTypedArray()
        if (splits.size != 2) return false
        val names = String(Base64.decode(splits[1], Base64.DEFAULT)).split("\\|".toRegex()).toTypedArray()
        return names.size == 3 && names[1] == SENDER_WIFI_NAMING_SALT
    }

    fun isShareThemSSID(ssid: String?): Boolean {
        if (Utils.isOreoOrAbove) {
            return null != ssid && ssid.contains("AndroidShare")
        }
        Log.d(TAG, "is this ssid mathcing to ST hotspot: $ssid")
        val splits = ssid!!.split("-".toRegex()).toTypedArray()
        return if (splits.size != 2) false else try {
            val names = String(Base64.decode(splits[1], Base64.DEFAULT)).split("\\|".toRegex()).toTypedArray()
            names.size == 3 && names[1] == SENDER_WIFI_NAMING_SALT
        } catch (e: Exception) {
            false
        }
    }

    fun getSenderSocketPortFromSSID(ssid: String): Int {
        val splits = ssid.split("-".toRegex()).toTypedArray()
        if (splits.size != 2) return -1
        val names = String(Base64.decode(splits[1], Base64.DEFAULT)).split("\\|".toRegex()).toTypedArray()
        return if (names.size == 3 && names[1] == SENDER_WIFI_NAMING_SALT) names[2].toInt() else -1
    }

    fun getSenderInfoFromSSID(ssid: String): Array<String>? {
        if (Utils.isOreoOrAbove) {
            return arrayOf(ssid, Utils.DEFAULT_PORT_OREO.toString())
        }
        val splits = ssid.split("-".toRegex()).toTypedArray()
        if (splits.size != 2) return null
        val names = String(Base64.decode(splits[1], Base64.DEFAULT)).split("\\|".toRegex()).toTypedArray()
        return if (names.size == 3 && names[1] == SENDER_WIFI_NAMING_SALT) arrayOf(names[0], names[2]) else null
    }

    fun getThisDeviceIp(context: Context): String {
        val wifiMan = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInf = wifiMan.connectionInfo
        val ipAddress = wifiInf.ipAddress
        return String.format("%d.%d.%d.%d", ipAddress and 0xff, ipAddress shr 8 and 0xff, ipAddress shr 16 and 0xff, ipAddress shr 24 and 0xff)
    }

    /**
     * Check if there is any connectivity to a Wifi network
     *
     * @param context
     * @param includeConnectingStatus
     * @return
     */
    fun isConnectedOnWifi(context: Context, includeConnectingStatus: Boolean): Boolean {
        return if (Build.VERSION.SDK_INT >= 21) {
            var isWifiConnected = false
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networks = connectivityManager.allNetworks
            for (network in networks) {
                val info = connectivityManager.getNetworkInfo(network)
                if (info != null && info.type == ConnectivityManager.TYPE_WIFI && info.isAvailable && (!includeConnectingStatus || info.isConnectedOrConnecting)) {
                    isWifiConnected = true
                    break
                }
            }
            isWifiConnected
        } else {
            val info = getNetworkInfo(context)
            info.isConnectedOrConnecting && info.type == ConnectivityManager.TYPE_WIFI
        }
    }

    /**
     * Get the network info
     *
     * @param context
     * @return
     */
    fun getNetworkInfo(context: Context): NetworkInfo {
        val cm = context
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo!!
    }

    fun getAccessPointIpAddress(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcpInfo = wifiManager.dhcpInfo
        val ipAddress = convert2Bytes(dhcpInfo.serverAddress)
        try {
            val ip = InetAddress.getByAddress(ipAddress).hostAddress
            return ip.replace("/", "")
        } catch (e: UnknownHostException) {
            e.printStackTrace()
        }
        return null
    }

    private fun convert2Bytes(hostAddress: Int): ByteArray {
        return byteArrayOf((0xff and hostAddress).toByte(),
                (0xff and (hostAddress shr 8)).toByte(),
                (0xff and (hostAddress shr 16)).toByte(),
                (0xff and (hostAddress shr 24)).toByte())
    }

    val hostIpAddress: String?
        get() {
            try {
                val enumerationNetworkInterface = NetworkInterface.getNetworkInterfaces()
                while (enumerationNetworkInterface.hasMoreElements()) {
                    val networkInterface = enumerationNetworkInterface.nextElement()
                    val enumerationInetAddress = networkInterface.inetAddresses
                    while (enumerationInetAddress.hasMoreElements()) {
                        val inetAddress = enumerationInetAddress.nextElement()
                        if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                            return inetAddress.hostAddress
                        }
                    }
                }
                return null
            } catch (e: Exception) {
                Log.e("WifiUtils", "exception in fetching inet address: ${e.message}")
                return null
            }
        }

    @JvmStatic
    @Throws(IllegalArgumentException::class)
    fun inetAddressToInt(inetAddr: InetAddress): Int {
        val addr = inetAddr.address
        require(addr.size == 4) { "Not an IPv4 address" }
        return ((addr[3] and (0xff shl 24).toByte()) or
                (addr[2] and (0xff shl 16).toByte()) or
                (addr[1] and (0xff shl 8).toByte()) or
                (addr[0] and 0xff.toByte())).toInt()
    }

    fun isOpenWifi(result: ScanResult): Boolean =
            "WEP" !in result.capabilities && "PSK" !in result.capabilities && "EAP" !in result.capabilities

    fun getDeviceName(wifiManager: WifiManager): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Log.d(TAG, "For version >= MM inaccessible mac - falling back to the default device name: ${HotspotControl.DEFAULT_DEVICE_NAME}")
            return HotspotControl.DEFAULT_DEVICE_NAME
        }
        val macString = wifiManager.connectionInfo.macAddress
        if (macString == null) {
            Log.d(TAG, "MAC Address not found - Wi-Fi disabled? Falling back to the default device name: ${HotspotControl.DEFAULT_DEVICE_NAME}")
            return HotspotControl.DEFAULT_DEVICE_NAME
        }
        val macBytes = Utils.macAddressToByteArray(macString)
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                val hardwareAddress = iface.hardwareAddress
                if (hardwareAddress != null && Arrays.equals(macBytes, hardwareAddress)) {
                    return iface.name
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "exception in retrieving device name: ${e.message}")
        }
        Log.w(TAG, "None found - falling back to the default device name: ${HotspotControl.DEFAULT_DEVICE_NAME}")
        return HotspotControl.DEFAULT_DEVICE_NAME
    }
}