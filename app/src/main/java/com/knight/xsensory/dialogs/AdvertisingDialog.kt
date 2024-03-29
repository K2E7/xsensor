package com.knight.xsensory.dialogs

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.lifecycle.viewModelScope
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.knight.xsensory.AppViewModel
import com.knight.xsensory.dialogs.DiscoveringDialog
import com.knight.xsensory.MyApp
import com.knight.xsensory.databinding.LayoutSearchingToSendBinding
import kotlinx.coroutines.launch
import java.io.File

class AdvertisingDialog(private val vm:AppViewModel, private val context: Context, private val file : File) {

    private lateinit var dialog: AlertDialog
    private lateinit var b: LayoutSearchingToSendBinding
    private val connectionsClient: ConnectionsClient by lazy{ Nearby.getConnectionsClient(context)}
    var connectionEnd = false


    fun show(){
        b = LayoutSearchingToSendBinding.inflate(LayoutInflater.from(context))
        b.apply{
            animLottie.visibility = View.VISIBLE
            tvSearching.text = "Searching connection..."
            btnStartTransfer.visibility = View.GONE
        }

        dialog = AlertDialog.Builder(context)
            .setView(b.root)
            .setCancelable(false)
            .show()


        startAdvertising()

    }

    // Callback for connecting to other devices
    private val connectionLifecycleCallback = object: ConnectionLifecycleCallback(){
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {

            // Accept connection with Discoverer
            vm.viewModelScope.launch {
                b.apply{
                    animLottie.visibility = View.GONE
                    tvSearching.text = connectionInfo.endpointName
                    btnStartTransfer.apply{
                        visibility = View.VISIBLE
                        setOnClickListener {
                            connectionsClient.acceptConnection(endpointId,payloadCallback)
                                .addOnSuccessListener {
                                    Log.d("Advertising Dialog","Connection Accepted Successfully")
                                }
                                .addOnFailureListener {
                                    b.tvSearching.text = it.localizedMessage?:"Unknown error occurred"
                                    Log.d("Advertising Dialog",it.localizedMessage?:"Unknown error")
                                }
                            this.visibility = View.GONE
                        }
                    }
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {

            // Connection setup with Discoverer, update UI according to the connection status
            vm.viewModelScope.launch{
                when (result.status.statusCode) {
                    ConnectionsStatusCodes.STATUS_OK -> {
                        // We're connected! Can now start sending and receiving data.

                        // if you were advertising, you can stop
                        connectionsClient.stopAdvertising()

                        b.tvSearching.text = "Sending file..."
                        val pfd = context.contentResolver.openFileDescriptor(Uri.fromFile(file),"r")
                            ?: kotlin.run { b.tvSearching.text = "File not found"
                                return@launch}
                        connectionsClient.sendPayload(endpointId,Payload.fromFile(pfd))
                            .addOnSuccessListener {
                                b.tvSearching.text = "Payload Sent Success"
                            }
                            .addOnFailureListener {
                                Log.e("Advertising",it.toString())
                                b.tvSearching.text = it.localizedMessage ?: "Unknown error in sending payload"
                            }
                    }
                    ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                        // The connection was rejected by one or both sides.
                        b.tvSearching.text = "Connection rejected"
                    }
                    ConnectionsStatusCodes.STATUS_ERROR -> {
                        // The connection broke before it was able to be accepted.
                        b.tvSearching.text = result.status.statusMessage ?: "Unknown Error"
                    }
                    else -> {
                        // Unknown status code
                        b.tvSearching.text = "Unknown Error!"
                    }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            if(!connectionEnd)b.tvSearching.text = "Connection disconnected"
        }
    }

    // Callback for payload shared
    private val payloadCallback = object : PayloadCallback(){
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // Not required as only sending payload, not receiving
            Log.e("AdvertisingDialog","-> onPayloadReceived")
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // If file shared successfully show Done, and if not then show data transferred
            b.tvSearching.text = if(update.status == PayloadTransferUpdate.Status.SUCCESS) {
                dialog.setCancelable(true)
                connectionEnd = true
                "Done\nConnection End"
            } else "Sent: ${(update.bytesTransferred)/1024}KB \nTotal: ${(update.totalBytes)/1024}KB"

            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                // File received successfully
                // Stop advertising
                connectionsClient.stopAdvertising()

                // Start discovery to find Device 3
                startDiscovery()
            }
        }
    }



    // Start advertising the connection
    private fun startAdvertising(){
        vm.viewModelScope.launch {
            connectionsClient.startAdvertising(
                "${Build.BRAND}: ${Build.MODEL}",
                context.packageName,
                connectionLifecycleCallback,
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
            )
                .addOnFailureListener {
                    b.tvSearching.text = it.localizedMessage?:"Unknown error occurred"
                }
        }

    }

    // Start discovering connection
    private fun startDiscovery(){
        val endpointDiscoveryCallback:EndpointDiscoveryCallback =
            object:EndpointDiscoveryCallback(){
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    b.apply {
                        tvSearching.text = info.endpointName
                        animLottie.visibility = View.GONE
                        btnStartTransfer.apply {
                            visibility = View.VISIBLE
                            setOnClickListener {
                                requestConnection(endpointId)
                                this.visibility = View.GONE
                                b.tvSearching.text = "Sending request..."
                            }
                        }
                    }
                }

                override fun onEndpointLost(endpointId: String) {
                    b.tvSearching.text = "Connection Lost"
                }

            }

        connectionsClient.startDiscovery(context.packageName,endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
            .addOnFailureListener {
                b.tvSearching.text = it.localizedMessage?:"Unknown error occurred"
            }
    }

    private fun requestConnection(endpointId: String) {
        connectionsClient.requestConnection(
            "${Build.BRAND}: ${Build.MODEL}",
            endpointId,
            connectionLifecycleCallback
        )
            .addOnSuccessListener {
                Log.d("Discovering Dialog","Connection Requested Successfully")
            }
            .addOnFailureListener {
                b.tvSearching.text = it.localizedMessage?:"Unknown error occurred"
                Log.d("Discovering Dialog",it.localizedMessage?:"Unknown")
            }
    }


}