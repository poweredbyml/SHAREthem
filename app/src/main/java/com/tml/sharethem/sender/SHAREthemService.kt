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
package com.tml.sharethem.sender

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.tml.sharethem.R
import com.tml.sharethem.utils.HotspotControl
import com.tml.sharethem.utils.Utils
import com.tml.sharethem.utils.WifiUtils
import java.lang.ref.WeakReference

/**
 * Manages Hotspot configuration using an instance of [HotspotControl] and also deals lifecycle of [SHAREthemServer]
 * Created by Sri on 18/12/16.
 */
class SHAREthemService : Service() {
    private var wifiManager: WifiManager? = null
    private var hotspotControl: HotspotControl? = null
    private var m_fileServer: SHAREthemServer? = null
    private var m_notificationStopActionReceiver: BroadcastReceiver? = null
    private var hotspotCheckHandler: HotspotChecker? = null

    internal object ShareIntents {
        const val TYPE = "type"
        const val SHARE_SERVER_UPDATES_INTENT_ACTION = "share_server_updates_intent_action"
        const val SHARE_SERVER_UPDATE_TEXT = "share_server_update_text"
        const val SHARE_SERVER_UPDATE_FILE_NAME = "share_server_file_name"
        const val SHARE_CLIENT_IP = "share_client_ip"
        const val SHARE_TRANSFER_PROGRESS = "share_transfer_progress"

