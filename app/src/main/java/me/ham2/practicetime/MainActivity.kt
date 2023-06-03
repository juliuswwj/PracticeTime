package me.ham2.practicetime

import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Environment
import android.os.Looper
import android.text.format.DateFormat
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import java.util.*
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.tensorflow.lite.support.audio.TensorAudio
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.task.core.BaseOptions
import me.ham2.practicetime.ui.theme.PracticeTimeTheme
import java.io.File
import java.io.FileWriter
import java.io.IOException

class MainActivity : ComponentActivity() {
    companion object {
        const val MODEL_NAME = "lite-model_yamnet_classification_tflite_1.tflite"
        const val MAX_IDLE = 300
    }
    private lateinit var classifier: AudioClassifier
    private lateinit var tensorAudio: TensorAudio
    private lateinit var recorder: AudioRecord
    private var music = 0
    private var total = 0
    private var idle = 0
    private var ticks = 0
    private var interval: Long = 0
    private var executor: ScheduledThreadPoolExecutor? = null
    private var updateHandler = Handler(Looper.getMainLooper())
    private var logHandler = Handler(Looper.getMainLooper())
    private val classifyRunnable = Runnable {
        classifyAudio()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)

        val btnStart = findViewById<Button>(R.id.btnStart)

        btnStart.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.RECORD_AUDIO),
                    1
                )
            } else {
                if(executor == null)
                    start();
                else
                    stop();

                if(executor == null) {
                    btnStart.setText(R.string.start)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    btnStart.setText(R.string.stop)
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        btnStart.callOnClick()
    }


    private fun start() {
        val baseOptionsBuilder = BaseOptions.builder()
            .setNumThreads(2)

        val options = AudioClassifier.AudioClassifierOptions.builder()
            .setScoreThreshold(0.5f)
            .setMaxResults(2)
            .setBaseOptions(baseOptionsBuilder.build())
            .build()

        try {
            // Create the classifier and required supporting objects
            classifier = AudioClassifier.createFromFileAndOptions(baseContext, MODEL_NAME, options)
            tensorAudio = classifier.createInputTensorAudio()
            recorder = classifier.createAudioRecord()
        } catch (e: Exception) {
            // IllegalStateException
            log("TFLite failed to load with error: " + e.message)
            return
        }

        recorder.startRecording()

        val lengthInMilliSeconds = ((classifier.requiredInputBufferSize * 1.0f) /
                classifier.requiredTensorAudioFormat.sampleRate) * 1000

        interval = (lengthInMilliSeconds * 0.5f).toLong()
        executor = ScheduledThreadPoolExecutor(1)
        executor!!.scheduleAtFixedRate(
            classifyRunnable,
            0,
            interval,
            TimeUnit.MILLISECONDS)

        log("app start " + recorder.state)
        update()
    }

    private fun stop() {
        recorder.stop()
        executor!!.shutdownNow()
        executor = null
        updateHandler.removeCallbacksAndMessages(null)
        log("app stop. total=%d idle=%d".format(total, idle))
        total = 0
        idle = 0
    }

    private fun update() {
        val txtTotal = findViewById<TextView>(R.id.txtTotal)
        val txtIdle = findViewById<TextView>(R.id.txtIdle)
        txtTotal.text = "Total = %d:%02d".format(total/60, total%60)
        txtIdle.text = "Idle = %d:%02d".format(idle/60, idle%60)

        if(music > 0) {
            music -= 1
            total += 1
            idle = 0
        } else {
            idle += 1
            if(idle >= MAX_IDLE) {
                if(total == 0 && ticks >= MAX_IDLE) {
                    val btnStart = findViewById<Button>(R.id.btnStart)
                    btnStart.callOnClick() // will call stop()
                    return
                }
                total = 0
                idle = 0
            }
        }
        ticks += 1
        if(ticks % (MAX_IDLE/3) == 0) {
            log("total=%d idle=%d".format(total, idle))
        }

        if(executor != null) {
            updateHandler.postDelayed({
                update()
            }, 1000)
        }
    }

    private fun classifyAudio() {
        tensorAudio.load(recorder)
        val output = classifier.classify(tensorAudio)
        if(output[0].categories.size > 0 && output[0].categories[0].label == "Music"){
            music = 5
        }
    }

    private fun log(txt: String) {
        val line = "[%s] %s\n".format(DateFormat.format("yyyy-MM-dd kk:mm:ss", Date()), txt)
        logHandler.post(Runnable {
            val txtLog = findViewById<TextView>(R.id.txtLog)
            var len = txtLog.text.length
            if(len > 1000) len = 1000
            txtLog.text = line + txtLog.text.substring(0, len)

            val logFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "practicetime.txt")
            try {
                if (!logFile.exists()) {
                    logFile.createNewFile()
                    logFile.setReadable(true, false)
                }
                FileWriter(logFile, true).use {
                    it.append(line)
                }
            } catch (e: IOException) {
            }
        })
    }

 }

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
            text = "Hello $name!",
            modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    PracticeTimeTheme {
        Greeting("Android")
    }
}