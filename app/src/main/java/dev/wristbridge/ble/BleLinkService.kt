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
import android.bluetooth.BluetoothStatusCodes
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
import kotlinx.coroutines.flow.update
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/**
 * Runs the phone as a BLE peripheral that the watchOS app connects to.
 *
 * Android can only be discovered here, not discover: the watch is the central
 * and opens the connection. That is forced by watchOS, where CoreBluetooth
 * exposes no peripheral role, so the phone advertises and waits.
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

    /** Held until onServiceAdded confirms the GATT service is registered. */
    private var advertiseSettings: AdvertiseSettings? = null
    private var advertiseData: AdvertiseData? = null

    /** Peers that have enabled notifications on the TX characteristic. */
    private val subscribers = Collections.synchronizedSet(HashSet<String>())

    /**
     * Usable ATT payload per peer. Tracked separately from subscription because
     * the MTU exchange and the CCCD write arrive in either order, and binding
     * the two loses the negotiated size when the MTU lands first.
     */
    private val payloadSizes = Collections.synchronizedMap(HashMap<String, Int>())

    /**
     * Chunks still to go out, per peer, and the peers with one in flight.
     *
     * Android's stack carries a single outstanding notification at a time: the
     * next one may only be handed over once onNotificationSent has fired. A
     * plain loop therefore loses every chunk after the first, which at the
     * default 20-byte MTU means losing most of every message. Guarded by
     * [sendLock] because onNotificationSent arrives on a binder thread.
     */
    private val outbound = HashMap<String, ArrayDeque<ByteArray>>()
    private val inFlight = HashSet<String>()
    private val sendLock = Any()

    /**
     * Peers that have presented the pairing token. Encryption alone proves a
     * peer bonded with this phone at some point; this proves it is the watch
     * that was actually set up, and nothing is sent to anyone else.
     */
    private val authenticated = Collections.synchronizedSet(HashSet<String>())
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
        // Android restarts this after a process kill. If the Bluetooth grant
        // was revoked meanwhile, startForeground refuses, and an uncaught
        // throw here would crash the app rather than simply stopping the link.
        val started = runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
        if (started.isFailure) {
            update { it.copy(lastError = "Missing permission to run the watch link") }
            stopSelf()
            return START_NOT_STICKY
        }
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
            // ENCRYPTED, not plain WRITE: this makes Android insist on a bonded,
            // encrypted link before a peer may write anything. Without it any
            // device in radio range could talk to the bridge.
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        )

        val tx = BluetoothGattCharacteristic(
            BleProtocol.TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        ).apply {
            // Without a CCCD the central has no way to subscribe to notifications.
            addDescriptor(
                BluetoothGattDescriptor(
                    BleProtocol.CCCD_UUID,
                    // Subscribing is what exposes notification content, so the
                    // descriptor is gated on an encrypted link too.
                    BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                        BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
                )
            )
        }

        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
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

        // Deliberately carries no device name. A phone's Bluetooth name is
        // frequently the owner's own ("Marek's Pixel"), and broadcasting it
        // continuously to anyone scanning is a needless disclosure: the watch
        // learns the phone's name from the welcome message, over an encrypted
        // link, after it has proved who it is.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
            .build()

        advertiseSettings = settings
        advertiseData = data
        server.addService(service)
        // Advertising starts in onServiceAdded. Beginning here would let a
        // central connect before the service exists and find nothing.
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
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                update { it.copy(lastError = "Could not register the bridge service") }
                return
            }
            val settings = advertiseSettings ?: return
            val data = advertiseData ?: return
            runCatching { advertiser?.startAdvertising(settings, data, advertiseCallback) }
        }

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
                authenticated.remove(address)
                payloadSizes.remove(address)
                synchronized(sendLock) {
                    outbound.remove(address)
                    inFlight.remove(address)
                }
                update { it.copy(connectedWatch = null) }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            val address = device?.address ?: return
            // Three bytes of every ATT packet are header.
            payloadSizes[address] = (mtu - 3).coerceAtLeast(DEFAULT_PAYLOAD)
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            val address = device?.address ?: return
            synchronized(sendLock) { inFlight.remove(address) }
            // Whatever the status, move on: a failed chunk has already broken
            // this message, and stalling would wedge the queue for every later
            // one too. The watch simply never reassembles that message.
            pump(address)
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
            if (preparedWrite) {
                // Not supported, and silently accepting one would hand a
                // fragment to the reassembler as though it were whole.
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null
                    )
                }
                return
            }
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
                    if (enabling) subscribers.add(address) else subscribers.remove(address)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    // ---- Message handling --------------------------------------------------

    private fun handle(device: BluetoothDevice, json: String) {
        val message = BleProtocol.decode(json)

        // A handshake is the only thing entertained from an unverified peer.
        if (message !is BleProtocol.Inbound.Hello && device.address !in authenticated) {
            RelayLog.record(
                RelayLog.Outcome.SKIPPED,
                "Watch link",
                "Ignored a message from an unpaired device",
            )
            return
        }

        when (message) {
            is BleProtocol.Inbound.Hello -> handleHello(device, message)

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

            BleProtocol.Inbound.Unknown -> Log.w(TAG, "Unrecognised message from a peer")
        }
    }

    /**
     * Trust on first use: the first watch to say hello is issued a token and
     * remembered. Afterwards a peer must present that token, so a second device
     * in range cannot quietly take the watch's place.
     *
     * "Forget this watch" on the Watch tab clears the token when you genuinely
     * want to pair a different one.
     */
    private fun handleHello(device: BluetoothDevice, message: BleProtocol.Inbound.Hello) {
        // The wire format carries a version; refusing one this build does not
        // know is the point of sending it. Interpreting a newer dialect by
        // guesswork would be worse than declining it plainly.
        if (message.version != BleProtocol.PROTOCOL_VERSION) {
            RelayLog.record(
                RelayLog.Outcome.FAILED,
                "Watch link",
                "The watch app speaks a different version of the bridge protocol",
                "It expects version ${message.version}; this app speaks " +
                    "${BleProtocol.PROTOCOL_VERSION}. Update whichever is older.",
            )
            return
        }

        val known = trustPrefs.getString(KEY_TOKEN, null)

        val presented = message.token
        val tokenMatches = known != null && presented != null &&
            MessageDigest.isEqual(
                known.toByteArray(Charsets.UTF_8),
                presented.toByteArray(Charsets.UTF_8),
            )
        if (known != null && !tokenMatches) {
            authenticated.remove(device.address)
            RelayLog.record(
                RelayLog.Outcome.FAILED,
                "Watch link",
                "Refused a device that is not your paired watch",
                "Use \"Forget this watch\" on the Watch tab if you meant to pair a new one.",
            )
            return
        }

        val token = known ?: java.util.UUID.randomUUID().toString().also {
            trustPrefs.edit().putString(KEY_TOKEN, it).apply()
        }

        authenticated.add(device.address)
        update { it.copy(connectedWatch = message.watchName) }
        RelayLog.record(
            RelayLog.Outcome.SENT,
            "Watch link",
            if (known == null) {
                "Paired with ${message.watchName}"
            } else {
                "${message.watchName} connected"
            },
        )
        send(device, BleProtocol.welcome(Build.MODEL ?: "Android", token))
    }

    /** Returns false when the message could not be queued, so it was not sent. */
    private fun send(device: BluetoothDevice, message: ByteArray): Boolean {
        val address = device.address
        val payload = payloadSizes[address] ?: DEFAULT_PAYLOAD
        val chunks = BleProtocol.chunk(message, payload)

        synchronized(sendLock) {
            val queue = outbound.getOrPut(address) { ArrayDeque() }
            if (queue.size + chunks.size > MAX_QUEUED_CHUNKS) {
                // The peer has stopped acknowledging. Dropping is better than
                // growing without bound, and the mail relay is the fallback,
                // which is why the caller has to learn this failed.
                RelayLog.record(
                    RelayLog.Outcome.SKIPPED,
                    "Watch link",
                    "Dropped a message; the watch is not keeping up",
                )
                return false
            }
            queue.addAll(chunks)
        }
        update { it.copy(messagesOut = it.messagesOut + 1) }
        pump(address)
        return true
    }

    /**
     * Hands the next queued chunk to the stack, if nothing is already in
     * flight for that peer. Re-entered from onNotificationSent until the queue
     * drains.
     */
    @SuppressLint("MissingPermission")
    private fun pump(address: String) {
        val server = gattServer ?: return
        val characteristic = txCharacteristic ?: return
        val device = devices[address] ?: return

        val part = synchronized(sendLock) {
            if (address in inFlight) return
            val queue = outbound[address]
            val next = queue?.removeFirstOrNull() ?: return
            if (queue.isEmpty()) outbound.remove(address)
            inFlight.add(address)
            next
        }

        val handed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, part) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = part
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        }.getOrDefault(false)

        if (!handed) {
            // No onNotificationSent will arrive for a call the stack refused,
            // so release the slot here or the peer would never send again.
            synchronized(sendLock) { inFlight.remove(address) }
        }
    }

    /**
     * Pushes a notification to every verified, subscribed watch. Returns true
     * only if at least one received it, which is what tells the relay whether
     * it still needs to fall back to email.
     */
    fun broadcast(message: ByteArray): Boolean {
        val targets = synchronized(subscribers) { subscribers.toList() }
        var delivered = false
        for (address in targets) {
            if (address !in authenticated) continue
            val device = devices[address] ?: continue
            if (send(device, message)) delivered = true
        }
        return delivered
    }

    /** Drops the remembered watch so a different one can pair. */
    fun forgetPairedWatch() {
        trustPrefs.edit().remove(KEY_TOKEN).apply()
        authenticated.clear()
        update { it.copy(connectedWatch = null) }
        RelayLog.record(RelayLog.Outcome.SENT, "Watch link", "Forgot the paired watch")
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
        // update(), not value =, because binder callbacks and the UI thread
        // both land here and a read-modify-write would drop one.
        state.update(transform)
    }

    companion object {
        private const val TAG = "Wristbridge"
        private const val CHANNEL_ID = "wristbridge.ble"
        private const val NOTIFICATION_ID = 4712
        private const val KEY_TOKEN = "pair_token"

        /** The BLE default MTU of 23, less three bytes of ATT header. */
        private const val DEFAULT_PAYLOAD = 20

        /**
         * Roughly a dozen full-size messages at the default MTU. Past this the
         * peer is clearly not acknowledging and queuing more only delays the
         * fallback to mail.
         */
        private const val MAX_QUEUED_CHUNKS = 256

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
