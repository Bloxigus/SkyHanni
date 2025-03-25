package at.hannibal2.skyhanni.utils

import at.hannibal2.skyhanni.utils.ConnectFourUtils.Position.Companion.columnMask
import kotlin.math.min


/**
 * This entire file is a Kotlin port of a python implementation by lhorrell99
 * https://github.com/lhorrell99/connect-4-solver/tree/master
 */

// position_t = Long
object ConnectFourUtils {
    class Position {
        companion object {
            const val WIDTH = 7
            const val HEIGHT = 6
            const val MIN_SCORE = -(WIDTH * HEIGHT) / 2 + 3
            const val MAX_SCORE = (WIDTH * HEIGHT + 1) / 2 - 3
            private fun calculateBottomMask(width: Int, height: Int): Long {
                if (width == 0) {
                    return 0
                }
                val mask = calculateBottomMask(width - 1, height) or (1L shl ((width - 1) * (height + 1)))
                return mask
            }
            val bottomMask = calculateBottomMask(WIDTH, HEIGHT)
            val boardMask = bottomMask * ((1L shl HEIGHT) - 1)
            private fun computeWinningPosition(position: Long, mask: Long): Long {
                var r = (position shl 1) and (position shl 2) and (position shl 3)
                var p = (position shl (HEIGHT + 1)) and (position shl 2 * (HEIGHT + 1))
                r = r or (p and (position shl 3 * (HEIGHT + 1)))
                r = r or (p and (position shr (HEIGHT + 1)))
                p = (position shr (HEIGHT + 1)) and (position shr 2 * (HEIGHT + 1))
                r = r or (p and (position shl (HEIGHT + 1)))
                r = r or (p and (position shr 3 * (HEIGHT + 1)))

                p = (position shl HEIGHT) and (position shl 2 * HEIGHT)
                r = r or (p and (position shl 3 * HEIGHT))
                r = r or (p and (position shr HEIGHT))
                p = (position shr HEIGHT) and (position shr 2 * HEIGHT)
                r = r or (p and (position shl HEIGHT))
                r = r or (p and (position shr 3 * HEIGHT))

                p = (position shl (HEIGHT + 2)) and (position shl 2 * (HEIGHT + 2))
                r = r or (p and (position shl 3 * (HEIGHT + 2)))
                r = r or (p and (position shr (HEIGHT + 2)))
                p = (position shr (HEIGHT + 2)) and (position shr 2 * (HEIGHT + 2))
                r = r or (p and (position shl (HEIGHT + 2)))
                r = r or (p and (position shr 3 * (HEIGHT + 2)))

                return r and (boardMask xor mask)
            }
            fun columnMask(col: Int): Long {
                return ((1L shl HEIGHT) - 1) shl (col * (HEIGHT + 1))
            }
            private fun popcount(m: Long): Int {
                return m.countOneBits()
            }
            fun topMaskCol(col: Int): Long {
                return 1L shl ((HEIGHT - 1) + col * (HEIGHT + 1))
            }

            // Returns a bitmask containing a single 1 corresponding to the bottom cell of a given column
            fun bottomMaskCol(col: Int): Long {
                return 1L shl (col * (HEIGHT + 1))
            }
            fun fromMoveList(moves: MutableList<Move>): Position {
                val pos = Position()
                for (move in moves) {
                    pos.playCol(move.column)
                }
                return pos
            }
            fun fromKey(key: Long, mask: Long, moves: Int): Position {
                val pos = Position()
                pos.currentPosition = key
                pos.mask = mask
                pos.moves = moves
                return pos
            }
            fun duplicate(position: Position): Position {
                val pos = Position()
                pos.currentPosition = position.currentPosition
                pos.mask = position.mask
                pos.moves = position.moves
                return pos
            }
        }
        private var currentPosition: Long = 0
        private var mask: Long = 0
        private var moves: Int = 0
        fun play(move: Long) {
            currentPosition = currentPosition xor mask
            mask = mask or move
            moves++
        }
        fun play(seq: String): Int {
            for (i in seq.indices) {
                val col = seq[i].code - '1'.code
                if (col < 0 || col >= WIDTH || !canPlay(col) || isWinningMove(col)) {
                    return i // Invalid move
                }
                playCol(col)
            }
            return seq.length
        }
        fun nbMoves(): Int {
            return moves
        }
        fun key(): Long {
            return currentPosition + mask
        }
        fun key3(): Long {
            var keyForward: Long = 0
            for (i in 0 until WIDTH) {
                keyForward = partialKey3(keyForward, i)
            }

            var keyReverse: Long = 0
            for (i in WIDTH - 1 downTo 0) {
                keyReverse = partialKey3(keyReverse, i)
            }

            return (min(keyForward.toDouble(), keyReverse.toDouble()) / 3).toLong() // Take the smaller key and divide by 3
        }
        fun possibleNonLosingMoves(): Long {
            assert(!canWinNext())

            var possibleMask = possible()
            val opponentWin = opponentWinningPosition()
            val forcedMoves = possibleMask and opponentWin

            if (forcedMoves != 0L) {
                if ((forcedMoves and (forcedMoves - 1)) != 0L)
                    return 0
                else possibleMask = forcedMoves
            }

            return possibleMask and (opponentWin ushr 1).inv()
        }
        fun canWinNext(): Boolean {
            return winningPosition() and possible() != 0L
        }
        fun moveScore(move: Long): Int {
            return popcount(computeWinningPosition(currentPosition or move, move))
        }
        fun canPlay(col: Int): Boolean {
            return (mask and topMaskCol(col)) == 0L
        }
        fun possible(): Long {
            return (mask + bottomMask) and boardMask
        }
        fun opponentWinningPosition(): Long {
            return computeWinningPosition(currentPosition xor mask, mask)
        }
        fun winningPosition(): Long {
            return computeWinningPosition(currentPosition, mask)
        }
        fun partialKey3(tempKey: Long, col: Int): Long {
            var key = tempKey
            var pos = 1L shl (col * (HEIGHT + 1))
            while ((pos and mask) != 0L) {
                key *= 3
                key += (if (((pos and currentPosition) != 0L)) 1 else 2).toLong()
                pos = pos shl 1
            }
            return key * 3
        }
        fun isWinningMove(col: Int): Boolean {
            return winningPosition() and possible() and columnMask(col) != 0L
        }
        fun playCol(col: Int) {
            play((mask + bottomMaskCol(col)) and columnMask(col))
        }
    }
    data class Move(val color: LorenzColor, val column: Int)
    class MoveSorter {
        private var size = 0
        private class Entry {
            var move: Long = 0
            var score: Int = 0
        }
        private val entries = arrayOfNulls<Entry>(Position.WIDTH)
        init {
            for (i in 0 until Position.WIDTH) {
                entries[i] = Entry()
            }
        }
        fun add(move: Long, score: Int) {
            var pos = size++
            while (pos > 0 && entries[pos - 1]!!.score > score) {
                entries[pos]!!.move = entries[pos - 1]!!.move
                entries[pos]!!.score = entries[pos - 1]!!.score
                pos--
            }
            entries[pos]!!.move = move
            entries[pos]!!.score = score
        }

