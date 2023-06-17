/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.saferide.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.example.saferide.ProjectConfiguration
import java.util.LinkedList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit
import com.example.saferide.cameraInference.PersonClassifier
import com.example.saferide.databinding.FragmentCameraBinding
import org.tensorflow.lite.task.vision.detector.Detection
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import android.os.Handler
import android.os.Looper

class CameraFragment : Fragment(), PersonClassifier.DetectorListener {
    private val TAG = "CameraFragment"

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!
    
    private lateinit var personView: TextView
    private lateinit var resetButton: Button
    private lateinit var switchButton: Button

    private lateinit var personClassifier: PersonClassifier
    private lateinit var bitmapBuffer: Bitmap
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT // default back camera
    private var length: Int = 0
    private var isPaused: Boolean = false
    private lateinit var cameraProvider: ProcessCameraProvider
    private var isCameraOn: Boolean = false
    private var mustTurnOn: Boolean = false
    private lateinit var detectedTime: Date
    private var isDetected: Boolean = false

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
        Log.d(TAG, "View destroyed")

        // Shut down our background executor
        cameraExecutor.shutdown()

    }


    override fun onCreate (savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setFragmentResultListener("accelKey") { requestKey, bundle ->
            val detectedAudio = bundle.getBoolean("bundleKey")
            // If camera is off and audio has been detected, turn on camera to look for a person
            if(detectedAudio && !isCameraOn) {
                if (!isPaused) {
                    // Start camera after 2 seconds
                    Handler(Looper.getMainLooper()).postDelayed({
                        setUpCamera()
                    }, 2000)

                } else {
                    Log.d(TAG, "App is paused, must turn on camera on entry")
                    mustTurnOn = true
                }
            }
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "Paused")
        isPaused = true
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Resuming")
        if (mustTurnOn) {
            setUpCamera()
        }
        isPaused = false
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "View has been created")

        personClassifier = PersonClassifier()
        personClassifier.initialize(requireContext())
        personClassifier.setDetectorListener(this)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
//        fragmentCameraBinding.viewFinder.post {
//            // Set up the camera and its use cases
//            setUpCamera()
//        }
        personView = fragmentCameraBinding.PersonView

        // Setup button to reset the camera
        resetButton = fragmentCameraBinding.ResetButton
        resetButton.setOnClickListener {
            disableCamera()
            mustTurnOn = false
            personView.text = "NO PERSON"
            personView.setBackgroundColor(ProjectConfiguration.idleBackgroundColor)
            personView.setTextColor(ProjectConfiguration.idleTextColor)
            setFragmentResult("resetKey", bundleOf("bundleKey" to true))
        }
        switchButton = fragmentCameraBinding.SwitchButton
        switchButton.setOnClickListener {
            // Only if the camera is actually on
            if (isCameraOn) {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                // rebind camera
                val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
                cameraProviderFuture.addListener(
                    {
                        // CameraProvider
                        cameraProvider = cameraProviderFuture.get()
                        fragmentCameraBinding.overlay.setViewFinder(true)
                        // Build and bind the camera use cases
                        bindCameraUseCases(cameraProvider)
                    },
                    ContextCompat.getMainExecutor(requireContext())
                )
            }
        }

    }

    // Start CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        Log.d(TAG, "Setting up camera")
        // Wait 2 seconds before starting camera
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()
                fragmentCameraBinding.overlay.setViewFinder(true)
                // Build and bind the camera use cases
                bindCameraUseCases(cameraProvider)

            },
            ContextCompat.getMainExecutor(requireContext())
        )
        isCameraOn = true
    }

    // Disable the camera by unbinding all
    private fun disableCamera() {
        Log.d(TAG, "Disabling camera")
        val cameraProviderFuture = ProcessCameraProvider.getInstance((requireContext()))

        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
                fragmentCameraBinding.overlay.setViewFinder(false)
                fragmentCameraBinding.overlay.invalidate()
            },
            ContextCompat.getMainExecutor((requireContext()))
        )

        isCameraOn = false
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {

        // CameraSelector - makes assumption that we're only using the back camera
        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(lensFacing).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview =
            Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .build()
        // Attach the viewfinder's surface provider to preview use case
        preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)


        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
        // The analyzer can then be assigned to the instance
        imageAnalyzer!!.setAnalyzer(cameraExecutor) { image -> detectObjects(image) }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = fragmentCameraBinding.viewFinder.display.rotation
    }

    private fun detectObjects(image: ImageProxy) {
        if (!::bitmapBuffer.isInitialized) {
            // The image rotation and RGB image buffer are initialized only once
            // the analyzer has started running
            bitmapBuffer = Bitmap.createBitmap(
                image.width,
                image.height,
                Bitmap.Config.ARGB_8888
            )
        }
        // Copy out RGB bits to the shared bitmap buffer
        image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }
        val imageRotation = image.imageInfo.rotationDegrees

        // Pass Bitmap and rotation to the object detector helper for processing and detection
        personClassifier.detect(bitmapBuffer, imageRotation)
    }
    private fun detectionCounter(first: Boolean) {
        // if a person has just been detected, set the detectedTime variable
        if (first) {
            detectedTime = Calendar.getInstance().time
            Log.d(TAG, "First detected at ${TimeUnit.MILLISECONDS.toSeconds(detectedTime.time)}")
        } // If a person has been detected, see if 2 seconds have passed
        else {
            val curTime = Calendar.getInstance().time
            val diff = TimeUnit.MILLISECONDS.toSeconds(curTime.time - detectedTime.time)
            Log.d(TAG, "Consecutive detection for $diff")
            if (diff >= 2) {
                // If a person was detected for 2 seconds, reset everything
                setFragmentResult("stopKey", bundleOf("bundleKey" to true))
                disableCamera()
                personView.text = "NO PERSON"
                personView.setBackgroundColor(ProjectConfiguration.idleBackgroundColor)
                personView.setTextColor(ProjectConfiguration.idleTextColor)
            }
        }
    }
    // Update UI after objects have been detected. Extracts original image height/width
    // to scale and place bounding boxes properly through OverlayView
    override fun onObjectDetectionResults(
        results: MutableList<Detection>?,
        inferenceTime: Long,
        imageHeight: Int,
        imageWidth: Int
    ) {
        // Only if the camera is actually on
        if (isCameraOn) {
            activity?.runOnUiThread {
                // Pass necessary information to OverlayView for drawing on the canvas
                fragmentCameraBinding.overlay.setResults(
                    results ?: LinkedList<Detection>(),
                    imageHeight,
                    imageWidth
                )
                // find at least one bounding box of the person
                val isPersonDetected: Boolean =
                    results!!.find { it.categories[0].label == "person" } != null

                if (isPersonDetected && !isPaused) {
                    personView.text = "PERSON"
                    personView.setBackgroundColor(ProjectConfiguration.activeBackgroundColor)
                    personView.setTextColor(ProjectConfiguration.activeTextColor)
                    // If first time detecting a person, indicate it
                    if (!isDetected) {
                        detectionCounter(true)
                        isDetected = true
                    } else {
                        detectionCounter(false)
                    }

                } else if (!isPaused) {
                    personView.text = "NO PERSON"
                    personView.setBackgroundColor(ProjectConfiguration.idleBackgroundColor)
                    personView.setTextColor(ProjectConfiguration.idleTextColor)
                    // If a person isn't detected, reset
                    isDetected = false
                }

                // Force a redraw
                fragmentCameraBinding.overlay.invalidate()
            }
        }
    }

    override fun onObjectDetectionError(error: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }
}
