package com.nutrilens.ai.ui.camera

import android.Manifest
import com.nutrilens.ai.vision.BarcodeReader
import com.nutrilens.ai.util.navigateSlide
import com.nutrilens.ai.data.repo.ProductRepository
import com.nutrilens.ai.data.remote.ProductLookup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.isInvisible
import androidx.core.os.bundleOf
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.EditText
import android.text.InputType
import android.text.InputFilter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.nutrilens.ai.R
import com.nutrilens.ai.ai.FoodClassifier
import com.nutrilens.ai.databinding.FragmentCameraBinding
import com.nutrilens.ai.ui.common.ScanViewModel
import com.nutrilens.ai.util.Haptic
import com.nutrilens.ai.util.fadeVisible
import com.nutrilens.ai.util.haptic
import com.nutrilens.ai.util.ImageUtils
import com.nutrilens.ai.util.confidencePercent
import com.nutrilens.ai.util.foodName
import com.nutrilens.ai.vision.ImageAnalyzer
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Food scanning module: CameraX preview + capture (or pick an image from the gallery), with a
 * live hint from the model and the OpenCV photo check while framing.
 */
@AndroidEntryPoint
class CameraFragment : Fragment(R.layout.fragment_camera) {

    private val scanViewModel: ScanViewModel by activityViewModels()

    @Inject lateinit var classifier: FoodClassifier
    @Inject lateinit var analyzer: ImageAnalyzer
    @Inject lateinit var products: ProductRepository

    /** Barcode mode reads EAN/UPC codes from the preview instead of recognising a dish. */
    private var barcodeMode = false
    /** True from a detected barcode until the user is back to scanning (one lookup at a time). */
    private val lookingUp = AtomicBoolean(false)

