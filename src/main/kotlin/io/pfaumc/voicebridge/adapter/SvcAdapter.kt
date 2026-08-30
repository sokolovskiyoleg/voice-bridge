package io.pfaumc.voicebridge.adapter

import de.maxhenkel.voicechat.api.BukkitVoicechatService
import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.audiosender.AudioSender
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent
import io.pfaumc.voicebridge.BridgeMetrics
import io.pfaumc.voicebridge.VoiceBridgePlugin
import io.pfaumc.voicebridge.session.ModType
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Adapter for Simple Voice Chat.
 *
 * Implements VoicechatPlugin to hook into SVC's server-side API.
 * - Listens for MicrophonePacketEvents from SVC players and relays to PV players via AudioRelay.
 * - Creates native AudioSenders to relay audio FROM PV players TO SVC players.
 *
 * Registration: This class must be registered as a VoicechatPlugin via SVC's service discovery.
 * On Paper/Bukkit, this is done via BukkitVoicechatService.
 */
class SvcAdapter(private val plugin: VoiceBridgePlugin) : VoicechatPlugin {

    private val logger = Logger.getLogger("VoiceBridge-SVC")

    private var serverApi: VoicechatServerApi? = null

    var pvAdapter: PvAdapter? = null

    // Native SVC audio senders for relaying PV player audio to SVC clients.
    // Using AudioSender associates the stream with the speaker UUID, allowing
    // SVC clients to apply their per-player volume and mute settings.
    private val outboundSenders = ConcurrentHashMap<UUID, AudioSender>()

    init {
        // Register this plugin with SVC's Bukkit service
        registerWithSvc()
    }

    private fun registerWithSvc() {
        val service = Bukkit.getServicesManager()
            .load(BukkitVoicechatService::class.java)
        if (service != null) {
            service.registerPlugin(this)
            logger.info("Registered VoiceBridge as SVC plugin")
        } else {
            logger.warning("BukkitVoicechatService not available — SVC may not be fully loaded yet")
        }
    }

    override fun getPluginId(): String = "voice-bridge"

    override fun initialize(api: VoicechatApi) {
        if (api is VoicechatServerApi) {
            this.serverApi = api
            logger.info("SVC server API initialized")
        }
    }

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(MicrophonePacketEvent::class.java, ::onMicrophonePacket)
        registration.registerEvent(PlayerConnectedEvent::class.java, ::onPlayerConnected)
        registration.registerEvent(PlayerDisconnectedEvent::class.java, ::onPlayerDisconnected)
    }

    // --- Event Handlers ---

    private fun onPlayerConnected(event: PlayerConnectedEvent) {
        val connection = event.connection
        val playerUuid = connection.player.uuid
        val bukkitPlayer = connection.player.player as? Player
        val playerName = bukkitPlayer?.name ?: playerUuid.toString()

        // Register this player as an SVC user
        plugin.sessionManager.register(playerUuid, playerName, ModType.SIMPLE_VOICE_CHAT)
        logger.info("SVC player connected: $playerName")

        // Register a fake UDP connection in PV so PV clients see a voice icon
        pvAdapter?.registerBridgedConnection(playerUuid)
    }

    private fun onPlayerDisconnected(event: PlayerDisconnectedEvent) {
        val playerUuid = event.playerUuid

        // Remove the fake PV connection before unregistering the session
        pvAdapter?.removeBridgedConnection(playerUuid)

        // Remove only the SVC mod type; session is fully removed only when all mod types are gone
        plugin.sessionManager.unregister(playerUuid, ModType.SIMPLE_VOICE_CHAT)

        // Unregister the native sender for this player
        outboundSenders.remove(playerUuid)?.let { sender ->
            serverApi?.unregisterAudioSender(sender)
            sender.reset()
            logger.fine("Closed outbound sender for disconnected SVC player $playerUuid")
        }

        // Signal audio end on PV side for this player's outbound source
        plugin.audioRelay.pvAdapter?.cleanupSource(playerUuid)
    }

    /**
     * Called when an SVC player sends a microphone packet.
     * Relay this audio to PV players via the AudioRelay.
     */
    private fun onMicrophonePacket(event: MicrophonePacketEvent) {
        val senderConnection = event.senderConnection ?: return
        val senderUuid = senderConnection.player.uuid
        val packet = event.packet
        val opusData = packet.opusEncodedData
        if (opusData.isEmpty()) return
        val whispering = packet.isWhispering

        // Touch session to keep it alive
        plugin.sessionManager.getSession(senderUuid)?.touch()

        // Get the Bukkit player for position info
        val bukkitPlayer = Bukkit.getPlayer(senderUuid) ?: return

        // Get the configured distance
        val api = serverApi ?: return
        val distance = api.voiceChatDistance.toFloat()

        // Relay to PV players
        plugin.audioRelay.relaySvcToPv(
            senderUuid = senderUuid,
            senderPlayer = bukkitPlayer,
            opusData = opusData,
            sequenceNumber = 0, // SVC MicrophonePacket doesn't expose sequence to API
            distance = distance,
            whispering = whispering
        )
    }

    // --- Outbound: Send audio FROM a PV player TO SVC clients ---

    /**
     * Send audio from a PV player to nearby SVC clients as a native player stream.
     *
     * @return true if audio was sent successfully
     */
    fun sendAudioFromExternalPlayer(
        senderUuid: UUID,
        senderPlayer: Player,
        opusData: ByteArray,
        sequenceNumber: Long,
        distance: Float
    ): Boolean {
        val api = serverApi ?: return false

        val connection = api.getConnectionOf(senderUuid) ?: return false
        val sender = outboundSenders[senderUuid] ?: synchronized(outboundSenders) {
            outboundSenders[senderUuid] ?: api.createAudioSender(connection)
                .takeIf { api.registerAudioSender(it) }
                ?.also { outboundSenders[senderUuid] = it }
        } ?: return false

        if (!sender.canSend()) return false
        return sender.send(opusData)
    }

    /**
     * Clean up resources for a PV player who stopped talking.
     */
    fun flushChannel(senderUuid: UUID) {
        outboundSenders[senderUuid]?.reset()
    }

    /**
     * Remove channel for a player (e.g., on disconnect).
     */
    fun removeChannel(senderUuid: UUID) {
        outboundSenders.remove(senderUuid)?.let { sender ->
            serverApi?.unregisterAudioSender(sender)
            sender.reset()
        }
    }

    /**
     * Mark a non-SVC player as connected/disconnected in SVC's player state.
     * This updates the voice icon for SVC clients.
     * Only works for players without the SVC mod installed (i.e., PV-only players).
     */
    fun setExternalPlayerConnected(playerUuid: UUID, connected: Boolean) {
        serverApi?.getConnectionOf(playerUuid)?.isConnected = connected
    }

    fun shutdown() {
        outboundSenders.values.forEach { sender ->
            sender.reset()
            serverApi?.unregisterAudioSender(sender)
        }
        outboundSenders.clear()
        logger.info("SVC adapter shut down")
    }
}
