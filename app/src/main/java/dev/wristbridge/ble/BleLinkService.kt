package dev.wristbridge.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import dev.wristbridge.health.HealthStore
import dev.wristbridge.relay.RelayLog
import dev.wristbridge.relay.ReplyRegistry
import dev.wristbridge.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections
import java.util.UUID

/**
 * Runs the phone as a BLE peripheral that the watchOS app connects to.
 *
 * Android can only be discovered here, not discover: the watch is the central
 * and opens the connection. That is forced by watchOS, where CoreBluetooth
 * exposes no peripheral role — so the phone advertises and waits.
 *
 * Everything the email relay does over iCloud, this does directly when the
 * watch is in range: notifications out, replies and health data back.
 */
class BleLinkService : Service() {

    // ---- Observable state for the UI --------------------------------------

    data class LinkState(
        val advertising: Boolean = false,
        val connectedWatch: String? = null,
        val lastError: String? = null,
        val messagesIn: Int = 0,
        val messagesOut: Int = 0,
    )

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    /** Peers that have subscribed to TX, with their negotiated payload size. */
    private val subscribers = Collections.synchronizedMap(HashMap<String, Int>())
    private val devices = Collections.synchronizedMap(HashMap<String, BluetoothDevice>())
    private val reassemblers = Collections.synchronizedMap(HashMap<String, BleProtocol.Reassembler>())

    private val trustPrefs by lazy {
        getSharedPreferences("wristbridge.ble", Context.MODE_PRIVATE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (gattServer == null) start()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { gattServer?.close() }
        gattServer = null
        advertiser = null
        instance = null
        update { LinkState() }
        super.onDestroy()
    }

    // ---- Setup -------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun start() {
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            update { it.copy(lastError = "Bluetooth is off") }
            stopSelf()
            return
        }

        val server = manager.openGattServer(this, serverCallback)
        if (server == null) {
            update { it.copy(lastError = "Could not open a GATT server") }
            stopSelf()
            return
        }
        gattServer = server