        val next: Long
            get() = if ((size > 0)) entries[--size]!!.move else 0
        fun reset() {
            size = 0
        }
    }
    class Solver {
        var nodeCount: Long = 0 // counter of explored nodes
            private set
        private val columnOrder = IntArray(Position.WIDTH) // column exploration order

        // Recursively score connect 4 position using negamax variant of alpha-beta algorithm.
        // Assumes nobody already won and current player cannot win next move.
        private fun negamax(position: Position, alpha: Int, beta: Int): Int {
            var alpha = alpha
            var beta = beta
            assert(alpha < beta)
            assert(!position.canWinNext())

            nodeCount++ // increment counter of explored nodes

            val possible = position.possibleNonLosingMoves()
            if (possible == 0L) // if no possible non-losing move, opponent wins next move
                return -(Position.WIDTH * Position.HEIGHT - position.nbMoves()) / 2

            if (position.nbMoves() >= Position.WIDTH * Position.HEIGHT - 2) // check for draw game
                return 0

            var min =
                -(Position.WIDTH * Position.HEIGHT - 2 - position.nbMoves()) / 2 // lower bound of score
            if (alpha < min) {
                alpha = min
                if (alpha >= beta) return alpha // prune the exploration if the [alpha;beta] window is empty
            }

            var max =
                (Position.WIDTH * Position.HEIGHT - 1 - position.nbMoves()) / 2 // upper bound of score
            if (beta > max) {
                beta = max
                if (alpha >= beta) return beta // prune the exploration if the [alpha;beta] window is empty
            }

            val key = position.key()
            val value = transTable[key] ?: 0
            if (value != 0) {
                if (value > Position.MAX_SCORE - Position.MIN_SCORE + 1) { // lower bound
                    min = value + 2 * Position.MIN_SCORE - Position.MAX_SCORE - 2
                    if (alpha < min) {
                        alpha = min
                        if (alpha >= beta) return alpha
                    }
                } else { // upper bound
                    max = value + Position.MIN_SCORE - 1
                    if (beta > max) {
                        beta = max
                        if (alpha >= beta) return beta
                    }
                }
            }

            val moves = MoveSorter()
            for (i in Position.WIDTH - 1 downTo 0) {
                val move = possible and columnMask(columnOrder[i])
                if (move != 0L) moves.add(move, position.moveScore(move))
            }
            var next = moves.next
            while (next != 0L) {
                val positionDuplicate = Position.duplicate(position)
                positionDuplicate.play(next) // opponent's turn after current player plays
                val score = -negamax(positionDuplicate, -beta, -alpha)

                if (score >= beta) {
                    transTable[key] =
                        score + Position.MAX_SCORE - 2 * Position.MIN_SCORE + 2 // save lower bound
                    return score
                }
                if (score > alpha) alpha = score
                next = moves.next
            }

            transTable[key] = alpha - Position.MIN_SCORE + 1 // save upper bound
            return alpha
        }

