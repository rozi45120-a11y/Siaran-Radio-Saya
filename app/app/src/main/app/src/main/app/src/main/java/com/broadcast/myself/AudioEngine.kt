package com.broadcast.myself

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.Socket
import kotlin.coroutines.coroutineContext

class AudioEngine {
    private var isStreaming = false
    private var socket: Socket? = null
    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null

    // Setting Audio Standard Radio
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    suspend fun startStreaming(ip: String, port: Int, mount: String, pass: String) = withContext(Dispatchers.IO) {
        try {
            isStreaming = true
            
            // 1. Setup Koneksi ke Server Icecast/Shoutcast
            Log.d("AudioEngine", "Connecting to $ip:$port...")
            socket = Socket(ip, port)
            val outputStream = socket!!.getOutputStream()
            sendHeaders(outputStream, mount, pass)

            // 2. Setup Encoder AAC (Pengganti MP3/LAME)
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000) // 128 kbps bitrate
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufferSize * 2)
            
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            // 3. Setup Mic
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat)
            audioRecord?.startRecording()

            val bufferInfo = MediaCodec.BufferInfo()
            val inputBuffer = ByteArray(minBufferSize)

            Log.d("AudioEngine", "Streaming Started!")

            // 4. Loop Utama: Baca Mic -> Encode AAC -> Kirim ke Internet
            while (isActive && isStreaming) {
                val read = audioRecord?.read(inputBuffer, 0, minBufferSize) ?: 0
                if (read > 0) {
                    val inputIndex = mediaCodec?.dequeueInputBuffer(2000) ?: -1
                    if (inputIndex >= 0) {
                        val codecBuffer = mediaCodec?.getInputBuffer(inputIndex)
                        codecBuffer?.clear()
                        codecBuffer?.put(inputBuffer, 0, read)
                        mediaCodec?.queueInputBuffer(inputIndex, 0, read, System.nanoTime() / 1000, 0)
                    }

                    var outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                    while (outputIndex >= 0) {
                        val encodedData = mediaCodec?.getOutputBuffer(outputIndex)
                        encodedData?.position(bufferInfo.offset)
                        encodedData?.limit(bufferInfo.offset + bufferInfo.size)

                        // Tambahkan Header ADTS agar stream dikenali player
                        val adtsHeader = createAdtsHeader(bufferInfo.size)
                        outputStream.write(adtsHeader)

                        val chunk = ByteArray(bufferInfo.size)
                        encodedData?.get(chunk)
                        outputStream.write(chunk)

                        mediaCodec?.releaseOutputBuffer(outputIndex, false)
                        outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioEngine", "Error: ${e.message}")
            stopStreaming()
            throw e // Lempar error ke UI agar user tahu
        }
    }

    fun stopStreaming() {
        isStreaming = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            mediaCodec?.stop()
            mediaCodec?.release()
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendHeaders(out: OutputStream, mount: String, pass: String) {
        val auth = android.util.Base64.encodeToString("source:$pass".toByteArray(), android.util.Base64.NO_WRAP)
        val headers = "PUT /$mount HTTP/1.0\r\n" +
                "Authorization: Basic $auth\r\n" +
                "Content-Type: audio/aac\r\n" +
                "Ice-Name: BroadcastMySelf-15\r\n" +
                "Ice-Public: 0\r\n\r\n"
        out.write(headers.toByteArray())
    }

    // Header wajib untuk AAC Streaming
    private fun createAdtsHeader(length: Int): ByteArray {
        val packetLen = length + 7
        val frame = ByteArray(7)
        frame[0] = 0xFF.toByte()
        frame[1] = 0xF9.toByte()
        frame[2] = 0x50.toByte() // 44100Hz, 2ch
        frame[3] = (0x40 or (packetLen shr 11)).toByte()
        frame[4] = ((packetLen and 0x7FF) shr 3).toByte()
        frame[5] = (((packetLen and 7) shl 5) or 0x1F).toByte()
        frame[6] = 0xFC.toByte()
        return frame
    }
}
