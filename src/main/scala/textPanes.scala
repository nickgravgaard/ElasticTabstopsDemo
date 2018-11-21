import java.awt.Event.{CTRL_MASK, SHIFT_MASK}
import java.awt.event.KeyEvent.VK_Z
import java.awt.{Canvas, Color, Font, FontMetrics}
import java.nio.file.Path
import javax.swing.{JFrame, KeyStroke, SwingUtilities}
import scala.swing.Dialog.Result
import javax.swing.event.{DocumentEvent, UndoableEditEvent}
import javax.swing.text._
import javax.swing.text.DocumentFilter.FilterBypass
import javax.swing.undo.{CannotRedoException, CannotUndoException, UndoManager}
import scala.swing.{Action, Dialog, TextPane}
import scala.swing.event.{Key, KeyPressed}

import buildInfo.BuildInfo.{name => appName, version => appVersion}

import elasticTabstops.{split, splitAndStrip, calcTabstopPositions, spacesToTabs, tabsToSpaces}
import fileHandling.{chooseAndLoadTextFile, loadScratchFile, saveTextFile, saveTextFileAs}
import settings.{FontCC, Settings}


package object textPanes {

  class ElasticTextPane(var elasticFont: Font, var emptyColumnWidth: Double, var columnPadding: Double) extends TextPane {

    var fm = new Canvas().getFontMetrics(elasticFont)
    setFont(elasticFont)
    setElasticTabstopsDocFilter()

    def getPxVersionsOfEmSettings(): (Int, Int) = {
      val fontMetrics = new Canvas().getFontMetrics(elasticFont)
      val em = fontMetrics.getHeight  // more accurate than the font's point size (with Merriweather at least)
      val emptyColumnWidthPx = (emptyColumnWidth * em).toInt
      val columnPaddingPx = (columnPadding * em).toInt
      val emptyColumnWidthMinusPaddingPx = emptyColumnWidthPx - columnPaddingPx
      (emptyColumnWidthMinusPaddingPx, columnPaddingPx)
    }

    var (emptyColumnWidthMinusPaddingPx, columnPaddingPx) = getPxVersionsOfEmSettings

    def changeSettings(newElasticFont: Font, newEmptyColumnWidth: Double, newColumnPadding: Double): Unit = {
      elasticFont = newElasticFont
      emptyColumnWidth = newEmptyColumnWidth
      columnPadding = newColumnPadding

      val pxSettings = getPxVersionsOfEmSettings
      emptyColumnWidthMinusPaddingPx = pxSettings._1
      columnPaddingPx = pxSettings._2

      fm = new Canvas().getFontMetrics(elasticFont)
      setFont(elasticFont)
      setElasticTabstopsDocFilter()
      updateText(this.text)  // force update of tabstop positions
    }

    protected def setFont(font: Font) = {
      val attributes = new SimpleAttributeSet
      StyleConstants.setBackground(attributes, background)
      StyleConstants.setForeground(attributes, foreground)
      StyleConstants.setFontFamily(attributes, font.getFamily)
      StyleConstants.setFontSize(attributes, font.getSize)
      val doc = peer.getDocument.asInstanceOf[StyledDocument]
      doc.setParagraphAttributes(0, doc.getLength, attributes, true)
    }

    def onChange(): Unit = {}

    def getCaretsLineNumAndPos(): (Int, Int) = {
      val caretPos = peer.getCaretPosition
      val root = peer.getDocument.getDefaultRootElement
      val lineNum = root.getElementIndex(caretPos)
      val startOfLineOffset = root.getElement(lineNum).getStartOffset
      val posOnLine = caretPos - startOfLineOffset
      val lineTextToCaret = this.text.drop(startOfLineOffset).take(posOnLine)
      val minimalWhitespacePos = lineTextToCaret.replaceAll("[ \t]+", " ").length
      (lineNum, minimalWhitespacePos)
    }

    def minimiseMultipleWhitespace(list: List[(Char, Int)]) : List[(Char, Int)] = list match {
      case Nil => Nil
      case h::t => h match {
        case (' ', _) | ('\t', _) => {
          val segment = list.takeWhile((cAndP: (Char, Int)) => cAndP._1 == ' ' || cAndP._1 == '\t')
          (' ', segment.head._2) :: minimiseMultipleWhitespace(list.drop(segment.length))
        }
        case nonWhitespaceCAndP => nonWhitespaceCAndP :: minimiseMultipleWhitespace(list.drop(1))
      }
    }

    def setCaretsLineNumAndPos(lineNumAndPos: (Int, Int)): Unit = {
      val (lineNum, minimalWhitespacePos) = lineNumAndPos
      val root = peer.getDocument.getDefaultRootElement
      val startOfLineOffset = root.getElement(lineNum).getStartOffset
      val indexedLineText = split(this.text, '\n').drop(lineNum).take(1).flatten.zipWithIndex
      val minimalWhitespaceOnly = minimiseMultipleWhitespace(indexedLineText.toList)
      val pos = minimalWhitespaceOnly.lift(minimalWhitespacePos) match {
        case Some((_, pos)) => pos
        case None => minimalWhitespaceOnly.lastOption match {
          case Some((_, pos)) => pos + 1
          case None => 0
        }
      }
      peer.setCaretPosition(startOfLineOffset + pos)
    }

    def setNewText(text: String): Unit = {
      this.text = text
      peer.setCaretPosition(0)
      peer.grabFocus
      undoManager.discardAllEdits
    }

    def updateText(text: String): Unit = {
      val caretsLineNumAndPos = getCaretsLineNumAndPos()
      this.text = text
      setCaretsLineNumAndPos(caretsLineNumAndPos)
      peer.grabFocus
      // TODO: At the moment we discard the undo history when we update text, because converting from elastic to
      // non-elastic and vice versa are currently not undoable events, but it would be nice to be able to undo them.
      undoManager.discardAllEdits
    }

    private def getRecalcRange(textPerLine: List[String], startLineNum: Int, nofLines: Int): (Int, Int) = {
      val indexedLines = textPerLine.zipWithIndex
      val recalcStart = indexedLines.take(startLineNum).reverse.find(_._1.count(_ == '\t') == 0) match {
        case None => 0
        case Some((_, lineNum)) => lineNum
      }
      val recalcEnd = indexedLines.drop(startLineNum + nofLines).find(_._1.count(_ == '\t') == 0) match {
        case None => indexedLines.length
        case Some((_, lineNum)) => lineNum + 1
      }
      val recalcLength = recalcEnd - recalcStart
      (recalcStart, recalcLength)
    }

    def alignTabstops(startAndLength: Option[(Int, Int)] = None): Unit = {
      val doc = peer.getDocument.asInstanceOf[StyledDocument]
      val section = doc.getDefaultRootElement

      val allElements = (for (l <- 0 until section.getElementCount) yield section.getElement(l)).toList
      val allTextPerLine = for (el <- allElements) yield doc.getText(el.getStartOffset, el.getEndOffset - el.getStartOffset)

      val (recalcStart, recalcLength) = startAndLength match {
        case None => (0, allTextPerLine.length)
        case Some((lineNum, nofLines)) => {
          getRecalcRange(allTextPerLine, lineNum, nofLines)
        }
      }

      val elements = allElements.drop(recalcStart).take(recalcLength)
      val textPerLine = for (el <- elements) yield doc.getText(el.getStartOffset, el.getEndOffset - el.getStartOffset)
      val cellsPerLine = textPerLine.map(splitAndStrip(_, '\t').toList)
      def calcCellWidth(text: String): Int = math.max(fm.stringWidth(text), emptyColumnWidthMinusPaddingPx) + columnPaddingPx
      for ((tabstopPositionsThisLine, element) <- calcTabstopPositions(cellsPerLine, calcCellWidth).zip(elements)) {
        val tabStops = tabstopPositionsThisLine.map(new TabStop(_))
        val attributes = new SimpleAttributeSet()
        StyleConstants.setTabSet(attributes, new TabSet(tabStops.toArray))
        val length = element.getEndOffset - element.getStartOffset
        doc.setParagraphAttributes(element.getStartOffset, length, attributes, false)
      }
    }

    protected def setElasticTabstopsDocFilter(): Unit = {

      var fontMetrics = new Canvas().getFontMetrics(elasticFont)

      object ElasticTabstopsDocFilter extends DocumentFilter {
        override def insertString(fb: FilterBypass, offset: Int, string: String, attributes: AttributeSet) {
          super.insertString(fb, offset, string, attributes)
          onChange()
          val doc = fb.getDocument.asInstanceOf[StyledDocument]
          val lineNum = doc.getDefaultRootElement.getElementIndex(offset)
          val nofLines = string.count(_ == '\n') + 1
          alignTabstops(Some(lineNum, nofLines))
        }

        override def remove(fb: FilterBypass, offset: Int, length: Int) {
          super.remove(fb, offset, length)
          onChange()
          val doc = fb.getDocument.asInstanceOf[StyledDocument]
          val lineNum = doc.getDefaultRootElement.getElementIndex(offset)
          val nofLines = 1
          alignTabstops(Some(lineNum, nofLines))
        }

        override def replace(fb: FilterBypass, offset: Int, length: Int, string: String, attributes: AttributeSet) {
          super.replace(fb, offset, length, string, attributes)
          onChange()
          val doc = fb.getDocument.asInstanceOf[StyledDocument]
          val lineNum = doc.getDefaultRootElement.getElementIndex(offset)
          val nofLines = string.count(_ == '\n') + 1
          alignTabstops(Some(lineNum, nofLines))
        }
      }
      peer.getDocument().asInstanceOf[AbstractDocument].setDocumentFilter(ElasticTabstopsDocFilter)
    }

    val undoManager = new UndoManager
    val doc = peer.getDocument
    val javaVersionNum = System.getProperty("java.version").split('.').head.toInt
    doc.addUndoableEditListener((event: UndoableEditEvent) => {
      // There are problems with undo in Java 9 and later, so disable until we can get a workaround working :(
      // see: https://bugs.openjdk.java.net/browse/JDK-8190763
      // possible workarounds:
      // * https://github.com/nordfalk/jsyntaxpane/issues/198
      // * https://issues.apache.org/jira/browse/NETBEANS-217
      if (javaVersionNum < 9) {
        val edit = event.getEdit
        val eventType = edit.asInstanceOf[DocumentEvent].getType
        if (eventType != DocumentEvent.EventType.CHANGE)
          // don't allow undoing of style changes (so we ignore tabstop changes)
          undoManager.addEdit(edit)
      }
    })

    def undoAction(): Action = {
      val action = Action("Undo") {
        try {
          if (undoManager.canUndo) {
            undoManager.undo()
            alignTabstops()
          }
        }
        catch {
          case e: CannotUndoException =>
        }
      }
      action.accelerator = Some(KeyStroke.getKeyStroke(VK_Z, CTRL_MASK))
      action
    }

    def redoAction(): Action = {
      val action = Action("Redo") {
        try {
          if (undoManager.canRedo) {
            undoManager.redo()
            alignTabstops()
          }
        }
        catch {
          case e: CannotRedoException =>
        }
      }
      action.accelerator = Some(KeyStroke.getKeyStroke(VK_Z, CTRL_MASK | SHIFT_MASK))
      action
    }

    listenTo(this.keys)

    reactions += {
      case kp @ KeyPressed(_, Key.Z, _, _) => {
        if (kp.peer.isControlDown()) {
          if (kp.peer.isShiftDown()) {
            redoAction.apply
          } else {
            undoAction.apply
          }
        }
      }
    }

  }

  class EditorTextPane(_elasticFont: Font, _emptyColumnWidth: Double, _columnPadding: Double,
                       var nonElasticFont: Font, var nonElasticTabSize: Int, var filesAreNonElastic: Boolean,
                       var currentPath: String)
    extends ElasticTextPane(_elasticFont, _emptyColumnWidth, _columnPadding) {

    setNewText(if (filesAreNonElastic) spacesToTabs(loadScratchFile) else loadScratchFile)

    private var _elastic = true
    def elastic = _elastic
    def elastic_=(newElastic: Boolean) {
      if (newElastic != _elastic) {
        _elastic = newElastic
        if (_elastic) {
          // elastic on
          fm = new Canvas().getFontMetrics(elasticFont)
          setFont(elasticFont)
          setElasticTabstopsDocFilter()
          updateText(spacesToTabs(this.text))
        } else {
          // elastic off
          setFont(nonElasticFont)
          peer.getDocument().asInstanceOf[AbstractDocument].setDocumentFilter(new DocumentFilter)
          updateText(tabsToSpaces(this.text, nonElasticTabSize))
        }
      }
    }

    def changeSettings(newElasticFont: Font, newEmptyColumnWidth: Double, newColumnPadding: Double,
                       newNonElasticFont: Font, newNonElasticTabSize: Int): Unit = {
      elasticFont = newElasticFont
      emptyColumnWidth = newEmptyColumnWidth
      columnPadding = newColumnPadding
      nonElasticFont = newNonElasticFont
      nonElasticTabSize = newNonElasticTabSize

      val pxSettings = getPxVersionsOfEmSettings
      emptyColumnWidthMinusPaddingPx = pxSettings._1
      columnPaddingPx = pxSettings._2
      if (_elastic) {
        fm = new Canvas().getFontMetrics(elasticFont)
        setFont(elasticFont)
        setElasticTabstopsDocFilter()
        updateText(this.text)  // force update of tabstop positions
      } else {
        setFont(nonElasticFont)
      }
    }

    private var _modified = false
    def modified = _modified
    def modified_=(newModified: Boolean) {
      if (newModified != _modified) {
        _modified = newModified
        updateWindowTitle()
      }
    }

    def updateWindowTitle(): Unit = {
      val frame = SwingUtilities.getWindowAncestor(peer).asInstanceOf[JFrame]
      if (frame != null)
        frame.setTitle(s"${if (modified) "* " else ""}$currentPath - $appName v$appVersion")
    }

    def openScratchFile(scratchFilePath: Path, settings: Settings): Unit = {
      if (currentPath != scratchFilePath.toString && (!modified || Dialog.showConfirmation(message = "There are unsaved changes. Are you sure you want to switch to the scratch file?") == Result.Ok)) {
        currentPath = scratchFilePath.toString
        setNewText(if (settings.filesAreNonElastic) spacesToTabs(loadScratchFile) else loadScratchFile)
      }
    }

    def openFile(settings: Settings): Unit = {
      if (!modified || Dialog.showConfirmation(message = "There are unsaved changes. Are you sure you want to open another file?") == Result.Ok) {
        chooseAndLoadTextFile foreach { case (loadedText, path) =>
          currentPath = path
          setNewText(if (settings.filesAreNonElastic) spacesToTabs(loadedText) else loadedText)
        }
      }
    }

    def saveFile(settings: Settings): Unit = {
      val textToSave = if (settings.filesAreNonElastic) tabsToSpaces(text, settings.nonElasticTabSize) else text
      saveTextFile(textToSave, currentPath)
      modified = false
    }

    def saveFileAs(settings: Settings): Unit = {
      val textToSave = if (settings.filesAreNonElastic) tabsToSpaces(text, settings.nonElasticTabSize) else text
      saveTextFileAs(textToSave) foreach { path =>
        currentPath = path
        modified = false
      }
    }

    override def onChange(): Unit = {
      modified = true
    }
    override def setNewText(text: String): Unit = {
      super.setNewText(text)
      modified = false
    }
  }

}
