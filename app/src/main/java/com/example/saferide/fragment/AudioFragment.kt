package com.example.saferide.fragment

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.saferide.ProjectConfiguration
import com.example.saferide.audioInference.EnvClassifier
import com.example.saferide.databinding.FragmentAudioBinding
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import android.hardware.SensorManager
import android.hardware.SensorEventListener
import android.hardware.SensorEvent
import android.hardware.Sensor
import androidx.core.os.bundleOf
import android.media.MediaPlayer
import com.example.saferide.R
import com.example.saferide.audioInference.EnvClassifier.Companion.DEFAULT_REFRESH_INTERVAL_MS
import com.example.saferide.audioInference.EnvClassifier.Companion.LONG_REFRESH_INTERVAL_MS


class AudioFragment: Fragment(), EnvClassifier.DetectorListener {
    private val TAG = "AudioFragment"

    private var _fragmentAudioBinding: FragmentAudioBinding? = null
    lateinit var sm: SensorManager
    private lateinit var accelSensor: Sensor
    private lateinit var gravSensor: Sensor
    private var isMotionSensing: Boolean = false
    private var counter: Int = 0
    private var alarmSound: MediaPlayer? = null
    private var isSoundPlaying: Boolean = false


    var x:Float = 0f
    var y:Float = 0f
    var z:Float = 0f
    var x_grav:Float = 0f
    var y_grav:Float = 0f
    var z_grav:Float = 0f

