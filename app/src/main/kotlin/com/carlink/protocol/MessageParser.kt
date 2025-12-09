package com.carlink.protocol

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * CPC200-CCPA Protocol Message Parser
 *
 * Parses binary messages received from the Carlinkit adapter into typed message objects.
 * Handles header validation, payload extraction, and type-specific parsing.
 *
 * Ported from: lib/driver/readable.dart
 */
object MessageParser {
    /**
     * Parse a 16-byte header from raw bytes.
     *
     * @param data Raw header bytes (must be exactly 16 bytes)
     * @return Parsed MessageHeader
     * @throws HeaderParseException if header is invalid
     */
    fun parseHeader(data: ByteArray): MessageHeader {
        if (data.size != HEADER_SIZE) {
            throw HeaderParseException("Invalid buffer size - Expecting $HEADER_SIZE, got ${data.size}")
        }

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buffer.int
        if (magic != PROTOCOL_MAGIC) {
            throw HeaderParseException("Invalid magic number, received 0x${magic.toString(16)}")
        }

        val length = buffer.int
        val typeInt = buffer.int
        val msgType = MessageType.fromId(typeInt)

        if (msgType != MessageType.UNKNOWN) {
            val typeCheck = buffer.int
            val expectedCheck = (typeInt.inv()) and 0xFFFFFFFF.toInt()
            if (typeCheck != expectedCheck) {
                throw HeaderParseException("Invalid type check, received 0x${typeCheck.toString(16)}")
            }
        }

        return MessageHeader(length, msgType)
    }

    /**
     * Parse a complete message from header and payload.
     *
     * @param header Parsed message header
     * @param payload Message payload bytes (can be null for some message types)
     * @return Parsed Message object
     */
    fun parseMessage(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message =
        when (header.type) {
            MessageType.AUDIO_DATA -> parseAudioData(header, payload)
            MessageType.VIDEO_DATA -> parseVideoData(header, payload)
            MessageType.MEDIA_DATA -> parseMediaData(header, payload)
            MessageType.BLUETOOTH_ADDRESS -> parseStringMessage(header, payload) { BluetoothAddressMessage(header, it) }
            MessageType.BLUETOOTH_DEVICE_NAME -> parseStringMessage(header, payload) { BluetoothDeviceNameMessage(header, it) }
            MessageType.BLUETOOTH_PIN -> parseStringMessage(header, payload) { BluetoothPinMessage(header, it) }
            MessageType.MANUFACTURER_INFO -> parseManufacturerInfo(header, payload)
            MessageType.SOFTWARE_VERSION -> parseStringMessage(header, payload) { SoftwareVersionMessage(header, it) }
            MessageType.COMMAND -> parseCommand(header, payload)
            MessageType.PLUGGED -> parsePlugged(header, payload)
            MessageType.UNPLUGGED -> UnpluggedMessage(header)
            MessageType.WIFI_DEVICE_NAME -> parseStringMessage(header, payload) { WifiDeviceNameMessage(header, it) }
            MessageType.HI_CAR_LINK -> parseStringMessage(header, payload) { HiCarLinkMessage(header, it) }
            MessageType.BLUETOOTH_PAIRED_LIST -> parseStringMessage(header, payload) { BluetoothPairedListMessage(header, it) }
            MessageType.NETWORK_MAC_ADDRESS -> parseStringMessage(header, payload) { NetworkMacAddressMessage(header, it) }
            MessageType.NETWORK_MAC_ADDRESS_ALT -> parseStringMessage(header, payload) { NetworkMacAddressAltMessage(header, it) }
            MessageType.OPEN -> parseOpened(header, payload)
            MessageType.BOX_SETTINGS -> parseBoxInfo(header, payload)
            MessageType.PHASE -> parsePhase(header, payload)
            else -> UnknownMessage(header, payload)
        }

    private fun parseAudioData(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || payload.size < 12) {
            return UnknownMessage(header, payload)
        }

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        val decodeType = buffer.int
        val volume = buffer.float
        val audioType = buffer.int

        val remainingBytes = payload.size - 12

        return when {
            remainingBytes == 1 -> {
                val commandId = payload[12].toInt() and 0xFF
                AudioDataMessage(
                    header = header,
                    decodeType = decodeType,
                    volume = volume,
                    audioType = audioType,
                    command = AudioCommand.fromId(commandId),
                    data = null,
                    volumeDuration = null,
                )
            }

            remainingBytes == 4 -> {
                buffer.position(12)
                val duration = buffer.float
                AudioDataMessage(
                    header = header,
                    decodeType = decodeType,
                    volume = volume,
                    audioType = audioType,
                    command = null,
                    data = null,
                    volumeDuration = duration,
                )
            }

            remainingBytes > 0 -> {
                val audioData = ByteArray(remainingBytes)
                System.arraycopy(payload, 12, audioData, 0, remainingBytes)
                AudioDataMessage(
                    header = header,
                    decodeType = decodeType,
                    volume = volume,
                    audioType = audioType,
                    command = null,
                    data = audioData,
                    volumeDuration = null,
                )
            }

            else -> {
                AudioDataMessage(
                    header = header,
                    decodeType = decodeType,
                    volume = volume,
                    audioType = audioType,
                    command = null,
                    data = null,
                    volumeDuration = null,
                )
            }
        }
    }

