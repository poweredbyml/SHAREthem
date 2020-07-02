package com.tml.sharethem.sender

/**
 * Created by Sri on 18/12/16.
 */
internal interface FileTransferStatusListener {
    fun onBytesTransferProgress(ip: String, fileName: String, totalSize: Long, speed: String, currentSize: Long, percentageUploaded: Int)
    fun onBytesTransferCompleted(ip: String, fileName: String)
    fun onBytesTransferStarted(ip: String, fileName: String)
    fun onBytesTransferCancelled(ip: String, error: String, fileName: String)
}