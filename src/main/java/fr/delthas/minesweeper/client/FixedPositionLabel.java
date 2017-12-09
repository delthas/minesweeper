package fr.delthas.minesweeper.client;

import fr.delthas.javaui.Component;
import fr.delthas.javaui.Drawer;
import fr.delthas.javaui.Font;
import fr.delthas.javaui.InputState;

import java.awt.*;

/**
 * A label that doesn't center the texts it prints, but rather
 * aligns it on the left, with a width/10 margin
 */
public class FixedPositionLabel extends Component {
  private String text = "";
  
  public FixedPositionLabel() {
  
  }
  
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

  public String getText() {
    return text;
  }
  
  public void setText(String text) {
    this.text = text;
  }
}
