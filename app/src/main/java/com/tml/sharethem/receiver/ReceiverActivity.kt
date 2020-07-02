/*
 * Copyright 2017 Srihari Yachamaneni
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tml.sharethem.receiver

import android.Manifest
import android.annotation.TargetApi
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.Uri
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.provider.Settings
import android.text.Html
import android.text.TextUtils
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import com.tml.sharethem.R
import com.tml.sharethem.databinding.ActivityReceiverBinding
import com.tml.sharethem.utils.Utils
import com.tml.sharethem.utils.WifiUtils
import java.lang.ref.WeakReference
import java.util.*

/**
 * Controls
 */
class ReceiverActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReceiverBinding
    var m_p2p_connection_status: TextView? = null
    var m_receiver_control: SwitchCompat? = null
    var m_goto_wifi_settings: TextView? = null
    var m_sender_files_header: TextView? = null
    private var wifiManager: WifiManager? = null
    private var preferences: SharedPreferences? = null
    private var m_receiver_control_switch_listener: CompoundButton.OnCheckedChangeListener? = null
    private var mWifiScanReceiver: WifiScanner? = null
    private var mNwChangesReceiver: WifiScanner? = null
    private var m_wifiScanHandler: WifiTasksHandler? = null
    private var mConnectedSSID: String? = null
    private var m_areOtherNWsDisabled = false
    var m_toolbar: Toolbar? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)
        if (Utils.isShareServiceRunning(application)) {
            Toast.makeText(this, "Share mode is active, stop Share service to proceed with Receiving files", Toast.LENGTH_SHORT).show()
            return
        }

        binding = ActivityReceiverBinding.inflate(layoutInflater)

        m_p2p_connection_status = binding.p2pReceiverWifiInfo
        m_goto_wifi_settings = binding.p2pReceiverWifiSwitch
        m_sender_files_header = binding.p2pSenderFilesHeader
        m_receiver_control = binding.p2pReceiverApSwitch
        binding.p2pReceiverWifiSwitch.setOnClickListener { gotoWifiSettings() }
        m_toolbar = binding.toolbar
        binding.toolbar.title = getString(R.string.send_title)
        setSupportActionBar(m_toolbar)
        supportActionBar!!.setDisplayShowTitleEnabled(true)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        m_wifiScanHandler = WifiTasksHandler(this)
        m_receiver_control_switch_listener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!startSenderScan()) changeReceiverControlCheckedStatus(false)
            } else {
                changeReceiverControlCheckedStatus(true)
                showOptionsDialogWithListners(getString(R.string.p2p_receiver_close_warning), DialogInterface.OnClickListener { dialog, which ->
                    changeReceiverControlCheckedStatus(false)
                    disableReceiverMode()
                }, DialogInterface.OnClickListener { _, _ -> }, getString(R.string.Action_Ok), getString(R.string.Action_cancel))
            }
        }
        binding.p2pReceiverApSwitch.setOnCheckedChangeListener(m_receiver_control_switch_listener)
        if (!wifiManager!!.isWifiEnabled) wifiManager!!.isWifiEnabled = true
        //start search by default
        binding.p2pReceiverApSwitch.isChecked = true
    }

    @TargetApi(23)
    private fun checkLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                    PERMISSIONS_REQUEST_CODE_ACCESS_COARSE_LOCATION
            )
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSIONS_REQUEST_CODE_ACCESS_COARSE_LOCATION -> if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (checkLocationAccess()) startSenderScan()
            } else {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    showOptionsDialogWithListners(getString(R.string.p2p_receiver_gps_permission_warning), DialogInterface.OnClickListener { dialog, which -> checkLocationPermission() }, DialogInterface.OnClickListener { dialog, which ->
                        dialog.dismiss()
                        finish()
                    }, "Re-Try", "Yes, Im Sure")
                } else {
                    showOptionsDialogWithListners(getString(R.string.p2p_receiver_gps_no_permission_prompt), DialogInterface.OnClickListener { dialog, which ->
                        try {
                            val intent = Intent()
                            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            val uri = Uri.fromParts("package", packageName, null)
                            intent.data = uri
                            startActivity(intent)
                        } catch (anf: ActivityNotFoundException) {
                            Toast.makeText(applicationContext, "Settings activity not found", Toast.LENGTH_SHORT).show()
                        }
                    }, DialogInterface.OnClickListener { dialog, which -> finish() }, getString(R.string.label_settings), getString(R.string.Action_cancel))
                }
            }
        }
    }

    public override fun onResume() {
        super.onResume()
        val isConnectedToShareThemAp = WifiUtils.isWifiConnectedToSTAccessPoint(applicationContext)
        if (isConnectedToShareThemAp) {
            unRegisterForScanResults()
            if (!m_receiver_control!!.isChecked) changeReceiverControlCheckedStatus(true)
            val ssid = wifiManager!!.connectionInfo.ssid
            Log.d(TAG, "wifi is connected/connecting to ShareThem ap, ssid: $ssid")
            mConnectedSSID = ssid
            addSenderFilesListingFragment(WifiUtils.getAccessPointIpAddress(this), ssid)
        } else if (m_receiver_control!!.isChecked) {
            Log.d(TAG, "wifi isn't connected to ShareThem ap, initiating sender search..")
            resetSenderSearch()
        }
    }

    override fun onPause() {
        super.onPause()
        unRegisterForScanResults()
    }

    override fun onDestroy() {
        super.onDestroy()
        unRegisterForNwChanges()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Entry point to start receiver mode. Makes calls to register necessary broadcast receivers to start scanning for SHAREthem Wifi Hotspot.
     *
     * @return
     */
    private fun startSenderScan(): Boolean {
        if (Utils.getTargetSDKVersion(applicationContext) >= 23
                &&  // if targetSdkVersion >= 23
                //      Get Wifi Scan results method needs GPS to be ON and COARSE location permission
                !checkLocationPermission()) {
            return false
        }
        changeReceiverControlCheckedStatus(true)
        registerAndScanForWifiResults()
        registerForNwChanges()
        return true
    }

    /**
     * Disables and removes SHAREthem wifi configuration from Wifi Settings. Also does cleanup work to remove handlers, un-register receivers etc..
     */
    private fun disableReceiverMode() {
        if (!TextUtils.isEmpty(mConnectedSSID)) {
            if (m_areOtherNWsDisabled) WifiUtils.removeSTWifiAndEnableOthers(this, wifiManager, mConnectedSSID) else WifiUtils.removeWifiNetwork(this, wifiManager, mConnectedSSID)
        }
        m_wifiScanHandler!!.removeMessages(WifiTasksHandler.WAIT_FOR_CONNECT_ACTION_TIMEOUT)
        m_wifiScanHandler!!.removeMessages(WifiTasksHandler.WAIT_FOR_RECONNECT_ACTION_TIMEOUT)
        unRegisterForScanResults()
        unRegisterForNwChanges()
        removeSenderFilesListingFragmentIfExists()
    }

    fun showOptionsDialogWithListners(message: String?,
                                      pListner: DialogInterface.OnClickListener?,
                                      nListener: DialogInterface.OnClickListener?,
                                      pButtonText: String?,
                                      nButtonText: String?) {
        val builder = AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle).apply {
            setCancelable(false)
            setMessage(Html.fromHtml(message))
            setPositiveButton(pButtonText, pListner)
            setNegativeButton(nButtonText, nListener
                    ?: DialogInterface.OnClickListener { dialog, _ ->
                        dialog.cancel()
                    })
        }
        builder.show()
    }

    /**
     * Changes checked status without invoking listener. Removes @[android.widget.CompoundButton.OnCheckedChangeListener] on @[SwitchCompat] button before changing checked status
     *
     * @param checked if `true`, sets @[SwitchCompat] checked.
     */
    private fun changeReceiverControlCheckedStatus(checked: Boolean) {
        m_receiver_control!!.setOnCheckedChangeListener(null)
        m_receiver_control!!.isChecked = checked
        m_receiver_control!!.setOnCheckedChangeListener(m_receiver_control_switch_listener)
    }

    /**
     * Registers for [WifiManager.SCAN_RESULTS_AVAILABLE_ACTION] action and also calls a method to start Wifi Scan action.
     */
    private fun registerAndScanForWifiResults() {
        if (null == mWifiScanReceiver) mWifiScanReceiver = WifiScanner()
        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(mWifiScanReceiver, intentFilter)
        m_p2p_connection_status!!.text = getString(R.string.p2p_receiver_scanning_hint)
        startWifiScan()
    }

    /**
     * Registers for [WifiManager.NETWORK_STATE_CHANGED_ACTION] action
     */
    private fun registerForNwChanges() {
        if (null == mNwChangesReceiver) mNwChangesReceiver = WifiScanner()
        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        registerReceiver(mNwChangesReceiver, intentFilter)
    }

    private fun unRegisterForScanResults() {
        stopWifiScan()
        try {
            if (null != mWifiScanReceiver) unregisterReceiver(mWifiScanReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "exception while un-registering wifi changes.." + e.message)
        }
    }

    private fun unRegisterForNwChanges() {
        try {
            if (null != mNwChangesReceiver) unregisterReceiver(mNwChangesReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "exception while un-registering NW changes.." + e.message)
        }
    }

    private fun startWifiScan() {
        m_wifiScanHandler!!.removeMessages(WifiTasksHandler.SCAN_FOR_WIFI_RESULTS)
        m_wifiScanHandler!!.sendMessageDelayed(m_wifiScanHandler!!.obtainMessage(WifiTasksHandler.SCAN_FOR_WIFI_RESULTS), 500)
    }

    private fun stopWifiScan() {
        if (null != m_wifiScanHandler) m_wifiScanHandler!!.removeMessages(WifiTasksHandler.SCAN_FOR_WIFI_RESULTS)
    }

    private fun checkLocationAccess(): Boolean {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.e(TAG, "GPS not enabled..")
            buildAlertMessageNoGps()
            return false
        }
        return true
    }

    private fun buildAlertMessageNoGps() {
        val builder = AlertDialog.Builder(this)
        builder.setMessage("Your GPS seems to be disabled, Please enabled it to proceed with p2p movie sharing")
                .setCancelable(false)
                .setPositiveButton("Yes") { dialog, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    dialog.dismiss()
                }
                .setNegativeButton("No") { dialog, _ ->
                    dialog.cancel()
                    finish()
                }
        val alert = builder.create()
        alert.show()
    }

    fun resetSenderSearch() {
        removeSenderFilesListingFragmentIfExists()
        startSenderScan()
    }

    private fun connectToWifi(ssid: String) {
        val info = wifiManager!!.connectionInfo
        unRegisterForScanResults()
        if (Utils.isOreoOrAbove) {
            promptToConnectManually(ssid)
            return
        }
        val resetWifiScan: Boolean
        if (info.ssid == ssid) {
            Log.d(TAG, "Already connected to ShareThem, add sender Files listing fragment")
            resetWifiScan = false
            addSenderFilesListingFragment(WifiUtils.getAccessPointIpAddress(applicationContext), ssid)
        } else {
            m_p2p_connection_status!!.text = getString(R.string.p2p_receiver_connecting_hint, ssid)
            resetWifiScan = !WifiUtils.connectToOpenWifi(this, wifiManager, ssid, false)
            Log.e(TAG, "connection attempt to ShareThem wifi is " + if (!resetWifiScan) "success!!!" else "FAILED..!!!")
        }
        //if wap isnt successful, start wifi scan
        if (resetWifiScan) {
            Toast.makeText(this, getString(R.string.p2p_receiver_error_in_connecting, ssid), Toast.LENGTH_SHORT).show()
            m_p2p_connection_status!!.text = getString(R.string.p2p_receiver_scanning_hint)
            startSenderScan()
        } else {
            val message = m_wifiScanHandler!!.obtainMessage(WifiTasksHandler.WAIT_FOR_CONNECT_ACTION_TIMEOUT)
            message.obj = ssid
            m_wifiScanHandler!!.sendMessageDelayed(message, 7000)
        }
    }

    private fun promptToConnectManually(ssid: String) {
        showOptionsDialogWithListners(getString(R.string.p2p_receiver_oreo_msg, ssid), DialogInterface.OnClickListener { dialog, which ->
            try {
                val intent = Intent(WifiManager.ACTION_PICK_WIFI_NETWORK)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this@ReceiverActivity, "Wifi listings not found on your device!! :(", Toast.LENGTH_SHORT).show()
            }
        }, DialogInterface.OnClickListener { dialog, which ->
            Toast.makeText(applicationContext, getString(R.string.p2p_receiver_error_in_connecting, ssid), Toast.LENGTH_SHORT).show()
            m_p2p_connection_status!!.text = getString(R.string.p2p_receiver_scanning_hint)
            startSenderScan()
        }, "Settings", "Cancel")
    }

    private inner class WifiScanner : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION && !WifiUtils.isWifiConnectedToSTAccessPoint(applicationContext)) {
                val mScanResults = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).scanResults
                var foundSTWifi = false
                for (result in mScanResults) if (WifiUtils.isShareThemSSID(result.SSID) && (Utils.isOreoOrAbove || WifiUtils.isOpenWifi(result))) {
                    Log.d(TAG, "signal level: " + result.level)
                    connectToWifi(result.SSID)
                    foundSTWifi = true
                    break
                }
                if (!foundSTWifi) {
                    Log.e(TAG, "no ST wifi found, starting scan again!!")
                    startWifiScan()
                }
            } else if (intent.action == WifiManager.NETWORK_STATE_CHANGED_ACTION) {
                val netInfo = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                if (ConnectivityManager.TYPE_WIFI == netInfo!!.type) {
                    val info = wifiManager!!.connectionInfo
                    val supState = info.supplicantState
                    Log.d(TAG, "NETWORK_STATE_CHANGED_ACTION, ssid: " + info.ssid + ", ap ip: " + WifiUtils.getAccessPointIpAddress(applicationContext) + ", sup state: " + supState)
                    if (null == preferences) preferences = getSharedPreferences(
                            packageName, Context.MODE_PRIVATE)
                    if (WifiUtils.isShareThemSSID(info.ssid)) {
                        if (System.currentTimeMillis() - preferences!!.getLong(LASTCONNECTEDTIME, 0) >= SYNCTIME && supState == SupplicantState.COMPLETED) {
                            mConnectedSSID = info.ssid
                            m_wifiScanHandler!!.removeMessages(WifiTasksHandler.WAIT_FOR_CONNECT_ACTION_TIMEOUT)
                            m_wifiScanHandler!!.removeMessages(WifiTasksHandler.WAIT_FOR_RECONNECT_ACTION_TIMEOUT)
                            val ip = WifiUtils.getAccessPointIpAddress(applicationContext)
                            preferences!!.edit().putLong(LASTCONNECTEDTIME, System.currentTimeMillis()).apply()
                            Log.d(TAG, "client connected to ShareThem hot spot. AP ip address: $ip")
                            addSenderFilesListingFragment(ip, info.ssid)
                        }
                        //                        else if (!netInfo.isConnectedOrConnecting() && System.currentTimeMillis() - Prefs.getInstance().loadLong(LASTDISCONNECTEDTIME, 0) >= SYNCTIME) {
//                            Prefs.getInstance().saveLong(LASTDISCONNECTEDTIME, System.currentTimeMillis());
//                            if (LogUtil.LOG)
//                                LogUtil.e(TAG, "AP disconnedted..");
//                            Toast.makeText(context, "Sender Wifi Hotspot disconnected. Retrying to connect..", Toast.LENGTH_SHORT).show();
//                            resetSenderSearch();
//                        }
                    }
                }
            }
        }
    }

    private fun addSenderFilesListingFragment(ip: String?, ssid: String) {
        val senderInfo = setConnectedUi(ssid)
        if (null == senderInfo) {
            Log.e(TAG, "Cant retrieve port and name info from SSID")
            return
        }
        val fragment = supportFragmentManager.findFragmentByTag(TAG_SENDER_FILES_LISTING)
        if (null != fragment) {
            if (ip == (fragment as FilesListingFragment).senderIp && ssid == fragment.senderSSID) {
                Log.e(TAG, "files fragment exists already!!")
                return
            } else {
                Log.e(TAG, "fragment with diff tag is found, removing to add a fresh one..")
                removeSenderFilesListingFragmentIfExists()
            }
        }
        Log.d(TAG, "adding files fragment with ip: $ip")
        val ft = supportFragmentManager.beginTransaction()
        val filesListingFragment: FilesListingFragment = FilesListingFragment.getInstance(ip, ssid, senderInfo[0], senderInfo[1])
        //        ft.setCustomAnimations(R.anim.push_left_in, R.anim.push_left_out,
//                R.anim.push_right_in, R.anim.push_right_out);
        ft.add(R.id.sender_files_list_fragment_holder, filesListingFragment, TAG_SENDER_FILES_LISTING).commitAllowingStateLoss()
    }

    private fun setConnectedUi(ssid: String): Array<String>? {
        val senderInfo = WifiUtils.getSenderInfoFromSSID(ssid)
        if (null == senderInfo || senderInfo.size != 2) return null
        val ip = WifiUtils.getThisDeviceIp(applicationContext)
        m_p2p_connection_status!!.text = getString(R.string.p2p_receiver_connected_hint, ssid, ip)
        m_goto_wifi_settings!!.visibility = View.VISIBLE
        if (!m_receiver_control!!.isChecked) changeReceiverControlCheckedStatus(true)
        m_sender_files_header!!.visibility = View.VISIBLE
        return senderInfo
    }

    protected fun gotoWifiSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (anf: ActivityNotFoundException) {
            Toast.makeText(this, "No Wifi listings feature found on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeSenderFilesListingFragmentIfExists() {
        m_p2p_connection_status!!.text = getString(if (m_receiver_control!!.isChecked) R.string.p2p_receiver_scanning_hint else R.string.p2p_receiver_hint_text)
        m_goto_wifi_settings!!.visibility = View.GONE
        m_sender_files_header!!.visibility = View.GONE
        val fragment = supportFragmentManager.findFragmentByTag(TAG_SENDER_FILES_LISTING)
        if (null != fragment) {
            supportFragmentManager
                    .beginTransaction()
                    .remove(fragment)
                    .commitAllowingStateLoss()
        }
    }

    internal class WifiTasksHandler(activity: ReceiverActivity) : Handler() {
        private val mActivity: WeakReference<ReceiverActivity> = WeakReference(activity)
        override fun handleMessage(msg: Message) {
            val activity = mActivity.get() ?: return
            when (msg.what) {
                SCAN_FOR_WIFI_RESULTS -> if (null != activity.wifiManager) activity.wifiManager!!.startScan()
                WAIT_FOR_CONNECT_ACTION_TIMEOUT -> {
                    Log.e(TAG, "cant connect to sender's hotspot by increasing priority, try the dirty way..")
                    activity.m_areOtherNWsDisabled = WifiUtils.connectToOpenWifi(activity, activity.wifiManager, msg.obj as String, true)
                    val m = obtainMessage(WAIT_FOR_RECONNECT_ACTION_TIMEOUT)
                    m.obj = msg.obj
                    sendMessageDelayed(m, 6000)
                }
                WAIT_FOR_RECONNECT_ACTION_TIMEOUT -> {
                    if (WifiUtils.isWifiConnectedToSTAccessPoint(activity) || activity.isFinishing) return
                    Log.e(TAG, "Even the dirty hack couldn't do it, prompt user to chose it fromWIFI settings..")
                    activity.disableReceiverMode()
                    activity.showOptionsDialogWithListners(
                            activity.getString(R.string.p2p_receiver_connect_timeout_error, msg.obj),
                            DialogInterface.OnClickListener { dialog, _ ->
                                dialog.dismiss()
                                activity.gotoWifiSettings()
                            },
                            DialogInterface.OnClickListener { _, _ -> activity.finish() },
                            "Settings",
                            "Cancel"
                    )
                }
            }
        }

        companion object {
            const val SCAN_FOR_WIFI_RESULTS = 100
            const val WAIT_FOR_CONNECT_ACTION_TIMEOUT = 101
            const val WAIT_FOR_RECONNECT_ACTION_TIMEOUT = 102
        }

    }

    companion object {
        const val TAG = "ReceiverActivity"
        private const val PERMISSIONS_REQUEST_CODE_ACCESS_COARSE_LOCATION = 100
        private const val TAG_SENDER_FILES_LISTING = "sender_files_listing"
        private const val SYNCTIME = 800L
        private const val LASTCONNECTEDTIME = "LASTCONNECTEDTIME"
        private const val LASTDISCONNECTEDTIME = "LASTDISCONNECTEDTIME"
    }
}