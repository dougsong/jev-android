package io.github.jevandroid.sample

internal enum class ModelBackend(val label: String, val providerName: String, val defaultModel: String) {
    JEV("Jev (TypeSafe)", "TypeSafe", "jev-latest"),
    DEEPSEEK("DeepSeek", "DeepSeek", "deepseek-flash"),
}

internal data class ProviderFields(val apiKey: String, val model: String)

/** Per-activity, in-memory drafts. A provider switch never reuses another provider's key. */
internal class ProviderSelection {
    var backend: ModelBackend = ModelBackend.JEV
        private set
    private val fields = mutableMapOf<ModelBackend, ProviderFields>()

    fun select(next: ModelBackend, currentKey: String, currentModel: String): ProviderFields {
        fields[backend] = ProviderFields(currentKey, currentModel)
        backend = next
        return fields[next] ?: ProviderFields("", next.defaultModel)
    }
}
