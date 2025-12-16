package org.example.demo.chat

enum class AiModelType {
    GIGACHAT,
    HUGGINGFACE
}

data class AiModel(
    val type: AiModelType,
    val name: String,
    val displayName: String,
    val apiUrl: String? = null,
    val requiresAuth: Boolean = true
) {
    companion object {
        val GIGACHAT = AiModel(
            type = AiModelType.GIGACHAT,
            name = "GigaChat",
            displayName = "GigaChat",
            apiUrl = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions",
            requiresAuth = true
        )
        
        val HUGGINGFACE_DEEPSEEK = AiModel(
            type = AiModelType.HUGGINGFACE,
            name = "deepseek-ai/DeepSeek-V3.2",
            displayName = "deepseek-V3.2",
            apiUrl = "https://router.huggingface.co/v1/chat/completions",
            requiresAuth = true
        )
        
        val HUGGINGFACE_LLAMA = AiModel(
            type = AiModelType.HUGGINGFACE,
            name = "meta-llama/Llama-3.1-8B-Instruct:novita",
            displayName = "Llama-3.1",
            apiUrl = "https://router.huggingface.co/v1/chat/completions",
            requiresAuth = true
        )
        
        val ALL_MODELS = listOf(
            GIGACHAT,
            HUGGINGFACE_DEEPSEEK,
            HUGGINGFACE_LLAMA,
        )
    }
}







