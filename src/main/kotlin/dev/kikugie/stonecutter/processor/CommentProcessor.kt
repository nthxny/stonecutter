package dev.kikugie.stonecutter.processor

import dev.kikugie.stonecutter.processor.ExpressionType.*
import java.io.Reader
import java.util.regex.Pattern

class CommentProcessor(
    private val input: Reader,
    private val output: StringBuilder,
    private val checker: ConditionProcessor
) {
    private val tokens = ArrayDeque<Entry>()
    private val line: Int
        get() = output.count { it == '\n' } + 1

    @Suppress("ControlFlowWithEmptyBody")
    fun run() {
        read(START) ?: return // No expressions in this file
        while (next()) {
        }
    }

    private fun next(): Boolean {
        val (expr, type) = ExpressionType.of(readExpression() ?: return false)
        when (type) {
            SINGLE -> single(expr)
            OPENER -> opener(expr)
            EXTENSION -> extension(expr)
            CLOSER -> closer()
        }
        return true
    }

    private fun single(expr: String) {
        if (!expr.startsWith("if", true))
            throw error("Only IF statement is allowed for single-line expressions: $expr")
        val last = tokens.lastOrNull()?.type
        if (last == OPENER || last == EXTENSION) throw error("Statements can't be nested: $expr")
        processCode(readNextLine(), testExpression(expr, 2, SINGLE))
        read(START)
    }

    private fun opener(expr: String) {
        if (!expr.startsWith("if", true))
            throw error("Only IF statement is allowed for openers: $expr")
        val last = tokens.lastOrNull()?.type
        if (last == OPENER || last == EXTENSION) throw error("Statements can't be nested: $expr")
        val code = read(START) ?: throw error("Conditional block is not closed")
        processCode(code, testExpression(expr, 2, OPENER))
    }

    private fun extension(expr: String) {
        val last = tokens.lastOrNull()?.type
        if (last != OPENER && last != EXTENSION) throw error("Statement must follow a condition: $expr")
        if (expr.startsWith("else", true))
            elseExtension(expr)
        else if (expr.startsWith("elif", true))
            elifExtension(expr)
        else throw error("Invalid expression $expr, must be ELSE or ELIF")
    }

    private fun closer() {
        val last = tokens.lastOrNull()?.type
        if (last != OPENER && last != EXTENSION) throw error("Condition block closer without context")
        tokens += Entry(CLOSER, false)
    }

    private fun elseExtension(expression: String) {
        if (!expression.equals("else", true))
            throw error("ELSE statements can't have a condition, use ELIF instead")
        val code = read(START) ?: throw error("Conditional block is not closed")
        (!tokens.last().result).also {
            processCode(code, it)
            tokens += Entry(EXTENSION, it)
        }
    }

    private fun elifExtension(expression: String) {
        if (expression.equals("elif", true))
            throw error("ELIF statement without a condition, use ELSE instead")
        val code = read(START) ?: throw error("Conditional block is not closed")
        (!tokens.last().result && testExpression(expression, 4, EXTENSION)).also { processCode(code, it) }
    }

    private fun testExpression(expression: String, drop: Int, type: ExpressionType): Boolean =
        checker.test(expression.drop(drop).trimStart()).also { tokens += Entry(type, it) }

    private fun readExpression(): String? {
        val expression = read(END)
            ?: return null
        if (START in expression)
            throw error("Expression wasn't correctly closed")
        if (expression.isBlank())
            throw error("Expression can't be empty")
        return expression.trim()
    }

    private fun readNextLine() = readRegex(CODE_LINE, true)?.replace(NEW_LINE, "")?.trim()
        ?: throw error("No end of line found. How.")

    @Suppress("SameParameterValue")
    private fun readRegex(regex: Pattern, includeMatch: Boolean = false): String? {
        val buffer = StringBuilder()
        val matcher = regex.matcher("")
        input.mark(1)

        var char: Char
        while (input.read().also { char = it.toChar() } != -1) {
            buffer.append(char)
            output.append(char)

            matcher.reset(buffer)
            if (matcher.find())
                if (!includeMatch)
                    return buffer.delete(matcher.start(), buffer.length).toString()
                else if (matcher.end() != buffer.length) {
                    buffer.deleteCharAt(buffer.length - 1)
                    output.deleteCharAt(output.length - 1)
                    input.reset()
                    return buffer.toString()
                }
            input.mark(1)
        }
        return if (buffer.isNotEmpty()) buffer.toString() else null
    }

    private fun read(match: String, includeMatch: Boolean = false): String? {
        val buffer = StringBuilder()
        var char: Char
        while (input.read().also { char = it.toChar() } != -1) {
            buffer.append(char)
            output.append(char)
            if (buffer.endsWith(match)) return buffer.toString().let {
                if (includeMatch) it else it.substring(0, it.length - match.length)
            }
        }
        return null
    }

    private fun isCommented(value: String) =
        value.trim().let { it.startsWith("/*") && it.endsWith("*/") }


    private fun processCode(code: String, enabled: Boolean) {
        val commented = isCommented(code)
        val result = if (enabled && commented)
            code.removePrefix("/*").removeSuffix("*/")
        else if (!enabled && !commented)
            "/*$code*/"
        else return

        val index = output.lastIndexOf(code)
        if (index != -1)
            output.replace(index, index + code.length, result)
    }

    private fun splitOne(str: String): Pair<String?, String?> =
        if (str.isBlank()) null to null
        else str.split(' ', limit = 1).let {
            if (it.size == 1) it.first().trim() to null
            else it.first().trim() to it.last().trim()
        }

    private fun error(message: String) = StonecutterSyntaxException("Error at line $line:\n$message")

    data class Entry(val type: ExpressionType, val result: Boolean)

    companion object {
        const val START = "/*?"
        const val END = "*/"
        val NEW_LINE = "(\\r\\n|\\r|\\n)".toRegex()
        val CODE_LINE: Pattern = Pattern.compile("\\S\\s*$NEW_LINE")

        fun process(input: Reader, processor: ConditionProcessor): StringBuilder {
            val builder = StringBuilder()
            val processor2 = CommentProcessor(input, builder, processor)
            processor2.run()
            return builder
        }
    }
}