    private fun parseVideoData(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || payload.size <= 20) {
            return VideoDataMessage(
                header = header,
                width = -1,
                height = -1,
                flags = -1,
                length = -1,
                unknown = -1,
                data = null,
            )
        }

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        val width = buffer.int
        val height = buffer.int
        val flags = buffer.int
        val length = buffer.int
        val unknown = buffer.int

        val videoData =
            if (payload.size > 20) {
                ByteArray(payload.size - 20).also {
                    System.arraycopy(payload, 20, it, 0, it.size)
                }
            } else {
                null
            }

        return VideoDataMessage(
            header = header,
            width = width,
            height = height,
            flags = flags,
            length = length,
            unknown = unknown,
            data = videoData,
        )
    }

    private fun parseMediaData(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || payload.size < 4) {
            return MediaDataMessage(header, MediaType.UNKNOWN, emptyMap())
        }

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val typeInt = buffer.int
        val mediaType = MediaType.fromId(typeInt)

        val mediaPayload: Map<String, Any> =
            when (mediaType) {
                MediaType.ALBUM_COVER -> {
                    val imageData = ByteArray(payload.size - 4)
                    System.arraycopy(payload, 4, imageData, 0, imageData.size)
                    mapOf("AlbumCover" to imageData)
                }

                MediaType.DATA -> {
                    try {
                        val jsonBytes = ByteArray(payload.size - 5) // Exclude type int and trailing null
                        System.arraycopy(payload, 4, jsonBytes, 0, jsonBytes.size)
                        val jsonString = String(jsonBytes, StandardCharsets.UTF_8).trim('\u0000')
                        val json = JSONObject(jsonString)
                        json.keys().asSequence().associateWith { json.get(it) }
                    } catch (e: Exception) {
                        emptyMap()
                    }
                }

                else -> {
                    emptyMap()
                }
            }

        return MediaDataMessage(header, mediaType, mediaPayload)
    }

    private inline fun <T : Message> parseStringMessage(
        header: MessageHeader,
        payload: ByteArray?,
        factory: (String) -> T,
    ): Message {
        val value =
            payload?.let {
                String(it, StandardCharsets.US_ASCII).trim('\u0000')
            } ?: ""
        return factory(value)
    }

    private fun parseManufacturerInfo(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || payload.size < 8) {
            return ManufacturerInfoMessage(header, 0, 0)
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val a = buffer.int
        val b = buffer.int
        return ManufacturerInfoMessage(header, a, b)
    }

    private fun parseCommand(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || payload.size < 4) {
            return CommandMessage(header, CommandMapping.INVALID)
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val commandId = buffer.int
        return CommandMessage(header, CommandMapping.fromId(commandId))
    }

    private fun parsePlugged(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || payload.size < 4) {
            return PluggedMessage(header, PhoneType.UNKNOWN, null)
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val phoneTypeId = buffer.int
        val phoneType = PhoneType.fromId(phoneTypeId)

        val wifi =
            if (payload.size >= 8) {
                buffer.int
            } else {
                null
            }

        return PluggedMessage(header, phoneType, wifi)
    }

    private fun parseOpened(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || payload.size < 28) {
            return OpenedMessage(header, 0, 0, 0, 0, 0, 0, 0)
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return OpenedMessage(
            header = header,
            width = buffer.int,
            height = buffer.int,
            fps = buffer.int,
            format = buffer.int,
            packetMax = buffer.int,
            iBox = buffer.int,
            phoneMode = buffer.int,
        )
    }

    private fun parseBoxInfo(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null) {
            return BoxInfoMessage(header, emptyMap())
        }
        return try {
            val jsonString = String(payload, StandardCharsets.UTF_8).trim('\u0000')
            val json = JSONObject(jsonString)
            val settings = json.keys().asSequence().associateWith { json.get(it) }
            BoxInfoMessage(header, settings)
        } catch (e: Exception) {
            BoxInfoMessage(header, emptyMap())
        }
    }

    private fun parsePhase(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || payload.size < 4) {
            return PhaseMessage(header, 0)
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return PhaseMessage(header, buffer.int)
    }
}

