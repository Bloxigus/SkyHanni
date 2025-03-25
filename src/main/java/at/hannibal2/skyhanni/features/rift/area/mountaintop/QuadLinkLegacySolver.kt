package at.hannibal2.skyhanni.features.rift.area.mountaintop

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.events.GuiRenderEvent
import at.hannibal2.skyhanni.events.InventoryCloseEvent
import at.hannibal2.skyhanni.events.InventoryOpenEvent
import at.hannibal2.skyhanni.events.InventoryUpdatedEvent
import at.hannibal2.skyhanni.events.render.gui.ReplaceItemEvent
import at.hannibal2.skyhanni.features.rift.RiftApi
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.test.command.ErrorManager
import at.hannibal2.skyhanni.utils.ConnectFourUtils
import at.hannibal2.skyhanni.utils.InventoryUtils
import at.hannibal2.skyhanni.utils.ItemUtils.getLore
import at.hannibal2.skyhanni.utils.ItemUtils.getSingleLineLore
import at.hannibal2.skyhanni.utils.ItemUtils.setLore
import at.hannibal2.skyhanni.utils.LorenzColor
import at.hannibal2.skyhanni.utils.LorenzColor.Companion.toLorenzColor
import at.hannibal2.skyhanni.utils.RegexUtils.matchMatcher
import at.hannibal2.skyhanni.utils.RegexUtils.matches
import at.hannibal2.skyhanni.utils.RenderUtils.renderRenderable
import at.hannibal2.skyhanni.utils.compat.EnchantmentsCompat
import at.hannibal2.skyhanni.utils.renderables.Renderable
import at.hannibal2.skyhanni.utils.repopatterns.RepoPattern
import net.minecraft.item.ItemStack

@SkyHanniModule
object QuadLinkLegacySolver {

    private val devConfig get() = SkyHanniMod.feature.dev.debug
    private val config get() = RiftApi.config.area.mountaintop.quadLinkLegacy
    private val patternGroup = RepoPattern.group("rift.area.mountaintop.quadlink")

    // <editor-fold desc="Patterns">
    /**
     * REGEX-TEST: §cRed: oBlazin
     * REGEX-TEST: §aGreen: Wizardman
     */
    private val colorIndicatorPattern by patternGroup.pattern(
        "color.indicator",
        "§(?<colorchar>.)(?<colorname>.*): (?<player>[a-zA-Z0-9_]+)"
    )

    /**
     * REGEX-TEST: §cRed
     * REGEX-TEST: §aGreen
     */
    private val indiscriminatePiecePattern by patternGroup.pattern(
        "piece.indiscriminate",
        "^§.[A-Z][a-z]+$"
    )

    /**
     * REGEX-TEST: §aClick here!
     */
    private val myTurnPiecePattern by patternGroup.pattern(
        "piece.myturn",
        "§aClick here!"
    )

    /**
     * REGEX-TEST: §cIt's not your turn!
     */
    private val notMyTurnPiecePattern by patternGroup.pattern(
        "piece.notmyturn",
        "§cIt's not your turn!"
    )

    /**
     * REGEX-TEST: §aWinning move!
     */
    private val winningPieceLorePattern by patternGroup.pattern(
        "piece.winning",
        ".*§aWinning move!.*"
    )

    /**
     * REGEX-TEST: Quad Link Legacy - Wizardman
     */
    private val quadLinkLegacyInventoryPattern by patternGroup.pattern(
        "qll.inventory",
        "Quad Link Legacy - (?<player>[a-zA-Z0-9_]+)"
    )

    /**
     * REGEX-TEST: Four in a Row - oBlazin
     */
    private val fourInARowInventoryPattern by patternGroup.pattern(
        "fiar.inventory",
        "Four in a Row - (?<player>[a-zA-Z0-9_]+)"
    )
    // </editor-fold>

    private const val OPPONENT_PIECE_INDICATOR_SLOT = 18
    private const val MY_PIECE_INDICATOR_SLOT = 26

    private var currentBoardProp: ConnectFourUtils.Position? = null
    private var recommendationItemStack: ItemStack? = null

    private var myColor: LorenzColor? = null
    private var opponentColor: LorenzColor? = null
    private var currentTurn: LorenzColor? = null
    private var recommendedColumnMove: Int? = null

    // List of column moves made by each player
    private val moves = mutableListOf<ConnectFourUtils.Move>()
    // Cache of current game board for easy comparison
    private var currentBoardCache = emptyBoardCache()

    private fun resetState() {
        myColor = null
        opponentColor = null
        currentTurn = null
        currentBoardProp = null
        recommendedColumnMove = null
        moves.clear()
        currentBoardCache = emptyBoardCache()
    }

    private fun emptyBoardCache(): Map<Int, LorenzColor?> = (0..53).associateWith { null }.toMutableMap()

