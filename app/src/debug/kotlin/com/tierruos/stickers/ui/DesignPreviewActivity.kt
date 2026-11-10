package com.tierruos.stickers.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.tierruos.stickers.databinding.ActivityDesignPreviewBinding

/** Debug-only XML catalog and non-network launch recorder for instrumentation. */
class DesignPreviewActivity : Activity() {
    lateinit var binding: ActivityDesignPreviewBinding
        private set
    var recordedIntent: Intent? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDesignPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        binding.previewPrimary.setOnClickListener {
            binding.previewPrimary.isEnabled = false
            binding.previewLoading.visibility = View.VISIBLE
            binding.previewPrimary.postDelayed({
                binding.previewLoading.visibility = View.GONE
                binding.previewPrimary.isEnabled = true
            }, 1_500)
        }
    }

    override fun startActivity(intent: Intent) { recordedIntent = Intent(intent) }
}
