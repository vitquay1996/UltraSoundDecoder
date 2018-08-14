package com.example.vitquay.ultrasounddecoder

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import edu.emory.mathcs.jtransforms.fft.DoubleFFT_1D
import android.media.audiofx.LoudnessEnhancer
import java.lang.String.format
import android.text.method.ScrollingMovementMethod

class MainActivity : AppCompatActivity() {

    // State
    val RECEIVING = 1
    val ACK = 2
    val WAITING = 3
    val CHECKING = 4

    // Other var
    var lastTimeStringDecoded:Long = 0
    var prevDecodedString: String = ""
    var soundSampler: SoundSampler? = null
    var fftThread: Thread? = null
    var fftArray: DoubleArray? = null
    var handler: Handler = Handler(Looper.getMainLooper())
    var headerLength:Int = 1
    var globalState:Int = RECEIVING
    var stringArray:ArrayList<String> = ArrayList<String>()
    var sum:Int = 0
    var ringFrequency:Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupPermission()
        globalState = WAITING // Set initial state

        // Initialize TextViews and buttons
        textView.text = "\n.\n.\n.\n.\n.\n.\n.\n.\n.\n.\n.\n.\n.\n.\n.\nState: WAITING\nReady to receive DISCOVERY signal"
        textView.movementMethod = ScrollingMovementMethod()
        decodedTextView.text = "Decoded String: "

        convertButton.setOnClickListener {
            peakTextView.text = convertHexToString(peakTextView.text.dropLast(1).toString())
        }

        resetStringButton.setOnClickListener {
            decodedTextView.text = "Decoded String: "
        }

        resetButton.setOnClickListener {
            peakTextView.text = ""
            prevDecodedString = ""
            lastTimeStringDecoded = System.currentTimeMillis()
            globalState = WAITING
            textView.text = textView.text.toString() + "\nState: WAITING"
            textView.scrollTo(0, textView.getLayout().getLineTop(textView.getLineCount()) - textView.getHeight())
            headerLength = 1
            sum = 0
        }

        soundButton.setOnClickListener {
            val tone = generateTone(2, 17900)
            val enhancer = LoudnessEnhancer(tone.getAudioSessionId())
            enhancer.setTargetGain(100)
            enhancer.setEnabled(true)
            tone.play()
        }

        // Initialize Sound Sampler
        soundSampler = SoundSampler(44100, 44100)
        try {
            soundSampler!!.init()
        } catch (e: Exception) {
            Toast.makeText(this, "Sound Sample Init() Failed", Toast.LENGTH_LONG).show()
        }

        fftArray = DoubleArray(soundSampler!!.bufferSize)

        val doubleFFT_1D = DoubleFFT_1D(soundSampler!!.bufferSize)

