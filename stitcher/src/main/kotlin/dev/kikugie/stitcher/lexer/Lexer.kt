package dev.kikugie.stitcher.lexer

import dev.kikugie.stitcher.lexer.StitcherTokenType.*
import dev.kikugie.stitcher.scanner.CommentType
import dev.kikugie.stitcher.token.Token
import dev.kikugie.stitcher.token.TokenType
import dev.kikugie.stitcher.util.leadingSpaces
import dev.kikugie.stitcher.util.trailingSpaces

class Lexer(private val input: Iterable<Token>) {
    fun tokenize(): Sequence<Token> = sequence {
        input.forEach { process(it) }
    }

    private suspend fun SequenceScope<Token>.process(token: Token) {
        if (token.type != CommentType.COMMENT) {
            yield(token)
            return
        }

        when (token.value.firstOrNull()) {
            '?' -> {
                yield(token.subtoken(0..<1, CONDITION))
                yieldAll(scanContents(token, DefaultRecognizers.conditionState))
            }

            '$' -> {
                yield(token.subtoken(0..<1, SWAP))
                yieldAll(scanContents(token, DefaultRecognizers.swapState))
            }

            else -> yield(token)
        }
    }

    private fun scanContents(token: Token, checkers: List<Pair<TokenType, TokenRecognizer>>): List<Token> {
        val tokens = mutableListOf<Token>()
        var index = 1
        val buffer = StringBuilder()

        fun expressionToken() {
            if (buffer.isNotBlank()) tokens += token.subtoken(
                index - buffer.length + buffer.leadingSpaces()..<index - buffer.trailingSpaces(),
                EXPRESSION
            )
        }

        while (index < token.value.length) {
            var matched = false
            for ((type, it) in checkers) {
                val result = it.recognize(token.value, index) ?: continue
                expressionToken()
                if (buffer.isNotEmpty()) buffer.clear()

                tokens += token.subtoken(index..<result.end, type)
                index = result.end
                matched = true
                break
            }
            if (!matched) buffer.append(token.value[index++])
        }
        expressionToken()
        return tokens
    }
}