    // Event listener for accelerometer
    private var accelSensorEventListener: SensorEventListener? = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            return
        }
        // When new sensor data is available
        override fun onSensorChanged(event: SensorEvent?) {
            // Extract linear acceleration data (without gravity)
            if (event!!.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
                x = event.values[0]
                y = event.values[1]
                z = event.values[2]

            // Extract gravity data
            } else if (event.sensor.type == Sensor.TYPE_GRAVITY) {
                x_grav = event.values[0]
                y_grav = event.values[1]
                z_grav = event.values[2]
            }
            // Calculate vertical acceleration using linear acceleration and gravity
            val vertical_accel = (x * x_grav / 9.81) + (y * y_grav / 9.81) +  (z * z_grav /9.81)

            // If vertical acceleration is below -9, then detect as falling, notify camera fragment
            if ((vertical_accel < -9)) {
                accelView.text = "FALL DETECTED"
                accelView.setBackgroundColor(ProjectConfiguration.activeBackgroundColor)
                accelView.setTextColor(ProjectConfiguration.activeTextColor)
                setFragmentResult("accelKey", bundleOf("bundleKey" to true))
                // Play alarm sound
                if (!isSoundPlaying && (alarmSound != null)) {
                    alarmSound!!.start()
                    alarmSound!!.isLooping = true
                    isSoundPlaying = true
                }
                // Stop audio inference and accelerometer
                envClassifier.stopInferencing()
                uninstallSensor()
            } else {
                accelView.text = "NO FALL"
                accelView.setBackgroundColor(ProjectConfiguration.idleBackgroundColor)
                accelView.setTextColor(ProjectConfiguration.idleTextColor)
            }
            Log.d(TAG, "Vertical Accel = $vertical_accel")
        }
    }

    private val fragmentAudioBinding
        get() = _fragmentAudioBinding!!

    // classifiers
    lateinit var envClassifier: EnvClassifier
    private var isInferencing: Boolean = false

    // views
    lateinit var envView: TextView
    lateinit var accelView: TextView

    var isPaused: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentAudioBinding = FragmentAudioBinding.inflate(inflater, container, false)

        return fragmentAudioBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        envView = fragmentAudioBinding.EnvView
        accelView = fragmentAudioBinding.AccelView

        envClassifier = EnvClassifier()
        envClassifier.initialize(requireContext())
        envClassifier.setDetectorListener(this)
        envClassifier.startInferencing(DEFAULT_REFRESH_INTERVAL_MS)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "Releasing Media Player resources")
        // Release media player resources
        alarmSound!!.release()
        alarmSound = null
    }

    /* Uninstall the motion sensors (stops sensing) and set the flag accordingly */
    private fun uninstallSensor() {
        if (isMotionSensing) {
            sm.unregisterListener(accelSensorEventListener)
            isMotionSensing = false
        }
    }

    /* Install the motion sensors and set the flag accordingly */
    private fun installSensor() {
        if (!isMotionSensing) {
            sm.registerListener(
                accelSensorEventListener,
                accelSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            sm.registerListener(
                accelSensorEventListener,
                gravSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            isMotionSensing = true
        }
    }
    override fun onPause() {
        super.onPause()
        //envClassifier.stopInferencing()
        //uninstallSensor()
        //isInferencing = false
        isPaused = true
    }

    override fun onResume() {
        super.onResume()
        //envClassifier.startInferencing()
        // Install sensor to even listener
        // installSensor()
        isPaused = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Get accelerometer sensor
        sm = requireActivity().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gravSensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)

        // Load the sound file
        alarmSound = MediaPlayer.create(context, R.raw.alarmsound)

        // Reset everything if a person was detected
        setFragmentResultListener("stopKey") { requestKey, bundle ->
            val detectedPerson = bundle.getBoolean("bundleKey")
            // Only do stuff if the fragment isn't paused
            if (!isPaused && detectedPerson) {
                // Stop the alarm sound from playing
                if (isSoundPlaying && (alarmSound != null)) {
                    alarmSound!!.pause()
                    isSoundPlaying = false
                }
                // Stop accelerometer
                if (isMotionSensing) {
                    uninstallSensor()
                    Log.d(TAG, "uninstalling sensors")
                }
                // Reset audio inference
                envClassifier.stopInferencing()
                envClassifier.startInferencing(DEFAULT_REFRESH_INTERVAL_MS)

                // Reset the UI
                envView.text = "NO VEHICLES"
                envView.setBackgroundColor(ProjectConfiguration.idleBackgroundColor)
                envView.setTextColor(ProjectConfiguration.idleTextColor)

                accelView.text = "NO FALL"
                accelView.setBackgroundColor(ProjectConfiguration.idleBackgroundColor)
                accelView.setTextColor(ProjectConfiguration.idleTextColor)
            }
        }

        setFragmentResultListener("resetKey") { requestKey, bundle ->
            val reset = bundle.getBoolean("bundleKey")
            // Only do stuff if the fragment isn't paused
            if (reset) {
                // Stop the alarm sound from playing
                if (isSoundPlaying && (alarmSound != null)) {
                    alarmSound!!.pause()
                    isSoundPlaying = false
                }
                // Stop accelerometer
                if (isMotionSensing) {
                    uninstallSensor()
                    Log.d(TAG, "uninstalling sensors")
                }
                // Reset audio inference
                envClassifier.stopInferencing()
                envClassifier.startInferencing(DEFAULT_REFRESH_INTERVAL_MS)

                // Reset the UI
                envView.text = "NO VEHICLES"
                envView.setBackgroundColor(ProjectConfiguration.idleBackgroundColor)
                envView.setTextColor(ProjectConfiguration.idleTextColor)

                accelView.text = "NO FALL"
                accelView.setBackgroundColor(ProjectConfiguration.idleBackgroundColor)
                accelView.setTextColor(ProjectConfiguration.idleTextColor)
            }
        }
    }

    override fun onResults(score: Float) {
        activity?.runOnUiThread {
            if (score > EnvClassifier.THRESHOLD) {
                envView.text = "VEHICLE DETECTED"
                envView.setBackgroundColor(ProjectConfiguration.activeBackgroundColor)
                envView.setTextColor(ProjectConfiguration.activeTextColor)
                // Reset counter when sound is detected
                counter = 0
                // If sensor wasn't on, turn it on
                if (!isMotionSensing) {
                    // Switch audio inference to a longer interval
                    envClassifier.stopInferencing()
                    envClassifier.startInferencing(LONG_REFRESH_INTERVAL_MS)
                    // Begin accelerometer sensing
                    installSensor()
                }
            } else {
                envView.text = "NO VEHICLES"
                envView.setBackgroundColor(ProjectConfiguration.idleBackgroundColor)
                envView.setTextColor(ProjectConfiguration.idleTextColor)
                // If sensor was on
                if (isMotionSensing) {
                    // Count how many intervals it was on for
                    counter++
                    // If sound hasn't been detected for 303 intervals
                    // (approx. 30 seconds) consecutively, then uninstall sensor
                    if (counter > 303) {
                        uninstallSensor()
                        // Switch audio inference to default interval
                        envClassifier.stopInferencing()
                        envClassifier.startInferencing(DEFAULT_REFRESH_INTERVAL_MS)
                    }
                }
            }
        }
    }
}