// ==================== Message Classes ====================

/**
 * Base class for all protocol messages.
 */
sealed class Message(
    val header: MessageHeader,
) {
    override fun toString(): String = "Message(type=${header.type})"
}

/**
 * Unknown or unhandled message type.
 */
class UnknownMessage(
    header: MessageHeader,
    val data: ByteArray?,
) : Message(header) {
    override fun toString(): String = "UnknownMessage(type=${header.type}, dataLength=${data?.size})"
}

/**
 * Command message from adapter.
 */
class CommandMessage(
    header: MessageHeader,
    val command: CommandMapping,
) : Message(header) {
    override fun toString(): String = "Command(${command.name})"
}

/**
 * Device plugged notification.
 */
class PluggedMessage(
    header: MessageHeader,
    val phoneType: PhoneType,
    val wifi: Int?,
) : Message(header) {
    override fun toString(): String = "Plugged(phoneType=${phoneType.name}, wifi=$wifi)"
}

/**
 * Device unplugged notification.
 */
class UnpluggedMessage(
    header: MessageHeader,
) : Message(header) {
    override fun toString(): String = "Unplugged"
}

/**
 * Audio data message with PCM samples or command.
 */
class AudioDataMessage(
    header: MessageHeader,
    val decodeType: Int,
    val volume: Float,
    val audioType: Int,
    val command: AudioCommand?,
    val data: ByteArray?,
    val volumeDuration: Float?,
) : Message(header) {
    override fun toString(): String {
        val format = AudioFormats.fromDecodeType(decodeType)
        val formatInfo = format?.let { "${it.frequency}Hz ${it.channels}ch" } ?: "unknown"
        return when {
            command != null -> "AudioData(command=${command.name})"
            volumeDuration != null -> "AudioData(volumeDuration=$volumeDuration)"
            else -> "AudioData(format=$formatInfo, audioType=$audioType, bytes=${data?.size ?: 0})"
        }
    }
}

/**
 * Video data message with H.264 frame data.
 */
class VideoDataMessage(
    header: MessageHeader,
    val width: Int,
    val height: Int,
    val flags: Int,
    val length: Int,
    val unknown: Int,
    val data: ByteArray?,
) : Message(header) {
    override fun toString(): String = "VideoData(${width}x$height, flags=$flags, length=$length)"
}

/**
 * Media metadata message.
 */