        // Main Thread to calculate FFT
        fftThread = object : Thread() {
            override fun run() {
                while (true) {

                    /* Interrupt handler so that no resources is leaked*/
                    if (Thread.currentThread().isInterrupted) {
                        return
                    }
                    if (globalState == WAITING && ringFrequency > 0) {
                        val tone = generateTone(1, ringFrequency)
                        val enhancer = LoudnessEnhancer(tone.getAudioSessionId())
                        enhancer.setTargetGain(100)
                        enhancer.setEnabled(true)
                        tone.play()
                        ringFrequency = 0
                    }
                    if (globalState == RECEIVING || globalState == WAITING) {
                        shortArrayToDoubleArray(fftArray!!, soundSampler!!.buffer)

                        doubleFFT_1D.realForward(fftArray)

                        handler.post{
                            calculatePeak(18000, 21000, 1)
                        }
                    } else if (globalState == ACK) {
                        val tone = generateTone(1, 17900)
                        val enhancer = LoudnessEnhancer(tone.getAudioSessionId())
                        enhancer.setTargetGain(100)
                        enhancer.setEnabled(true)
                        tone.play()
                        headerLength = 1
                        sum = 0
                        globalState = RECEIVING
                    }

                    try {
                        sleep(5)
                    } catch (e: InterruptedException) {
                        return
                    }
                }
            }
        }
        fftThread!!.start()
    }

    // Generate tone of length durationS and frequency
    private fun generateTone(durationS: Int, frequency: Int): AudioTrack {
        val count = (44100.0 * (durationS - 0.4)).toInt()
        val buffer = ShortArray(count)

        // Initialize inverseBuffer to all 0s
        val inverseBuffer = DoubleArray(count)
        for (i in 0..(count-1)) {
            inverseBuffer[i]= 0.0
        }

        // Get the sample array from the array of frequency
        var j = 0
        while (j < count) {
            var sample = Math.cos(2.0 * Math.PI * j.toDouble() / (44100.0 / frequency)) * 0x7FFF
            inverseBuffer[j] += sample * 1
            j += 1
        }

        //Copy inverseBuffer to buffer
        for (i in 0..(count-1)) {
            buffer[i] = inverseBuffer[i].toShort()
        }

        val player = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_UNKNOWN)
                        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                        .build())
                .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(count * (16 / 8))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

        player.write(buffer, 0, count)
        player.setVolume(AudioTrack.getMaxVolume())

        return player
    }

    // Calculate peak frequency from fft array
    private fun calculatePeak(startFreq: Int, endFreq: Int, peakCount: Int) {
        var values = ArrayList<Pair>()
        var decodedString = ""

        /* Calculates magnitudes of FFT-ed array*/
        for (i in startFreq .. endFreq) {
            var magnitude = Math.log((Math.sqrt(Math.pow(fftArray!![2 * i], 2.0) + Math.pow(fftArray!![2 * i + 1], 2.0)) ))
            values.add(Pair(i, magnitude))
        }

        /* Sorts the magnitudes */
        values.sortDescending()

        if (values[0].magnitude > 15) {

            decodedString = when (values[0].frequency) {
                19500 -> "0"
                18000 -> "1"
                18100 -> "2"
                18200 -> "3"
                18300 -> "4"
                18400 -> "5"
                18550 -> "6"
                18600 -> "7"
                18700 -> "8"
                18800 -> "9"
                18900 -> "a"
                19000 -> "b"
                19100 -> "c"
                19200 -> "d"
                19300 -> "e"
                19400 -> "f"
                19600 -> "s"
                else -> ""
            }

        }

        handler.post {
            if (decodedString != "" && System.currentTimeMillis() - lastTimeStringDecoded > 1000) {
                lastTimeStringDecoded = System.currentTimeMillis()
                if (decodedString == "s") {
                    globalState == CHECKING
                    textView.text = textView.text.toString() + "\nState: CHECKING"
                    textView.scrollTo(0, textView.getLayout().getLineTop(textView.getLineCount()) - textView.getHeight())

                    for (i in 0..(peakTextView.text.length-2)) {
                        sum = sum + Integer.parseInt(peakTextView.text[i].toString(), 16)
                    }
                    sum = sum % 16
                    if (peakTextView.text.length > 0 && sum == Integer.parseInt(peakTextView.text[peakTextView.text.length - 1].toString(), 16)) {
                        textView.text = textView.text.toString() + "\nChecksum is correct"
                        textView.scrollTo(0, textView.getLayout().getLineTop(textView.getLineCount()) - textView.getHeight())
                        decodedTextView.text = decodedTextView.text.toString() + convertHexToString(peakTextView.text.dropLast(1).toString())
                        peakTextView.text = ""
                        ringFrequency = 17800
                        globalState = WAITING
                        sum = 0
                        textView.text = textView.text.toString() + "\nState: WAITING"
                        textView.scrollTo(0, textView.getLayout().getLineTop(textView.getLineCount()) - textView.getHeight())
                    } else if (peakTextView.text.length > 0) {
                        textView.text = textView.text.toString() + "\nChecksum is incorrect"
                        textView.scrollTo(0, textView.getLayout().getLineTop(textView.getLineCount()) - textView.getHeight())
                        ringFrequency = 17850
                        resetButton.performClick()
                    }
                }
                if (globalState == RECEIVING) {
                    headerLength--;
                    if (headerLength < 0) {
                        peakTextView.text = peakTextView.text.toString() + decodedString
                    }
                } else if (globalState == WAITING) {
                    stringArray.add(decodedString)
                    if (stringArray.size > 4) {
                        stringArray.removeAt(0)
                    }
                    if (stringArray.size == 4) {
                        textView.text = textView.text.toString() + "\nString array:" + stringArray[0] + stringArray[1] + stringArray[2] + stringArray[3]
                        textView.scrollTo(0, textView.getLayout().getLineTop(textView.getLineCount()) - textView.getHeight())
                        if (stringArray[0] == "1" && stringArray[1] == "2" && stringArray[2] == "1" && stringArray[3] == "2") {
                            Log.d("ADFF", "State changed to ACK")
                            textView.text = textView.text.toString() + "\nState: ACK"
                            textView.scrollTo(0, textView.getLayout().getLineTop(textView.getLineCount()) - textView.getHeight())
                            globalState = ACK
                        }
                    }
                }
            }
        }

        /*----------------------------------------------------------------*/
    }

    class Pair(var frequency: Int, var magnitude: Double) : Comparable<Pair> {
        override fun compareTo(other: Pair): Int {
            return when {
                this.magnitude > other.magnitude -> 1
                this.magnitude < other.magnitude -> -1
                else -> 0
            }
        }
    }

    /**
     * Helper function, short array -> double array
     */
    fun shortArrayToDoubleArray(doubleArray: DoubleArray, shortArray: ShortArray) {
        if (shortArray.size != doubleArray.size) {
            println("Size mismatch")
            return
        } else {
            for (i in 0 until shortArray.size) {
                doubleArray[i] = shortArray[i].toDouble()
            }
        }
    }

    fun convertHexToString(hex: String): String {

        val sb = StringBuilder()
        val temp = StringBuilder()

        //49204c6f7665204a617661 split into two characters 49, 20, 4c...
        var i = 0
        while (i < hex.length - 1) {

            //grab the hex in pairs
            val output = hex.substring(i, i + 2)
            //convert hex to decimal
            val decimal = Integer.parseInt(output, 16)
            //convert the decimal to character
            sb.append(decimal.toChar())

            temp.append(decimal)
            i += 2
        }

        return sb.toString()
    }

    private fun setupPermission() {
        val permission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO)

        if (permission != PackageManager.PERMISSION_GRANTED) {
            makeRequest();
        }
    }

    private fun makeRequest() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }
}