    private fun ItemStack.isGamePiece() = indiscriminatePiecePattern.matches(displayName.orEmpty())
    private fun ItemStack.isWinningPiece() = winningPieceLorePattern.matches(getSingleLineLore())

    // We want to filter out anything on the sides when trying to read the game board
    private val excludedKeys = (0..53).filter { it % 9 == 0 || it % 9 == 8 }.toSet()
    private fun Map<Int, ItemStack>.commonFilter() = filter { it.key !in excludedKeys && it.value.hasDisplayName() }
    private fun Map<Int, ItemStack>.filterGamePieces() = commonFilter().filter { it.value.isGamePiece() }
    private fun Map<Int, ItemStack>.filterNotGamePieces() = commonFilter().filter { !it.value.isGamePiece() }
    private fun Map<Int, ItemStack>.getCurrentTurn(): LorenzColor? = filterNotGamePieces().values.firstOrNull()?.getPlayer()

    private fun String.fixHypixelColors() =
        replace("Orange", "Gold")

    private fun Pair<Int, ItemStack>.getPlayer(): LorenzColor? = second.getPlayer()
    private fun ItemStack.getPlayer(): LorenzColor? = displayName?.let {
        val myColor = myColor ?: ErrorManager.skyHanniError("Player colors not initialized")
        val opponentColor = opponentColor ?: ErrorManager.skyHanniError("Player colors not initialized")

        // Check if the piece is a player's placed piece
        it.fixHypixelColors().toLorenzColor(failOnError = false)?.let { color -> return color }

        if (myTurnPiecePattern.matches(it)) myColor
        else if (notMyTurnPiecePattern.matches(it)) opponentColor
        else null
    }

    private fun InventoryOpenEvent.readPlayerColors() {
        if (opponentColor != null && myColor != null) return
        opponentColor = extractColor(inventoryItems[OPPONENT_PIECE_INDICATOR_SLOT]?.displayName ?: return)
        myColor = extractColor(inventoryItems[MY_PIECE_INDICATOR_SLOT]?.displayName ?: return)
    }

    private fun regenerateBoardProp() {
        val moves = moves.takeIf { it.size > 0 } ?: return
        currentBoardProp = ConnectFourUtils.Position.fromMoveList(moves)
    }

    private fun regenerateBoardCache(currentBoard: Map<Int, ItemStack>) {
        currentBoardCache = currentBoard.map { it.key to it.value.getPlayer() }.toMap()
    }

    private fun extractColor(piece: String): LorenzColor? {
        return colorIndicatorPattern.matchMatcher(piece) {
            group("colorchar")?.let { it[0].toLorenzColor() }
                ?: group("colorname")?.let { LorenzColor.valueOf(it.uppercase()) }
                ?: ErrorManager.skyHanniError("Failed to read color")
        }
    }

    private fun Int.getBoardColumn(): Int? {
        val col = this % 9
        return if (col in 1..7) col - 1 else null
    }
    private fun Pair<Int, ItemStack>.getBoardColumn(): Int? = first.getBoardColumn()

    private fun getNextMove(): Int? {
        val board = currentBoardProp ?: return null
        return ConnectFourUtils.solve(board)
    }

    private fun generateRecommendationItemStack(base: ItemStack) {
        recommendationItemStack = base.copy().apply {
            setStackDisplayName("§aRecommended Move")
            addEnchantment(EnchantmentsCompat.PROTECTION.enchantment, 0)
            setLore(
                getLore().toMutableList().apply { add(0, "§8(From SkyHanni)") }
            )
        }
    }

    @HandleEvent
    fun replaceItem(event: ReplaceItemEvent) {
        if (!config.enabled || (!quadLinkLegacyInventoryPattern.matches(event.inventory.name)) && !fourInARowInventoryPattern.matches(event.inventory.name)) return
        if (event.slot in excludedKeys || event.slot.getBoardColumn() != recommendedColumnMove) return

        val originalItem = event.originalItem.takeIf { !it.isGamePiece() } ?: return
        if (originalItem.getPlayer() != myColor) return

        recommendationItemStack?.let {
            event.replace(it)
        } ?: generateRecommendationItemStack(originalItem)
    }

    @HandleEvent
    fun onInventoryClose(event: InventoryCloseEvent) {
        resetState()
    }

    @HandleEvent
    fun onBackgroundDraw(event: GuiRenderEvent.ChestGuiOverlayRenderEvent) {
        if (!devConfig.connect4Debug) return
        if (!quadLinkLegacyInventoryPattern.matches(InventoryUtils.openInventoryName()) && !fourInARowInventoryPattern.matches(InventoryUtils.openInventoryName())) return

        devConfig.connect4DebugPosition.renderRenderable(
            Renderable.verticalContainer(
                getDebugInfo()
            ),
            posLabel = "Connect4 Debug Info"
        )
    }

