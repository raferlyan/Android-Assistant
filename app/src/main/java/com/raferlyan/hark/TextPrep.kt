package com.raferlyan.hark

import android.content.Context
import java.io.File

/**
 * 把设置里的唤醒词 / 热词 / 纠错规则转换成 sherpa-onnx 需要的格式，并做 token 校验。
 *
 * 唤醒词模型的 token 是“声母 + 带调韵母”（与 sherpa-onnx text2token 的 ppinyin 一致），
 * 汉字到拼音用打包在 assets 里的 pinyin_table.txt（tools/gen_pinyin_table.py 生成）。
 * 多音字读错时，直接在设置里写 token 即可覆盖。
 */
class TextPrep(private val ctx: Context) {

    data class Prepared(val content: String, val count: Int, val warnings: List<String>)

    private val pinyin: Map<Char, List<String>> by lazy {
        val m = HashMap<Char, List<String>>(24000)
        ctx.assets.open("pinyin_table.txt").bufferedReader(Charsets.UTF_8).forEachLine { line ->
            // "小 x iǎo" 或零声母 "爱  ài"
            if (line.length < 3) return@forEachLine
            val parts = line.substring(2).split(' ').filter { it.isNotEmpty() }
            m[line[0]] = parts
        }
        m
    }

    private fun readTokens(path: String): Set<String> =
        File(path).takeIf { it.isFile }?.readLines(Charsets.UTF_8)
            ?.mapNotNull { it.trim().split(' ').firstOrNull()?.takeIf(String::isNotEmpty) }
            ?.toHashSet() ?: emptySet()

    /** 输入每行 `小晏小晏 :2.0 #0.20`，输出 `x iǎo y àn x iǎo y àn :2.0 #0.20 @小晏小晏`。 */
    fun prepareKeywords(raw: String, tokensPath: String): Prepared {
        val tokens = readTokens(tokensPath)
        val out = ArrayList<String>()
        val warn = ArrayList<String>()
        for (line in raw.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("//") }) {
            val words = line.split(Regex("\\s+"))
            val opts = words.filter { it.startsWith(":") || it.startsWith("#") }
            var display = words.firstOrNull { it.startsWith("@") }?.drop(1)
            val body = words.filterNot { it.startsWith(":") || it.startsWith("#") || it.startsWith("@") }

            val toks = ArrayList<String>()
            if (body.any { w -> w.any(::isCjk) }) {
                val text = body.joinToString("")
                if (display == null) display = text
                for (ch in text) {
                    val p = pinyin[ch]
                    if (p == null) {
                        warn += "「$line」中的「$ch」查不到拼音"
                        continue
                    }
                    toks += p
                }
            } else {
                toks += body
                if (display == null) display = body.joinToString("")
            }
            val bad = toks.filter { it !in tokens }
            if (bad.isNotEmpty()) {
                warn += "「$line」有 token 不在模型词表里：${bad.joinToString(" ")}（可改写为 token 形式）"
                continue
            }
            if (toks.isEmpty()) continue
            out += (toks + opts + "@$display").joinToString(" ")
        }
        return Prepared(out.joinToString("\n"), out.size, warn)
    }

    /** 热词：按字（cjkchar）编码，提前检查哪些字不在流式模型词表里。 */
    fun prepareHotwords(raw: String, tokensPath: String): Prepared {
        val tokens = readTokens(tokensPath)
        val out = ArrayList<String>()
        val warn = ArrayList<String>()
        for (line in raw.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("//") }) {
            val word = line.substringBefore(" :").trim()
            val oov = word.filter(::isCjk).filter { it.toString() !in tokens }
            if (oov.isNotEmpty()) {
                warn += "热词「$word」里的「$oov」不在模型词表，该热词会被跳过（这些字模型按字节输出，可以去掉它们只保留其余部分，如「手电筒」→「手电」）"
                continue
            }
            out += line
        }
        return Prepared(out.joinToString("\n"), out.size, warn)
    }

    companion object {
        fun isCjk(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿'

        fun parseCorrections(raw: String): List<Pair<String, String>> =
            raw.lines().mapNotNull { l ->
                val i = l.indexOf("=>")
                if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 2).trim()
            }.filter { it.first.isNotEmpty() }

        fun applyCorrections(text: String, rules: List<Pair<String, String>>): String =
            rules.fold(text) { acc, (from, to) -> acc.replace(from, to) }
    }
}
