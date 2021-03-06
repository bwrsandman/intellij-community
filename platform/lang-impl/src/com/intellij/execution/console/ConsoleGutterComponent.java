package com.intellij.execution.console;

import com.intellij.codeInsight.hint.TooltipController;
import com.intellij.codeInsight.hint.TooltipGroup;
import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.impl.ApplicationImpl;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.ex.DocumentBulkUpdateListener;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.editor.ex.EditorMarkupModel;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.HintHint;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;

public class ConsoleGutterComponent extends JComponent implements MouseMotionListener {
  private static final TooltipGroup TOOLTIP_GROUP = new TooltipGroup("CONSOLE_GUTTER_TOOLTIP_GROUP", 0);

  private int annotationGuttersSize = 0;
  private int myLastPreferredHeight = -1;
  private final EditorImpl editor;

  private final GutterContentProvider gutterContentProvider;

  private int lastGutterToolTipLine = -1;

  public ConsoleGutterComponent(@NotNull Editor editor, @NotNull GutterContentProvider provider, @NotNull Disposable parentDisposable) {
    this.editor = (EditorImpl)editor;
    gutterContentProvider = provider;
    addListeners(parentDisposable);

    addMouseMotionListener(this);
  }

  private void addListeners(@NotNull Disposable parentDisposable) {
    Project project = editor.getProject();
    assert project != null;
    project.getMessageBus().connect(parentDisposable).subscribe(DocumentBulkUpdateListener.TOPIC, new DocumentBulkUpdateListener.Adapter() {
      @Override
      public void updateFinished(@NotNull Document document) {
        if (document.getTextLength() == 0) {
          gutterContentProvider.documentCleared(editor);
        }
        updateSize();
      }
    });

    editor.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      public void documentChanged(DocumentEvent event) {
        DocumentEx document = editor.getDocument();
        if (document.isInBulkUpdate()) {
          return;
        }

        if (document.getTextLength() > 0) {
          int startDocLine = document.getLineNumber(event.getOffset());
          int endDocLine = document.getLineNumber(event.getOffset() + event.getNewLength());
          if (event.getOldLength() > event.getNewLength() || startDocLine != endDocLine || StringUtil.indexOf(event.getOldFragment(), '\n') != -1) {
            updateSize();
          }
        }
        else if (event.getOldLength() > 0) {
          gutterContentProvider.documentCleared(editor);
        }
      }
    });

    addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.isPopupTrigger()) {
          return;
        }

        int line = getLineAtPoint(e.getPoint());
        gutterContentProvider.doAction(line, editor);
      }
    });
  }

  public void updateSize() {
    int oldAnnotationsWidth = annotationGuttersSize;
    computeAnnotationsSize();
    if (oldAnnotationsWidth != annotationGuttersSize || myLastPreferredHeight != editor.getPreferredHeight()) {
      fireResized();
    }
    repaint();
  }

  private void fireResized() {
    processComponentEvent(new ComponentEvent(this, ComponentEvent.COMPONENT_RESIZED));
  }

  private void computeAnnotationsSize() {
    FontMetrics fontMetrics = editor.getFontMetrics(Font.PLAIN);
    int lineCount = editor.getDocument().getLineCount();
    GutterContentProvider gutterProvider = gutterContentProvider;
    gutterProvider.beforeUiComponentUpdate(editor);
    int gutterSize = 0;
    for (int i = 0; i < lineCount; i++) {
      String text = gutterProvider.getText(i, editor);
      if (text != null) {
        gutterSize = Math.max(gutterSize, fontMetrics.stringWidth(text));
      }
    }
    annotationGuttersSize = gutterSize;
  }

  @Override
  public Dimension getPreferredSize() {
    int w = annotationGuttersSize;
    myLastPreferredHeight = editor.getPreferredHeight();
    return new Dimension(w, myLastPreferredHeight);
  }

  @Override
  public void paint(Graphics g) {
    ((ApplicationImpl)ApplicationManager.getApplication()).editorPaintStart();
    try {
      Rectangle clip = g.getClipBounds();
      if (clip.height < 0) {
        return;
      }

      g.setColor(editor.getBackgroundColor());
      g.fillRect(clip.x, clip.y, clip.width, clip.height);

      if (annotationGuttersSize == 0) {
        return;
      }

      UISettings.setupAntialiasing(g);

      Graphics2D g2 = (Graphics2D)g;
      Object hint = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
      if (!UIUtil.isRetina()) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
      }

      try {
        paintAnnotations(g, clip);
      }
      finally {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, hint);
      }
    }
    finally {
      ((ApplicationImpl)ApplicationManager.getApplication()).editorPaintFinish();
    }
  }

  private void paintAnnotations(Graphics g, Rectangle clip) {
    g.setColor(JBColor.blue);
    g.setFont(editor.getColorsScheme().getFont(EditorFontType.PLAIN));

    int lineHeight = editor.getLineHeight();
    int startLineNumber = clip.y / lineHeight;
    int endLineNumber = (clip.y + clip.height) / lineHeight + 1;
    int lastLine = editor.logicalToVisualPosition(new LogicalPosition(getEndLineNumber(), 0)).line;
    endLineNumber = Math.min(endLineNumber, lastLine + 1);
    if (startLineNumber >= endLineNumber) {
      return;
    }

    gutterContentProvider.beforeUiComponentUpdate(editor);

    for (int i = startLineNumber; i < endLineNumber; i++) {
      int logLine = editor.visualToLogicalPosition(new VisualPosition(i, 0)).line;
      String text = gutterContentProvider.getText(logLine, editor);
      if (text != null) {
        g.drawString(text, 0, (i + 1) * lineHeight - editor.getDescent());
      }
    }
  }

  private int getEndLineNumber() {
    return Math.max(0, editor.getDocument().getLineCount() - 1);
  }

  @Override
  public void mouseDragged(MouseEvent e) {
    TooltipController.getInstance().cancelTooltips();
  }

  @Override
  public void mouseMoved(MouseEvent e) {
    int line = getLineAtPoint(e.getPoint());
    if (line == lastGutterToolTipLine) {
      return;
    }

    TooltipController controller = TooltipController.getInstance();
    if (lastGutterToolTipLine != -1) {
      controller.cancelTooltip(TOOLTIP_GROUP, e, true);
    }

    String toolTip = gutterContentProvider.getToolTip(line, editor);
    setCursor(toolTip == null ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    if (toolTip == null) {
      lastGutterToolTipLine = -1;
      controller.cancelTooltip(TOOLTIP_GROUP, e, false);
    }
    else {
      lastGutterToolTipLine = line;
      RelativePoint showPoint = new RelativePoint(this, e.getPoint());
      controller.showTooltipByMouseMove(editor,
                                        showPoint,
                                        ((EditorMarkupModel)editor.getMarkupModel()).getErrorStripTooltipRendererProvider().calcTooltipRenderer(toolTip),
                                        false,
                                        TOOLTIP_GROUP,
                                        new HintHint(this, e.getPoint()).setAwtTooltip(true));
    }
  }

  private int getLineAtPoint(final Point clickPoint) {
    return editor.yPositionToLogicalLine(clickPoint.y);
  }
}