package ceui.pixiv.ui.translation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Local
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TabCellHolder
import ceui.pixiv.ui.common.setUpCustomAdapter
import ceui.pixiv.ui.common.viewBinding

class TranslationFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val adapter by lazy { setUpCustomAdapter(binding, ListMode.VERTICAL_TABCELL) }

    private val modelPathLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                requireActivity().contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                Shaft.sSettings.llmModelPath = it.toString()
                Local.setSettings(Shaft.sSettings)
                refreshList()
            } catch (e: SecurityException) {
                e.printStackTrace()
                Toast.makeText(requireContext(), getString(R.string.access_denied), Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val ON_DEVICE_INFERENCE = 0
        private const val LLM_API = 1
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbarLayout.naviTitle.text = getString(R.string.translation_settings)
        adapter
        ObjectPool.get<User>(SessionManager.loggedInUid).observe(viewLifecycleOwner) {
            refreshList()
        }
    }

    private fun getFileNameFromUri(context: Context, uriString: String): String {
        if (uriString.isBlank()) {
            return ""
        }
        var fileName = ""
        try {
            val uri = Uri.parse(uriString)
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (fileName.isBlank()) {
            fileName = uriString.substringAfterLast('/')
        }
        return fileName
    }

    private fun refreshList() {
        val settings = Shaft.sSettings
        val currentMethod = settings.translationMethod
        val methodDisplay = when (currentMethod) {
            ON_DEVICE_INFERENCE -> getString(R.string.on_device_inference)
            LLM_API -> getString(R.string.local_llm_api)
            else -> getString(R.string.not_set)
        }
        val currentPrompt = settings.llmPrompt
        val currentIpAddress = settings.llmIpAddress
        val currentModelPath = settings.llmModelPath
        val modelPathDisplay = getFileNameFromUri(requireContext(), currentModelPath)
        val llmTemperature = settings.llmTemperature
        val modelName = settings.modelName
        val splitTextThreshold = settings.splitTextThreshold

        val items = mutableListOf(
            TabCellHolder(
                title = getString(R.string.translation_method),
                secondaryTitle = getString(R.string.translation_method_desc),
                extraInfo = methodDisplay
            ).onItemClick { showTranslationMethodDialog() },

            TabCellHolder(
                title = getString(R.string.llm_prompt),
                secondaryTitle = getString(R.string.llm_prompt_desc),
                extraInfo = currentPrompt.take(30) + if (currentPrompt.length > 30) getString(R.string.ellipsis) else ""
            ).onItemClick { showPromptInputDialog() },

            TabCellHolder(
                title = "Temperature",
                extraInfo = llmTemperature.toString()
            ).onItemClick { showTemperatureInputDialog() },

            TabCellHolder(
                title = getString(R.string.split_threshold),
                secondaryTitle = getString(R.string.split_threshold_desc),
                extraInfo = splitTextThreshold.toString()
            ).onItemClick { showSplitTextThresholdDialog() },

            TabCellHolder(
                title = getString(R.string.local_network_ip_address),
                secondaryTitle = getString(R.string.local_network_ip_address_desc),
                extraInfo = currentIpAddress.ifEmpty { getString(R.string.not_set) }
            ).onItemClick { showIpAddressInputDialog() },

            TabCellHolder(
                title = getString(R.string.llm_model_name),
                secondaryTitle = getString(R.string.llm_model_name_desc),
                extraInfo = modelName.ifEmpty { getString(R.string.not_set) }
            ).onItemClick { showModelNameInputDialog() },

            TabCellHolder(
                title = getString(R.string.model_path),
                secondaryTitle = getString(R.string.model_path_desc),
                extraInfo = modelPathDisplay.ifEmpty { getString(R.string.not_set) }
            ).onItemClick {
                modelPathLauncher.launch(arrayOf("application/octet-stream"))
            }
        )
        adapter.submitList(items)
    }

    private fun showTranslationMethodDialog() {
        val methods = arrayOf(getString(R.string.on_device_inference), getString(R.string.local_llm_api))
        val currentMethodValue = Shaft.sSettings.translationMethod
        val checkedItem = if (currentMethodValue == LLM_API) 1 else 0

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.select_translation_method))
            .setSingleChoiceItems(methods, checkedItem) { dialog, which ->
                val selectedMethod = if (which == 0) ON_DEVICE_INFERENCE else LLM_API
                Shaft.sSettings.translationMethod = selectedMethod
                Local.setSettings(Shaft.sSettings)
                dialog.dismiss()
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPromptInputDialog() {
        val editText = EditText(requireContext()).apply {
            setText(Shaft.sSettings.llmPrompt)
            isSingleLine = false
            minLines = 5
        }
        val container = FrameLayout(requireContext()).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(editText)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.llm_prompt))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Shaft.sSettings.llmPrompt = editText.text.toString()
                Local.setSettings(Shaft.sSettings)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showIpAddressInputDialog() {
        val editText = EditText(requireContext()).apply {
            setText(Shaft.sSettings.llmIpAddress)
            hint = getString(R.string.ip_address_hint)
            isSingleLine = true
        }
        val container = FrameLayout(requireContext()).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(editText)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.local_network_ip_address))
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val inputText = editText.text.toString()
                if (isValidLocalIpAddressAndPort(inputText)) {
                    Shaft.sSettings.llmIpAddress = inputText
                    Local.setSettings(Shaft.sSettings)
                    refreshList()
                    dialog.dismiss()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.invalid_ip_address_format), Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    private fun isValidLocalIpAddressAndPort(input: String): Boolean {
        val parts = input.split(":")
        if (parts.size != 2) return false

        val ip = parts[0]
        val port = parts[1].toIntOrNull()

        val isIpFormatValid = android.util.Patterns.IP_ADDRESS.matcher(ip).matches()
        if (!isIpFormatValid) return false

        val isLocalIp = ip.startsWith("192.168.") ||
                ip.startsWith("10.") ||
                (ip.startsWith("172.") && ip.substringAfter("172.").substringBefore(".").toIntOrNull() in 16..31) ||
                ip == "127.0.0.1"
        if (!isLocalIp) return false

        val isPortValid = port != null && port in 0..65535

        return isPortValid
    }

    private fun showModelNameInputDialog() {
        val editText = EditText(requireContext()).apply {
            setText(Shaft.sSettings.modelName)
            hint = getString(R.string.model_name_hint)
            isSingleLine = true
        }
        val container = FrameLayout(requireContext()).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(editText)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.llm_model_name))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Shaft.sSettings.modelName = editText.text.toString()
                Local.setSettings(Shaft.sSettings)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSplitTextThresholdDialog() {
        val editText = EditText(requireContext()).apply {
            setText(Shaft.sSettings.splitTextThreshold.toString())
            hint = "1500"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val container = FrameLayout(requireContext()).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(editText)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.split_text_threshold_title))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val threshold = editText.text.toString().toIntOrNull() ?: 1500
                Shaft.sSettings.splitTextThreshold = threshold
                Local.setSettings(Shaft.sSettings)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTemperatureInputDialog(){
        val editText = EditText(requireContext()).apply {
            setText(Shaft.sSettings.llmTemperature.toString())
            hint = "0.1"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val container = FrameLayout(requireContext()).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(editText)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Temperature")
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val temperature = editText.text.toString().toDoubleOrNull() ?: 0.1
                Shaft.sSettings.llmTemperature = temperature
                Local.setSettings(Shaft.sSettings)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
