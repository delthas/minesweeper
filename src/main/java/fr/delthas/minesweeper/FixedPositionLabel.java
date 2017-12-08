package fr.delthas.minesweeper;

import fr.delthas.javaui.Component;
import fr.delthas.javaui.Drawer;
import fr.delthas.javaui.Font;
import fr.delthas.javaui.InputState;

import java.awt.*;

public class FixedPositionLabel extends Component {
  private String text = "";
  
  /**
   * Creates a label (enabled), with an empty text.
   */
  public FixedPositionLabel() {
  
  }
  
  /**
   * Creates a label (enabled), with the specified text.
   *
   * @param text The text of the label, to be set, cannot be null (use the empty string ("") instead if needed).
   */
  public FixedPositionLabel(String text) {
    setText(text);
  }
  
  @Override
  protected void render(InputState inputState, Drawer drawer) {
    drawer.setColor(Color.WHITE);
    drawer.fillRectangle(0, 0, getWidth(), getHeight(), false);
    drawer.setColor(Color.BLACK);
    drawer.fillRectangle(1, 1, getWidth() - 2, getHeight() - 2, false);
    drawer.setColor(Color.WHITE);
    drawer.drawText(getWidth() / 10, getHeight() / 2, text, Font.COMIC, 16, false, true);
  }
  
  /**
   * @return The text of the label. No/empty text is returned as the empty string (""), not null.
   */
  public String getText() {
    return text;
  }
  
  /**
   * Sets the text of this label.
   *
   * @param text The text to be set, cannot be null (use the empty string ("") instead if needed).
   */
  public void setText(String text) {
    this.text = text;
  }
}