        internal object Types {
            const val FILE_TRANSFER_STATUS = 1000
            const val AP_ENABLED_ACKNOWLEDGEMENT = 1001
            const val AP_DISABLED_ACKNOWLEDGEMENT = 1002
        }
    }

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        hotspotControl = HotspotControl.getInstance(applicationContext)
        m_notificationStopActionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (WIFI_AP_ACTION_STOP == intent.action) disableHotspotAndStop()
            }
        }
        registerReceiver(m_notificationStopActionReceiver, IntentFilter(WIFI_AP_ACTION_STOP))
        //Start a foreground with message saying 'Initiating Hotspot'. Message is later updated using SHARE_SERVICE_NOTIFICATION_ID
        startForeground(SHARE_SERVICE_NOTIFICATION_ID, getShareThemNotification(getString(R.string.p2p_sender_service_init_notification_header), false))
        hotspotCheckHandler = HotspotChecker(this)
    }

    protected val stopAction: NotificationCompat.Action
        get() {
            val intent = Intent(WIFI_AP_ACTION_STOP)
            val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingIntent).build()
        }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        when (intent.action) {
            WIFI_AP_ACTION_START -> if (!hotspotControl!!.isEnabled) {
                startFileHostingServer(intent.getStringArrayExtra(EXTRA_FILE_PATHS)!!, intent.getIntExtra(EXTRA_PORT, 0), intent.getStringExtra(EXTRA_SENDER_NAME)!!)
            }
            WIFI_AP_ACTION_STOP -> disableHotspotAndStop()
            WIFI_AP_ACTION_START_CHECK -> if (null != hotspotControl && hotspotControl!!.isEnabled) {
                //starts a handler in loop to check Hotspot check. Service kills itself when Hotspot is no more alive
                if (null == hotspotCheckHandler) {
                    hotspotCheckHandler = HotspotChecker(this)
                } else hotspotCheckHandler!!.removeMessages(AP_ALIVE_CHECK)
                hotspotCheckHandler!!.sendEmptyMessageDelayed(100, 3000)
            }
        }
        return START_NOT_STICKY
    }

    private fun disableHotspotAndStop() {
        Log.d(TAG, "p2p service stop action received..")
        if (null != hotspotControl) hotspotControl!!.disable()
        wifiManager!!.isWifiEnabled = true
        if (null != hotspotCheckHandler) hotspotCheckHandler!!.removeCallbacksAndMessages(null)
        stopFileTasks()
        stopForeground(true)
        sendAcknowledgementBroadcast(ShareIntents.Types.AP_DISABLED_ACKNOWLEDGEMENT)
        stopSelf()
    }

    /**
     * Creates a Notification for [Service.startForeground] method.
     * Adds text passed in `text` param as content and title
     *
     * @param text          message and title
     * @param addStopAction if true STOP action is added to stop [SHAREthemService]
     * @return Notification
     */
    private fun getShareThemNotification(text: String, addStopAction: Boolean): Notification {

        val channelId =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    createNotificationChannel("my_service", "My Background Service")
                } else {
                    // If earlier version channel ID is not used
                    // https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
                    ""
                }

        val notificationIntent = Intent(applicationContext, SHAREthemActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, channelId).apply {
            setContentIntent(pendingIntent)
            setSmallIcon(R.mipmap.ic_launcher)
            setWhen(System.currentTimeMillis())
            setContentTitle(getString(R.string.app_name))
            setTicker(text)
            setContentText(text)
            if (addStopAction) addAction(stopAction)
        }
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(channelId: String, channelName: String): String{
        val chan = NotificationChannel(channelId,
                channelName, NotificationManager.IMPORTANCE_NONE)
        chan.lightColor = Color.BLUE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    /**
     * Starts [SHAREthemServer] and then enables Hotspot with assigned port being part of encoded SSID.
     *
     * @param filePathsTobeServed
     */
    private fun startFileHostingServer(filePathsTobeServed: Array<String>, port: Int, sender_name: String) {
        m_fileServer = SHAREthemServer(applicationContext, object : FileTransferStatusListener {
            @Synchronized
            override fun onBytesTransferProgress(ip: String, fileName: String, totalSize: Long, speed: String, currentSize: Long, percentageUploaded: Int) {
                sendTransferStatusBroadcast(ip, percentageUploaded, "Transferring $fileName file($percentageUploaded%)\nSpeed: $speed", fileName)
            }

            override fun onBytesTransferCompleted(ip: String, fileName: String) {
                sendTransferStatusBroadcast(ip, 100, "$fileName file transfer completed", fileName)
            }

            @Synchronized
            override fun onBytesTransferStarted(ip: String, fileName: String) {
                sendTransferStatusBroadcast(ip, 0, "Transferring $fileName file", fileName)
            }

            override fun onBytesTransferCancelled(ip: String, error: String, fileName: String) {
                Log.e(TAG, " transfer cancelled for ip: $ip, file name: $fileName")
                sendTransferStatusBroadcast(ip, 0, "Error in file transfer: $error", fileName)
            }
        }, filePathsTobeServed, port)
        //        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                try {
//                    m_fileServer.start();
//
//                } catch (BindException e) {
//                    e.printStackTrace();
//                    Log.e(TAG, "exception in starting file server: " + e.getMessage());
//                } catch (Exception e) {
//                    Log.e(TAG, "exception in starting file server: " + e.getMessage());
//                    e.printStackTrace();
//                }
//            }
//        }).start();
        try {
            m_fileServer!!.start()
            Log.d(TAG, "**** Server started success****port: ${m_fileServer!!.listeningPort}, ${m_fileServer!!.hostAddress}")
            if (Utils.isOreoOrAbove) {
                hotspotControl!!.turnOnOreoHotspot(m_fileServer!!.listeningPort)
            } else {
                /*
             * We need create a Open Hotspot with an SSID which can be intercepted by Receiver.
             * Here is the combination logic followed to create SSID for open Hotspot and same is followed by Receiver while decoding SSID, Sender HostName & port to connect
             * Reason for doing this is to keep SSID unique, constant(unless port is assigned by system) and interpretable by Receiver
             * {last 4 digits of android id} + {-} + Base64 of [{sender name} + {|} + SENDER_WIFI_NAMING_SALT + {|} + {port}]
             */
                var androidId = Settings.Secure.ANDROID_ID
                androidId = androidId.replace("[^A-Za-z0-9]".toRegex(), "")
                val name = (if (androidId.length > 4) androidId.substring(androidId.length - 4) else androidId) + "-" + Base64.encodeToString((if (TextUtils.isEmpty(sender_name)) generateP2PSpuulName() else sender_name + "|" + WifiUtils.SENDER_WIFI_NAMING_SALT + "|" + m_fileServer!!.listeningPort).toByteArray(), Base64.DEFAULT)
                hotspotControl!!.turnOnPreOreoHotspot(name, m_fileServer!!.listeningPort)
                hotspotCheckHandler!!.sendEmptyMessage(AP_START_CHECK)
            }
        } catch (e: Exception) {
            Log.e(TAG, "exception in hotspot init: " + e.message)
            e.printStackTrace()
        }
    }

    private fun stopFileTasks() {
        try {
            if (null != m_fileServer && m_fileServer!!.isAlive) {
                Log.d(TAG, "stopping server..")
                m_fileServer!!.stop()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "exception in stopping file server: " + e.message)
        }
    }

    private fun sendAcknowledgementBroadcast(type: Int) {
        val updateIntent = Intent(ShareIntents.SHARE_SERVER_UPDATES_INTENT_ACTION)
        updateIntent.putExtra(ShareIntents.TYPE, type)
        sendBroadcast(updateIntent)
    }

    private fun sendTransferStatusBroadcast(ip: String, progress: Int, updateText: String, fileName: String) {
        val updateIntent = Intent(ShareIntents.SHARE_SERVER_UPDATES_INTENT_ACTION).apply {
            putExtra(ShareIntents.TYPE, ShareIntents.Types.FILE_TRANSFER_STATUS)
            putExtra(ShareIntents.SHARE_SERVER_UPDATE_TEXT, updateText)
            putExtra(ShareIntents.SHARE_SERVER_UPDATE_FILE_NAME, fileName)
            putExtra(ShareIntents.SHARE_CLIENT_IP, ip)
            putExtra(ShareIntents.SHARE_TRANSFER_PROGRESS, progress)
        }
        sendBroadcast(updateIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (null != hotspotControl && hotspotControl!!.isEnabled) {
            hotspotControl!!.disable()
            wifiManager!!.isWifiEnabled = true
            stopFileTasks()
        }
        if (null != hotspotCheckHandler) hotspotCheckHandler!!.removeCallbacksAndMessages(null)
        if (null != m_notificationStopActionReceiver) unregisterReceiver(m_notificationStopActionReceiver)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    /**
     * Updates notification text saying 'Hotspot Enabled' along with STOP action.
     * Since there isn't an easy way to update Foreground notification, notifying a new notification with same ID as earlier would do the trick.
     * <br></br>Ref: [http://stackoverflow.com/a/20142620/862882](http://stackoverflow.com/a/20142620/862882)
     */
    private fun updateForegroundNotification() {
        val mNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mNotificationManager.notify(SHARE_SERVICE_NOTIFICATION_ID, getShareThemNotification(getString(R.string.p2p_sender_service_notification_header), true))
    }

    private class HotspotChecker internal constructor(senderService: SHAREthemService) : Handler() {
        var service: WeakReference<SHAREthemService> = WeakReference(senderService)
        override fun handleMessage(msg: Message) {
            val senderService = service.get() ?: return
            if (msg.what == AP_ALIVE_CHECK) {
                if (null == senderService.hotspotControl || !senderService.hotspotControl!!.isEnabled) {
                    Log.e(TAG, "hotspot isnt active, close this service")
                    senderService.disableHotspotAndStop()
                } else sendEmptyMessageDelayed(AP_ALIVE_CHECK, 3000)
            } else if (msg.what == AP_START_CHECK && null != senderService.hotspotControl) {
                if (senderService.hotspotControl!!.isEnabled) senderService.updateForegroundNotification() else {
                    removeMessages(AP_START_CHECK)
                    sendEmptyMessageDelayed(AP_START_CHECK, 800)
                }
            }
        }

    }

    companion object {
        private const val TAG = "ShareService"
        private const val SHARE_SERVICE_NOTIFICATION_ID = 100001
        const val WIFI_AP_ACTION_START = "wifi_ap_start"
        const val WIFI_AP_ACTION_STOP = "wifi_ap_stop"
        const val WIFI_AP_ACTION_START_CHECK = "wifi_ap_check"
        const val EXTRA_FILE_PATHS = "file_paths"
        const val EXTRA_PORT = "server_port"
        const val EXTRA_SENDER_NAME = "sender_name"
        private const val AP_ALIVE_CHECK = 100
        private const val AP_START_CHECK = 101
        private var DEFAULT_SENDER_NAME = "Sender."
        fun generateP2PSpuulName(): String {
            var androidId = Settings.Secure.ANDROID_ID
            androidId = if (TextUtils.isEmpty(androidId)) "" else if (androidId.length <= 3) androidId else androidId.substring(androidId.length - 3, androidId.length)
            return DEFAULT_SENDER_NAME + androidId
        }
    }
}