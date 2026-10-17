package com.dreaminalife.lyricstranslator.data.translation

import com.dreaminalife.lyricstranslator.data.translation.openrouter.OpenRouterApi
import com.dreaminalife.lyricstranslator.data.translation.openrouter.OpenRouterModelCatalog
import com.dreaminalife.lyricstranslator.domain.model.AppSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationParsingTest {
    @Test
    fun `openrouter reply loses bullets, multi-digit numbering, fences and blank lines`() {
        val reply = "```\n1. one\n\n- two\n* three\n10) ten\n12. twelve\n99 Luftballons\n```"
        assertEquals(
            listOf("one", "two", "three", "ten", "twelve", "99 Luftballons"),
            OpenRouterResponseParser.parse(reply),
        )
    }

    @Test
    fun `prompt placeholders are filled once`() {
        val prompt = PromptTemplate.fill("To {target_language}:\n{lyrics}", "zh-CN", "a {target_language} line\n{lyrics}")
        assertEquals("To zh-CN:\na {target_language} line\n{lyrics}", prompt)
        assertFalse(PromptTemplate.hasLyricsPlaceholder("Translate to {target_language}"))
        assertTrue(PromptTemplate.hasLyricsPlaceholder(AppSettings.DEFAULT_PROMPT_TEMPLATE))
    }

    @Test
    fun `request body merges model json, ignores model and messages, lifts extra_body`() {
        val modelBody = JsonObject(
            mapOf(
                "temperature" to JsonPrimitive(1.2),
                "model" to JsonPrimitive("ignored"),
                "messages" to JsonPrimitive("ignored"),
                "extra_body" to JsonObject(mapOf("reasoning" to JsonObject(mapOf("enabled" to JsonPrimitive(false))))),
            )
        )
        val body = OpenRouterApi.buildChatBody("deepseek/x", "PROMPT", modelBody)
        assertEquals(setOf("reasoning", "temperature", "model", "messages"), body.keys)
        assertEquals("deepseek/x", body["model"]!!.jsonPrimitive.content)
        assertEquals("1.2", body["temperature"]!!.jsonPrimitive.content)
        assertEquals("false", body["reasoning"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        val message = body["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        assertEquals("PROMPT", message["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openrouter message content and error are read`() {
        assertEquals("hi", OpenRouterApi.messageContent("""{"choices":[{"message":{"role":"assistant","content":"hi"}}]}"""))
        assertEquals("No auth", OpenRouterApi.errorMessage("""{"error":{"message":"No auth","code":401}}"""))
    }

    @Test
    fun `google response segments are joined and split into lines`() {
        val json = """[[["Sinking, melting \n ","沈む \n ",null,null,3],["Night \n\n ","夜 \n\n ",null,null,3],["Goodbye","さよなら",null,null,3]],null,"ja"]"""
        val text = GoogleWebTranslator.parseResponse(json)
        assertEquals(listOf("Sinking, melting", "Night", "Goodbye"), GoogleWebTranslator.splitLines(text))
    }

    @Test
    fun `google batches stay under the size limit and keep order`() {
        val lines = List(30) { "x".repeat(200) + it }
        val batches = GoogleWebTranslator.chunk(lines)
        assertTrue(batches.size > 1)
        assertTrue(batches.all { batch -> batch.sumOf { it.length + 1 } <= 4500 })
        assertEquals(lines, batches.flatten())
    }

    @Test
    fun `model catalog reads id, context and price per million`() {
        val json = """{"data":[
            {"id":"z/b","name":"B","context_length":1000,"top_provider":{"context_length":64000},"pricing":{"prompt":"0.0000003","completion":"0.0000012"}},
            {"id":"a/a","pricing":{"prompt":"0","completion":"0"}}
        ]}"""
        val models = OpenRouterModelCatalog.parse(json)
        assertEquals(listOf("a/a", "z/b"), models.map { it.id })
        assertEquals("a/a", models[0].name)
        assertEquals(64000L, models[1].contextLength)
        assertEquals(0.3, models[1].promptPricePerM!!, 1e-9)
        assertEquals(1.2, models[1].completionPricePerM!!, 1e-9)
    }
}
