package fr.delthas.minesweeper.client;

import fr.delthas.javaui.Component;

import java.util.function.Function;

/**
 * A component that captures all key presses on it and forwards it
 * to a simple listener
 */
public class CaptureComponent extends Component {
  private Function<Integer, Boolean> listener;
  
  public CaptureComponent() {
  }
  
  public CaptureComponent(Function<Integer, Boolean> listener) {
    this.listener = listener;
  }
  
  @Override
  protected boolean pushKeyButton(double x, double y, int key, boolean down, long time) {
    if (listener != null && down) {
      return listener.apply(key);
    }
    return false;
  }
  
  public void setListener(Function<Integer, Boolean> listener) {
    this.listener = listener;
  }
}
