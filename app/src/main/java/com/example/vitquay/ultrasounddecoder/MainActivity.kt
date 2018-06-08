package com.example.vitquay.ultrasounddecoder

import android.Manifest
import android.content.pm.PackageManager
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

class MainActivity : AppCompatActivity() {

    var lastTimeStringDecoded:Long = 0
    var prevDecodedString: String = ""
    var soundSampler: SoundSampler? = null
    var fftThread: Thread? = null
    var fftArray: DoubleArray? = null
    var handler: Handler = Handler(Looper.getMainLooper())
    var headerLength:Int = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupPermission()

        resetButton.setOnClickListener {
            peakTextView.text = ""
            prevDecodedString = ""
            lastTimeStringDecoded = System.currentTimeMillis()
            headerLength = 2
        }

        soundSampler = SoundSampler(44100, 44100)
        try {
            soundSampler!!.init()
        } catch (e: Exception) {
            Toast.makeText(this, "Sound Sample Init() Failed", Toast.LENGTH_LONG).show()
        }

        fftArray = DoubleArray(soundSampler!!.bufferSize)

        val doubleFFT_1D = DoubleFFT_1D(soundSampler!!.bufferSize)

        fftThread = object : Thread() {
            override fun run() {
                while (true) {

                    /* Interrupt handler so that no resources is leaked*/
                    if (Thread.currentThread().isInterrupted) {
                        return
                    }

                    shortArrayToDoubleArray(fftArray!!, soundSampler!!.buffer)

                    doubleFFT_1D.realForward(fftArray)

                    handler.post{
                        calculatePeak(18000, 21000, 1)
                    }

                    try {
                        sleep(10)
                    } catch (e: InterruptedException) {
                        return
                    }
                }
            }
        }
        fftThread!!.start()
    }

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

        var intCharArray = IntArray(peakCount)
        lateinit var pair:IntegerPair

        if (values[0].magnitude > 15) {

            decodedString = when (values[0].frequency) {
                19000 -> "0"
                20000 -> "1"
                else -> ""
            }

        }

        /*---------------------------------------------*/

        /* Compares average with checksum, update string if necessary */
        handler.post {
            if (decodedString != "" && System.currentTimeMillis() - lastTimeStringDecoded > 1000) {
                headerLength--
                Log.d("ADFSDF", values[0].magnitude.toString() + " " + values[0].frequency.toString())
                if (headerLength < 0) {
                peakTextView.text = peakTextView.text.toString() + decodedString
                }
                lastTimeStringDecoded = System.currentTimeMillis()
                Log.d("ADFDF", lastTimeStringDecoded.toString())
                prevDecodedString = decodedString
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

    class IntegerPair(var coeff1: Int, var coeff2: Int) {

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

    private fun setupPermission() {
        val permission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO)

        if (permission != PackageManager.PERMISSION_GRANTED) {
//            Toast.makeText(this, "Failed to get audio recording permission", Toast.LENGTH_LONG).show()
            makeRequest();
        }
    }

    private fun makeRequest() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }
}