    private fun getDebugInfo() = listOf(
        Renderable.string("My Color: ${myColor ?: "N/A"}"),
        Renderable.string("Opponent Color: ${opponentColor ?: "N/A"}"),
        Renderable.string("Current Turn: ${currentTurn ?: "N/A"}"),
        Renderable.string("Moves: ${moves.size}"),
        Renderable.string("Board hash: ${currentBoardProp?.key() ?: "N/A"}"),
        Renderable.string("Recommended Move (Column): $recommendedColumnMove"),
        getBoardRenderable()
    )

    private fun getBoardRenderable() = Renderable.verticalContainer(
        buildList {
            add(Renderable.string("Current board:"))
            addAll(
                currentBoardProp?.toBoardString(
                    myColor = myColor ?: LorenzColor.RED,
                    opponentColor = opponentColor ?: LorenzColor.GREEN
                )?.split("\n")?.map {
                    Renderable.string(it)
                }.orEmpty()
            )
        }
    )

    // We only want to consider new moves
    //  Because the new move from the opponent is "flashed" when it is played, we need to do an explicit
    //  greater than check, as a not equal check would false flag on the "animation"
    private fun InventoryOpenEvent.getCurrentBoard(): Map<Int, ItemStack>? =
        inventoryItems.filterGamePieces().takeIf { it.size > moves.size }

    // Figure out which piece changed between the last turn and now
    private fun getChangedPiece(currentBoard: Map<Int, ItemStack>): Pair<Int, ItemStack>? =
        currentBoard.filter { currentBoardCache[it.key] == null }.takeIf {
            it.size == 1 // Only one piece should have changed
        }?.toList()?.first()

    @HandleEvent
    fun onInventoryUpdate(event: InventoryUpdatedEvent) {
        if (!config.enabled || (!quadLinkLegacyInventoryPattern.matches(event.inventoryName) && !fourInARowInventoryPattern.matches(event.inventoryName))) return

        event.readPlayerColors()
        val currentBoard = event.getCurrentBoard() ?: return
        if (currentBoard.any { it.value.isWinningPiece() }) {
            resetState()
            return
        }
        val changedStack = getChangedPiece(currentBoard) ?: return

        val newColumn = changedStack.getBoardColumn() ?: return
        val newPlayer = changedStack.getPlayer() ?: return
        val newMove = ConnectFourUtils.Move(newPlayer, newColumn)

        moves.add(newMove)
        regenerateBoardCache(currentBoard)
        regenerateBoardProp()
        currentTurn = event.inventoryItems.getCurrentTurn()
        recommendedColumnMove = if (currentTurn == myColor) getNextMove() else null
    }

    private fun ConnectFourUtils.Position.toBoardString(
        colored: Boolean = true,
        opponentColor: LorenzColor = LorenzColor.RED,
        myColor: LorenzColor = LorenzColor.YELLOW
    ): String {
        val board = StringBuilder()

        // Initialize a map from each column to its pieces (from bottom to top)
        val columnMap = mutableMapOf<Int, MutableList<LorenzColor?>>()
        for (col in 0 until ConnectFourUtils.Position.WIDTH) {
            columnMap[col] = MutableList(ConnectFourUtils.Position.HEIGHT) { null }
        }

        // We'll track how many pieces have been placed in each column using a simple counter
        val pieceCountPerColumn = IntArray(ConnectFourUtils.Position.WIDTH) { 0 }

        // Fill in the placed pieces from the history
        for ((index, col) in moves.withIndex()) {
            val player = col.color
            // Place the piece at the bottom-most available slot in that column
            // pieceCountPerColumn[col] gives the next free row (starting from 0 at the bottom)
            val placedHeight = pieceCountPerColumn[col.column]
            if (placedHeight >= ConnectFourUtils.Position.HEIGHT) ErrorManager.skyHanniError("Column $col is overfilled!")
            columnMap.getOrPut(col.column) { mutableListOf() }
            columnMap[col.column]?.let {
                it[placedHeight] = player
            }
            pieceCountPerColumn[col.column]++
        }

        // Build the visual board from top to bottom
        for (row in ConnectFourUtils.Position.HEIGHT - 1 downTo 0) {
            for (col in 0 until ConnectFourUtils.Position.WIDTH) {
                val cell = columnMap[col]?.get(row)
                val char = when (cell) {
                    is LorenzColor -> cell.name[0]
                    else -> 'O'
                }
                val appendString = if (colored) "§${cell?.chatColorCode ?: "7"}$char" else "$char"
                board.append(appendString).append(' ')
            }
            board.append('\n')
        }

        return board.toString().trimEnd()
    }
}
