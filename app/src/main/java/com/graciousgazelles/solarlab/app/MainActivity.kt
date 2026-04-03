package com.graciousgazelles.solarlab.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.graciousgazelles.solarlab.app.databinding.ActivityMainBinding
import com.graciousgazelles.solarlab.feature.lab.LabFrame
import com.graciousgazelles.solarlab.feature.lab.LabFrameListener
import com.graciousgazelles.solarlab.feature.lab.LabSession
import com.graciousgazelles.solarlab.render.core.RenderBackend
import com.graciousgazelles.solarlab.render.core.RenderBackendStatus

class MainActivity : AppCompatActivity(), LabFrameListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var session: LabSession

    private var resumeSimulationOnForeground: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = LabSession.createDefault(context = this, listener = this)

        binding.renderHost.setOnBackendStatusChangedListener(::onBackendStatusChanged)

        binding.buttonStartPause.setOnClickListener {
            if (session.isRunning()) {
                session.pause()
                binding.buttonStartPause.text = getString(R.string.action_start)
            } else {
                session.start()
                binding.buttonStartPause.text = getString(R.string.action_pause)
            }
        }

        binding.buttonStep.setOnClickListener {
            session.stepOnce()
        }

        binding.buttonReset.setOnClickListener {
            session.resetDefault()
            binding.renderHost.resetCamera()
            binding.buttonStartPause.text = getString(R.string.action_start)
        }

        binding.buttonBackend.setOnClickListener {
            binding.renderHost.cycleBackendPreference()
            updateBackendButtonText(binding.renderHost.backendPreference())
        }

        updateBackendButtonText(binding.renderHost.backendPreference())

        session.dispatchCurrentFrame()
        session.start()
        binding.buttonStartPause.text = getString(R.string.action_pause)
    }

    override fun onLabFrame(frame: LabFrame) {
        binding.renderHost.submitSnapshot(frame.snapshot)
        binding.textDiagnostics.text = frame.diagnostics.toPrettyString()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.renderHost.onHostResume()
        }
        if (::session.isInitialized && resumeSimulationOnForeground) {
            session.start()
            binding.buttonStartPause.text = getString(R.string.action_pause)
        }
    }

    override fun onPause() {
        if (::session.isInitialized) {
            resumeSimulationOnForeground = session.isRunning()
            session.pause()
            binding.buttonStartPause.text = getString(R.string.action_start)
        }
        if (::binding.isInitialized) {
            binding.renderHost.onHostPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::session.isInitialized) {
            session.release()
        }
        if (::binding.isInitialized) {
            binding.renderHost.release()
        }
        super.onDestroy()
    }

    private fun onBackendStatusChanged(status: RenderBackendStatus) {
        binding.textBackend.text = status.message
        updateBackendButtonText(status.requested)
    }

    private fun updateBackendButtonText(requested: RenderBackend) {
        binding.buttonBackend.text = when (requested) {
            RenderBackend.AUTO -> getString(R.string.action_backend_auto)
            RenderBackend.VULKAN -> getString(R.string.action_backend_vulkan)
            RenderBackend.OPENGL -> getString(R.string.action_backend_opengl)
        }
    }
}