    /** Live preview recognition: at most one frame in flight, about one per second. */
    private val liveBusy = AtomicBoolean(false)
    private var lastLive = 0L
    private var analysisExecutor: ExecutorService? = null

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashOn = false
    private var breathe: ObjectAnimator? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionPanel(true)
        }

    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) importFromGallery(uri)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentCameraBinding.bind(view)

        binding.btnClose.setOnClickListener { findNavController().popBackStack() }
        binding.btnFlash.setOnClickListener { toggleFlash() }
        binding.btnSwitch.setOnClickListener { switchCamera() }
        binding.btnCapture.setOnClickListener { capture() }
        binding.btnGallery.setOnClickListener { openGallery() }
        binding.btnGrant.setOnClickListener { permissionLauncher.launch(Manifest.permission.CAMERA) }
        binding.btnPickInstead.setOnClickListener { openGallery() }
        binding.tabFood.setOnClickListener { setMode(barcode = false) }
        binding.tabBarcode.setOnClickListener { setMode(barcode = true) }
        binding.btnTypeBarcode.setOnClickListener { askBarcodeNumber() }
        setMode(barcode = savedInstanceState?.getBoolean(KEY_BARCODE) ?: (arguments?.getBoolean(ARG_BARCODE) == true), rebind = false)

        breathe = ObjectAnimator.ofPropertyValuesHolder(
            binding.frameCorners,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.7f)
        ).apply {
            duration = 1400
            repeatCount = 5 // three gentle pulses to draw the eye, then still
            repeatMode = ValueAnimator.REVERSE
            start()
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionPanel(show: Boolean) {
        _binding?.permissionPanel?.isVisible = show
    }

    private fun startCamera() {
        showPermissionPanel(false)
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            if (_binding == null) return@addListener
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
            .build()
        imageCapture = capture

        val executor = analysisExecutor ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            // Thin barcode bars need more pixels than the dish hint does.
            .setTargetResolution(if (barcodeMode) android.util.Size(1280, 720) else android.util.Size(640, 480))
            .build()
            .also { it.setAnalyzer(executor, ::analyzeFrame) }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(viewLifecycleOwner, selector, preview, capture, analysis)
        } catch (e: Exception) {
            toast(getString(R.string.camera_unavailable))
        }
    }

    /**
     * Runs the same OpenCV check and classifier on a preview frame and shows a live hint, so
     * the user can fix light/blur or framing before taking the photo. Frames are dropped while
     * one is being processed.
     */
    private fun analyzeFrame(proxy: ImageProxy) {
        if (barcodeMode) return scanBarcode(proxy)
        val now = SystemClock.elapsedRealtime()
        if (now - lastLive < LIVE_INTERVAL_MS || !liveBusy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        lastLive = now
        val frame = try {
            val raw = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            if (rotation == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true).also { raw.recycle() }
        } catch (e: Exception) {
            liveBusy.set(false)
            return
        } finally {
            proxy.close()
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            try {
                val quality = analyzer.analyze(frame)
                val issue = quality?.issues?.firstOrNull()
                val text = if (issue != null) {
                    getString(issue.live)
                } else {
                    val forModel = analyzer.enhanceForModel(frame, quality)
                    val p = classifier.classify(forModel)
                    if (forModel !== frame) forModel.recycle()
                    if (p.confidence >= ScanViewModel.LOW_CONFIDENCE) {
                        getString(R.string.live_looks_like_fmt, requireContext().foodName(p.label), confidencePercent(p.confidence) ?: 0)
                    } else null
                }
                withContext(Dispatchers.Main) {
                    _binding?.tvLive?.let {
                        if (text != null && it.text != text) it.text = text
                        it.fadeVisible(text != null)
                    }
                }
            } catch (_: Exception) {
                // No model, or the camera went away: just no live hint.
            } finally {
                frame.recycle()
                liveBusy.set(false)
            }
        }
    }

    /** Reads a barcode from the frame; the first one found is looked up (others are ignored meanwhile). */
    private fun scanBarcode(proxy: ImageProxy) {
        if (lookingUp.get()) { proxy.close(); return }
        val code = try {
            val plane = proxy.planes[0]
            BarcodeReader.decode(BarcodeReader.lumaFromRgba(plane.buffer, proxy.width, proxy.height, plane.rowStride), proxy.width, proxy.height)
        } catch (e: Exception) {
            null
        } finally {
            proxy.close()
        }
        if (code != null) activity?.runOnUiThread { onBarcode(code) }
    }

    private fun onBarcode(code: String) {
        val b = _binding ?: return
        if (!lookingUp.compareAndSet(false, true)) return
        b.root.haptic(Haptic.CONFIRM)
        b.tvLive.text = getString(R.string.looking_up_fmt, code)
        b.tvLive.fadeVisible(true)
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = products.lookup(code)) {
                is ProductLookup.Found -> navigateSlide(R.id.foodDetailFragment, bundleOf("label" to result.food.label))
                ProductLookup.NotFound -> lookupProblem(getString(R.string.barcode_not_found_title), getString(R.string.barcode_not_found_msg))
                is ProductLookup.NoNutrition -> lookupProblem(null, getString(R.string.barcode_no_nutrition_fmt, result.name))
                ProductLookup.Offline -> lookupProblem(getString(R.string.barcode_offline_title), getString(R.string.barcode_offline_msg), offline = true)
                ProductLookup.Unavailable -> lookupProblem(null, getString(R.string.barcode_unavailable))
            }
        }
    }

    /** Explains why a barcode can't be logged, with the most useful next step. */
    private fun lookupProblem(title: String?, message: String, offline: Boolean = false) {
        val b = _binding ?: return
        b.tvLive.fadeVisible(false)
        MaterialAlertDialogBuilder(requireContext())
            .apply { if (title != null) setTitle(title) }
            .setMessage(message)
            .setPositiveButton(if (offline) R.string.mode_food else R.string.find_food) { _, _ ->
                if (offline) setMode(barcode = false) else navigateSlide(R.id.foodLibraryFragment)
            }
            .setNegativeButton(R.string.scan_again, null)
            .setOnDismissListener { lookingUp.set(false) }
            .show()
    }

    /** For a worn or curved barcode: type the digits printed under the bars. */
    private fun askBarcodeNumber() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(14))
            hint = getString(R.string.barcode_dialog_hint)
        }
        val box = FrameLayout(requireContext()).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.barcode_dialog_title)
            .setView(box)
            .setPositiveButton(R.string.look_up) { _, _ ->
                val code = input.text.toString().trim()
                if (BarcodeReader.isValid(code)) onBarcode(code) else toast(getString(R.string.barcode_invalid))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setMode(barcode: Boolean, rebind: Boolean = true) {
        val b = _binding ?: return
        val changed = barcode != barcodeMode
        barcodeMode = barcode
        b.tabFood.isSelected = !barcode
        b.tabBarcode.isSelected = barcode
        b.tvHint.setText(if (barcode) R.string.barcode_hint else R.string.fit_one_dish_inside_the_frame_good_lig)
        b.btnCapture.isVisible = !barcode
        b.btnTypeBarcode.isVisible = barcode
        b.btnGallery.isInvisible = barcode
        b.btnSwitch.isInvisible = barcode
        b.tvLive.isVisible = false
        val density = resources.displayMetrics.density
        b.frameCorners.layoutParams = b.frameCorners.layoutParams.apply {
            width = ((if (barcode) 300 else 272) * density).toInt()
            height = ((if (barcode) 170 else 272) * density).toInt()
        }
        b.frameCorners.scaleType = ImageView.ScaleType.FIT_XY
        lookingUp.set(false)
        if (changed && rebind) {
            b.root.haptic(Haptic.TICK)
            if (lensFacing != CameraSelector.LENS_FACING_BACK) lensFacing = CameraSelector.LENS_FACING_BACK
            bindCamera()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_BARCODE, barcodeMode)
    }

    /** Shutter feel: the button dips, the screen flashes white, a firm haptic tick. */
    private fun shutterFeedback() {
        binding.btnCapture.haptic(Haptic.CONFIRM)
        binding.btnCapture.animate().scaleX(0.86f).scaleY(0.86f).setDuration(90)
            .withEndAction { _binding?.btnCapture?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(160)?.start() }.start()
        binding.shutterFlash.alpha = 0.85f
        binding.shutterFlash.animate().alpha(0f).setDuration(260).start()
    }

    private fun toggleFlash() {
        flashOn = !flashOn
        imageCapture?.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        binding.btnFlash.alpha = if (flashOn) 1f else 0.6f
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCamera()
    }

    private fun capture() {
        val capture = imageCapture ?: return
        binding.btnCapture.isEnabled = false
        shutterFeedback()
        val file = ImageUtils.createMealFile(requireContext())
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    startAnalysis(Uri.fromFile(file))
                }

                override fun onError(exception: ImageCaptureException) {
                    _binding?.btnCapture?.isEnabled = true
                    toast(getString(R.string.capture_failed))
                }
            }
        )
    }

    private fun openGallery() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun importFromGallery(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val local = ImageUtils.copyToInternal(requireContext(), uri)
                startAnalysis(local)
            } catch (e: Exception) {
                toast(getString(R.string.open_image_failed))
            }
        }
    }

    private fun startAnalysis(uri: Uri) {
        if (_binding == null) return
        scanViewModel.analyze(uri)
        findNavController().navigate(R.id.action_camera_to_result)
    }

    private fun toast(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        analysisExecutor?.shutdown()
        super.onDestroy()
    }

    override fun onDestroyView() {
        breathe?.cancel()
        breathe = null
        cameraProvider?.unbindAll()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val LIVE_INTERVAL_MS = 900L
        private const val KEY_BARCODE = "barcode_mode"
        /** Open the camera straight in barcode mode. */
        const val ARG_BARCODE = "barcode"
    }
}