        val service = BluetoothGattService(
            BleProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        val rx = BluetoothGattCharacteristic(
            BleProtocol.RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val tx = BluetoothGattCharacteristic(
            BleProtocol.TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            // Without a CCCD the central has no way to subscribe to notifications.
            addDescriptor(
                BluetoothGattDescriptor(
                    BleProtocol.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }

        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        server.addService(service)
        txCharacteristic = tx

        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            update { it.copy(lastError = "This phone cannot advertise over BLE") }
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        // The name is carried in the scan response: the 31-byte advertisement
        // is already spent on the 128-bit service UUID.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()

        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            update { it.copy(advertising = true, lastError = null) }
        }

        override fun onStartFailure(errorCode: Int) {
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertisement payload too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many BLE advertisers running"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already advertising"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Bluetooth internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE advertising unsupported"
                else -> "Advertising failed ($errorCode)"
            }
            update { it.copy(advertising = false, lastError = reason) }
        }
    }

    // ---- GATT server callbacks --------------------------------------------

    private val serverCallback = object : BluetoothGattServerCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val address = device?.address ?: return
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                devices[address] = device
                reassemblers[address] = BleProtocol.Reassembler()
            } else {
                devices.remove(address)
                subscribers.remove(address)
                reassemblers.remove(address)
                update { it.copy(connectedWatch = null) }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            val address = device?.address ?: return
            // Three bytes of every ATT packet are header.
            if (subscribers.containsKey(address)) subscribers[address] = mtu - 3
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (characteristic?.uuid != BleProtocol.RX_UUID) return

            val address = device?.address ?: return
            val bytes = value ?: return
            val assembled = reassemblers[address]?.accept(bytes) ?: return
            update { it.copy(messagesIn = it.messagesIn + 1) }
            handle(device, assembled)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (descriptor?.uuid == BleProtocol.CCCD_UUID) {
                val address = device?.address
                val enabling = value?.contentEquals(
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                ) == true
                if (address != null) {
                    if (enabling) subscribers[address] = DEFAULT_PAYLOAD else subscribers.remove(address)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    // ---- Message handling --------------------------------------------------

    private fun handle(device: BluetoothDevice, json: String) {
        when (val message = BleProtocol.decode(json)) {
            is BleProtocol.Inbound.Hello -> {
                val token = trustPrefs.getString(KEY_TOKEN, null)
                    ?: UUID.randomUUID().toString().also {
                        trustPrefs.edit().putString(KEY_TOKEN, it).apply()
                    }
                update { it.copy(connectedWatch = message.watchName) }
                RelayLog.record(RelayLog.Outcome.SENT, "Watch link", "${message.watchName} connected")
                send(device, BleProtocol.welcome(Build.MODEL ?: "Android", token))
            }

            is BleProtocol.Inbound.Health -> {
                HealthStore.get(this).record(message.samples)
                RelayLog.record(
                    RelayLog.Outcome.SENT,
                    "Watch link",
                    "${message.samples.size} health sample(s) received",
                )
            }

            is BleProtocol.Inbound.Reply -> {
                val pending = ReplyRegistry.consume(message.notificationId)
                if (pending == null) {
                    RelayLog.record(
                        RelayLog.Outcome.FAILED,
                        "Watch link",
                        message.text.take(60),
                        "That notification is no longer available to reply to",
                    )
                    return
                }
                val failure = ReplyRegistry.deliver(this, pending, message.text)
                if (failure == null) {
                    RelayLog.record(
                        RelayLog.Outcome.SENT,
                        pending.appLabel,
                        "Replied from watch: ${message.text.take(60)}",
                    )
                } else {
                    RelayLog.record(
                        RelayLog.Outcome.FAILED, pending.appLabel, message.text.take(60), failure
                    )
                }
                send(device, BleProtocol.ack(message.notificationId))
            }

            BleProtocol.Inbound.Unknown ->
                Log.w(TAG, "Unrecognised message from ${device.address}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun send(device: BluetoothDevice, message: ByteArray) {
        val server = gattServer ?: return
        val characteristic = txCharacteristic ?: return
        val payload = subscribers[device.address] ?: DEFAULT_PAYLOAD

        for (part in BleProtocol.chunk(message, payload)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, part)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = part
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        }
        update { it.copy(messagesOut = it.messagesOut + 1) }
    }

    /** Pushes a notification to every subscribed watch. Returns true if any got it. */
    fun broadcast(message: ByteArray): Boolean {
        val targets = synchronized(subscribers) { subscribers.keys.toList() }
        var delivered = false
        for (address in targets) {
            val device = devices[address] ?: continue
            send(device, message)
            delivered = true
        }
        return delivered
    }

    // ---- Foreground notification ------------------------------------------

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Watch link", NotificationManager.IMPORTANCE_MIN)
                .apply {
                    description = "Shown while the phone is reachable by your Apple Watch."
                    setShowBadge(false)
                }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Wristbridge watch link")
            .setContentText("Discoverable by your Apple Watch")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun update(transform: (LinkState) -> LinkState) {
        state.value = transform(state.value)
    }

    companion object {
        private const val TAG = "Wristbridge"
        private const val CHANNEL_ID = "wristbridge.ble"
        private const val NOTIFICATION_ID = 4712
        private const val KEY_TOKEN = "pair_token"

        /** The BLE default MTU of 23, less three bytes of ATT header. */
        private const val DEFAULT_PAYLOAD = 20

        val state = MutableStateFlow(LinkState())
        val observable: StateFlow<LinkState> = state

        /**
         * Set while the service is alive, so the notification relay can ask
         * whether the watch is in BLE range before falling back to email.
         */
        @Volatile
        var instance: BleLinkService? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BleLinkService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleLinkService::class.java))
        }
    }
}