class MediaDataMessage(
    header: MessageHeader,
    val type: MediaType,
    val payload: Map<String, Any>,
) : Message(header) {
    override fun toString(): String = "MediaData(type=${type.name})"
}

/**
 * Manufacturer info message.
 */
class ManufacturerInfoMessage(
    header: MessageHeader,
    val a: Int,
    val b: Int,
) : Message(header) {
    override fun toString(): String = "ManufacturerInfo(a=$a, b=$b)"
}

/**
 * Software version message.
 */
class SoftwareVersionMessage(
    header: MessageHeader,
    val version: String,
) : Message(header) {
    override fun toString(): String = "SoftwareVersion($version)"
}

/**
 * Bluetooth address message.
 */
class BluetoothAddressMessage(
    header: MessageHeader,
    val address: String,
) : Message(header) {
    override fun toString(): String = "BluetoothAddress($address)"
}

/**
 * Bluetooth PIN message.
 */
class BluetoothPinMessage(
    header: MessageHeader,
    val pin: String,
) : Message(header) {
    override fun toString(): String = "BluetoothPIN($pin)"
}

/**
 * Bluetooth device name message.
 */
class BluetoothDeviceNameMessage(
    header: MessageHeader,
    val name: String,
) : Message(header) {
    override fun toString(): String = "BluetoothDeviceName($name)"
}

/**
 * WiFi device name message.
 */
class WifiDeviceNameMessage(
    header: MessageHeader,
    val name: String,
) : Message(header) {
    override fun toString(): String = "WifiDeviceName($name)"
}

/**
 * HiCar link message.
 */
class HiCarLinkMessage(
    header: MessageHeader,
    val link: String,
) : Message(header) {
    override fun toString(): String = "HiCarLink($link)"
}

/**
 * Bluetooth paired list message.
 */
class BluetoothPairedListMessage(
    header: MessageHeader,
    val data: String,
) : Message(header) {
    override fun toString(): String = "BluetoothPairedList($data)"
}

/**
 * Network MAC address message.
 */
class NetworkMacAddressMessage(
    header: MessageHeader,
    val macAddress: String,
) : Message(header) {
    override fun toString(): String = "NetworkMacAddress($macAddress)"
}

/**
 * Network MAC address (alternate) message.
 */
class NetworkMacAddressAltMessage(
    header: MessageHeader,
    val macAddress: String,
) : Message(header) {
    override fun toString(): String = "NetworkMacAddressAlt($macAddress)"
}

/**
 * Opened response message.
 */
class OpenedMessage(
    header: MessageHeader,
    val width: Int,
    val height: Int,
    val fps: Int,
    val format: Int,
    val packetMax: Int,
    val iBox: Int,
    val phoneMode: Int,
) : Message(header) {
    override fun toString(): String = "Opened(${width}x$height@${fps}fps, format=$format, packetMax=$packetMax)"
}

/**
 * Box info/settings message.
 */
class BoxInfoMessage(
    header: MessageHeader,
    val settings: Map<String, Any>,
) : Message(header) {
    override fun toString(): String = "BoxInfo(settings=$settings)"
}

/**
 * Phase message.
 */
class PhaseMessage(
    header: MessageHeader,
    val phase: Int,
) : Message(header) {
    override fun toString(): String = "Phase($phase)"
}

/**
 * Adapter configuration message (synthetic, not from protocol).
 */
class AdapterConfigurationMessage(
    val config: AdapterConfig,
) : Message(MessageHeader(0, MessageType.UNKNOWN)) {
    override fun toString(): String = "AdapterConfiguration(${config.width}x${config.height}@${config.fps}fps)"
}

/**
 * Video streaming signal (synthetic, not from protocol).
 * Indicates that video data is being streamed directly to the renderer.
 * Used when video data bypasses message parsing for zero-copy performance.
 */
object VideoStreamingSignal : Message(MessageHeader(0, MessageType.VIDEO_DATA)) {
    override fun toString(): String = "VideoStreamingSignal"
}