        // Returns the score of a position
        fun solve(position: Position, weak: Boolean): Int {
            if (position.canWinNext()) // check if win in one move
                return (Position.WIDTH * Position.HEIGHT + 1 - position.nbMoves()) / 2

            var min = -(Position.WIDTH * Position.HEIGHT - position.nbMoves()) / 2
            var max = (Position.WIDTH * Position.HEIGHT + 1 - position.nbMoves()) / 2
            if (weak) {
                min = -1
                max = 1
            }

            while (min < max) { // iteratively narrow the min-max exploration window
                var med = min + (max - min) / 2
                if (med <= 0 && min / 2 < med) med = min / 2
                else if (med >= 0 && max / 2 > med) med = max / 2
                val r = negamax(position, med, med + 1) // null depth window
                if (r <= med) max = r
                else min = r
            }
            return min
        }

        // Returns the score of all possible moves of a position
        fun analyze(position: Position, weak: Boolean): IntArray {
            val scores = IntArray(Position.WIDTH)
            for (col in 0 until Position.WIDTH) {
                if (position.canPlay(col)) {
                    if (position.isWinningMove(col)) scores[col] =
                        (Position.WIDTH * Position.HEIGHT + 1 - position.nbMoves()) / 2
                    else {
                        val position2 = Position.duplicate(position)
                        position2.playCol(col)
                        scores[col] = -solve(position2, weak)
                    }
                } else {
                    scores[col] = INVALID_MOVE
                }
            }
            return scores
        }

        // Constructor
        init {
            for (i in 0 until Position.WIDTH) {
                columnOrder[i] =
                    Position.WIDTH / 2 + (1 - 2 * (i % 2)) * (i + 1) / 2 // initialize column order
            }
        }

        fun reset() {
            nodeCount = 0
            transTable.clear()
        }

        companion object {
            private const val TABLE_SIZE = 24 // store 2^TABLE_SIZE elements in the transposition table
            const val INVALID_MOVE: Int = -1000
            private val transTable: MutableMap<Long, Int> = HashMap()
        }
    }
    val solver = Solver()
    fun solve(position: Position): Int {
        var currentMax = Solver.INVALID_MOVE
        var currentIndex = -1
        val moves = solver.analyze(position, false)
        for (index in moves.indices) {
            if (currentMax > moves[index]) {
                currentMax = moves[index]
                currentIndex = index
            }
        }
        return currentIndex
    }
}
