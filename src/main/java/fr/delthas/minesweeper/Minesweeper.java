package fr.delthas.minesweeper;

import fr.delthas.javaui.*;
import fr.delthas.javaui.Button;
import fr.delthas.javaui.Component;
import fr.delthas.javaui.Font;
import fr.delthas.javaui.FontMetrics;
import fr.delthas.javaui.Image;
import fr.delthas.javaui.Label;
import fr.delthas.javaui.TextField;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Random;
import java.util.prefs.Preferences;

public class Minesweeper {
  // compact mine information storage:
  // most significant bits are flags (shown? is mine? is flag? is clicked on mine?)
  // least significant bits represents the count of neighbours which are mines (0 <= c <= 8)
  // rationale: use a compact int[] without indirections to improve data locality for caching
  private static final int FLAG_SHOWN = 1 << 29, FLAG_MINE = 1 << 28, FLAG_FLAG = 1 << 27, FLAG_MINEFAIL = 1 << 26;
  private static final int GRID_SIZE = 13; // assume square field
  private static final int MINE_COUNT = (int) (0.05 * GRID_SIZE * GRID_SIZE);
  private volatile boolean closeRequested = false;
  // Integer boxing indirection, but performance gains not worth writing a
  // specialized fast int arraydeque implementation
  private ArrayDeque<Integer> cascadeQueue = new ArrayDeque<>(100);
  private Random random = new Random();
  private Preferences prefs = Preferences.userNodeForPackage(Minesweeper.class);
  private Layer layerTitle;
  private Layer layerMain;
  private Component minePanel;
  private long startTime;
  private int bombsLeft;
  private String username;
  private int[] field = new int[GRID_SIZE * GRID_SIZE];
  private Sound[] sounds = new Sound[SoundEffect.values().length];
  private Texture[] images = new Texture[TileImage.values().length];
  private int clicks = 0;
  private int tilesLeft = -1;

  public static void main(String[] args) throws Exception {
    new Minesweeper().start();
  }

  private void start() {
    init();
    loop();
    destroy();
  }
  
  private void init() {
    SoundManager.getSoundManager().create();
    
    try {
      for (SoundEffect soundEffect : SoundEffect.values()) {
        sounds[soundEffect.ordinal()] = Sound.createSound("/" + soundEffect.name + ".ogg");
      }
    } catch (IOException ex) {
      System.err.println("Error while loading sounds, disabling sounds.");
      ex.printStackTrace();
      sounds = null;
    }
    
    Ui.getUi().create("Minesweeper");
    
    try {
      Atlas atlas = null;
      for (TileImage tileImage : TileImage.values()) {
        Image image = Image.createImage("/" + tileImage.name + ".png", true);
        if (atlas == null) {
          atlas = Atlas.createAtlas(image.getWidth(), image.getHeight(), TileImage.values().length, true);
        }
        images[tileImage.ordinal()] = atlas.uploadImage(image);
        image.close();
      }
    } catch (IOException ex) {
      System.err.println("Fatal error while loading images!");
      ex.printStackTrace();
      return;
    }
    
    layerTitle = new Layer();
    layerTitle.addComponent(0, Ui.getHeight() * 6 / 8.0, Ui.getWidth(), Ui.getHeight() / 8.0, new Component() {
      int frame = 0;
      
      @Override
      protected void render(InputState inputState, Drawer drawer) {
        frame = (frame + 1) % 100;
        String text = (frame % 10) < 5 ? "> MINESWEEPER <" : "< MINESWEEPER >";
        drawer.setColor(Color.getHSBColor(frame / 100f, 1f, 1f));
        drawer.drawText(getWidth() / 2, getHeight() / 2, text, Font.COMIC, 72, true, true);
      }
    });
    Button start = new Button("> start <");
    start.setListener((button, x, y) -> play());
    layerTitle.addComponent(Ui.getWidth() * 4 / 10, Ui.getHeight() * 1 / 9, Ui.getWidth() * 2 / 10, Ui.getHeight() / 15, start);
    TextField username = new TextField();
    username.setHintText("> enter username <");
    username.setPredicate(u -> {
      start.setEnabled(u != null && !u.isEmpty());
      Minesweeper.this.username = u;
      return true;
    });
    layerTitle.addComponent(Ui.getWidth() * 4 / 10, Ui.getHeight() * 4 / 9, Ui.getWidth() * 2 / 10, Ui.getHeight() / 15, username);
    layerTitle.addComponent(new CaptureComponent(key -> {
      if (key == Key.KEY_ESCAPE) { closeRequested = true; }
      return false;
    }));
    
    String defaultUsername = prefs.get("username", "");
    if (!defaultUsername.isEmpty()) {
      username.setText(defaultUsername);
    } else {
      start.setEnabled(false);
    }
    
    layerTitle.push();
    
    layerMain = new Layer();
    layerMain.setOpaque(true);
    
    int maxDimension = Integer.min(Ui.getHeight() - 100, Ui.getWidth() - 400);
    int sidePanelOffset = (Ui.getWidth() + maxDimension * 2) / 3 + 50;
    
    minePanel = new Component() {
      int size = (int) ((maxDimension - 100 - 201) / GRID_SIZE - 1);
      int totalSize = GRID_SIZE * (size + 1) + 1;
      int fontSize = -1;
      
      @Override
      protected boolean pushMouseButton(double x, double y, int button, boolean down, long time) {
        if (!down) { return false; }
        if (startTime == 0 && clicks > 0) { return false; }
        int i = (int) (x - ((getWidth() - totalSize) / 2));
        int j = (int) (y - ((getHeight() - totalSize) / 2));
        if (i < 0 || i >= totalSize || j < 0 || j >= totalSize || i % (size + 1) == 0 || j % (size + 1) == 0) {
          return false;
        }
        int pos = i / (size + 1) + j / (size + 1) * GRID_SIZE;
        int tile = field[pos];
        if (button == Mouse.MOUSE_LEFT && (tile & FLAG_SHOWN) == 0) {
          if (clicks == 0) {
            do {
              resetBoard();
            } while (((tile = field[pos]) & FLAG_MINE) != 0 || ((tile & 0xF /* 0xF >= 8 */) != 0));
          }
          clicks++;
          if ((tile & FLAG_MINE) != 0) {
            field[pos] |= FLAG_MINEFAIL;
            playSound(SoundEffect.FAIL);
            startTime = 0;
            for (int k = 0; k < field.length; k++) {
              field[k] |= FLAG_SHOWN;
            }
          } else {
            // cascade algorithm
            // do not use recursion to avoid stack overflow
            cascadeQueue.add(pos);
            Integer next;
            while ((next = cascadeQueue.poll()) != null) {
              pos = next; // explicit unboxing
              if ((field[pos] & FLAG_SHOWN) != 0) { continue; }
              field[pos] |= FLAG_SHOWN;
              tilesLeft--;
              if ((field[pos] & 0xF) == 0 /* 0xF >= 8 */) {
                // verbose but mandatory and efficient neighbour search
                if (cascadeCheckTile(pos - GRID_SIZE)) { cascadeQueue.add(pos - GRID_SIZE); }
                if (cascadeCheckTile(pos + GRID_SIZE)) { cascadeQueue.add(pos + GRID_SIZE); }
                
                if (pos % GRID_SIZE != 0) {
                  if (cascadeCheckTile(pos - 1)) { cascadeQueue.add(pos - 1); }
                  if (cascadeCheckTile(pos - GRID_SIZE - 1)) { cascadeQueue.add(pos - GRID_SIZE - 1); }
                  if (cascadeCheckTile(pos + GRID_SIZE - 1)) { cascadeQueue.add(pos + GRID_SIZE - 1); }
                }
                if (pos % GRID_SIZE != GRID_SIZE - 1) {
                  if (cascadeCheckTile(pos - GRID_SIZE + 1)) { cascadeQueue.add(pos - GRID_SIZE + 1); }
                  if (cascadeCheckTile(pos + GRID_SIZE + 1)) { cascadeQueue.add(pos + GRID_SIZE + 1); }
                  if (cascadeCheckTile(pos + 1)) { cascadeQueue.add(pos + 1); }
                }
              }
            }
            if (tilesLeft == 0) {
              playSound(SoundEffect.SUCCESS);
              startTime = 0;
              for (int k = 0; k < field.length; k++) {
                field[k] |= FLAG_SHOWN;
              }
            } else {
              playSound(SoundEffect.CLICK);
            }
          }
          return true;
        }
        if (button == Mouse.MOUSE_RIGHT && (tile & FLAG_SHOWN) == 0) {
          playSound(SoundEffect.FLAG);
          if ((tile & FLAG_FLAG) != 0) {
            bombsLeft++;
          } else {
            bombsLeft--;
          }
          field[pos] ^= FLAG_FLAG;
          return true;
        }
        return true;
      }
      
      @Override
      protected void render(InputState inputState, Drawer drawer) {
        // find best font size (only once)
        if (fontSize == -1) {
          fontSize = 16;
          outer: for (; fontSize > 6; fontSize--) {
            FontMetrics metrics = drawer.getFontMetrics(Font.COMIC, fontSize);
            // is the font too big to fit (vertically)?
            if (metrics.getAscent() - metrics.getDescent() >= size - 2) { continue; }
            // is the font too big to fit (horizontally)?
            float[] sizes = drawer.getTextPositions("0123456789", Font.COMIC, fontSize);
            for (int i = 1; i < sizes.length; i++) {
              if (sizes[i] - sizes[i - 1] > size - 2) { continue outer; }
            }
            // it fits!
            break;
          }
          if (fontSize == 6) {
            // font way too small to be readable!
            System.err.println("Grid size too large, cells too small to be readable. Increase the grid size.");
            closeRequested = true;
            return;
          }
        }
        
        // clear bg
        drawer.setColor(Color.BLACK);
        drawer.fillRectangle(0, 0, getWidth(), getHeight(), false);
        
        // draw pretty rectangles
        double offset = (inputState.getMouseX() - getWidth() / 2) * 2 / getWidth();
        double xOffset = offset >= 0 ? 100 * Math.expm1(-offset) : -100 * Math.expm1(offset);
        offset = (inputState.getMouseY() - getHeight() / 2) * 2 / getHeight();
        double yOffset = offset >= 0 ? 100 * Math.expm1(-offset) : -100 * Math.expm1(offset);
        for (int i = 10; i > 0; i--) {
          drawer.setColor(Color.getHSBColor(0, 0, (10f - i) * (10f - i) / 120));
          drawer.fillRectangle(getWidth() / 2 + xOffset * i / 10, getHeight() / 2 + yOffset * i / 10, totalSize, totalSize, true);
          drawer.setColor(Color.BLACK);
          drawer.fillRectangle(getWidth() / 2 + xOffset * i / 10, getHeight() / 2 + yOffset * i / 10, totalSize - 2, totalSize - 2, true);
        }
        
        // clear main bg
        drawer.setColor(Color.WHITE);
        drawer.fillRectangle(getWidth() / 2, getHeight() / 2, totalSize, totalSize, true);
        
        // draw lines
        drawer.setColor(Color.BLACK);
        double xMin = (getWidth() - totalSize) / 2;
        double xMax = (getWidth() + totalSize) / 2;
        double x = xMin - (size + 1);
        double yMin = (getHeight() - totalSize) / 2;
        double yMax = (getHeight() + totalSize) / 2;
        double y = yMin - (size + 1);
        for (int i = 0; i <= GRID_SIZE + 1; i++) {
          x += size + 1;
          y += size + 1;
          drawer.drawLine(x, yMin, x, yMax);
          drawer.drawLine(xMin, y, xMax, y);
        }
        
        // draw tiles
        int idx = 0;
        y = yMin + 1 - (size + 1);
        for (int i = 0; i < GRID_SIZE; i++) {
          y += size + 1;
          x = xMin + 1 - (size + 1);
          for (int j = 0; j < GRID_SIZE; j++) {
            int tile = field[idx++];
            x += size + 1;
            if ((tile & FLAG_SHOWN) == 0) {
              drawer.setColor(Color.GRAY);
              drawer.fillRectangle(x, y, size, size, false);
              if ((tile & FLAG_FLAG) != 0) {
                drawer.drawImage(x, y, size, size, images[TileImage.FLAG.ordinal()], false);
                continue;
              }
              // hidden tile with no user flag
              continue;
            }
            if ((tile & FLAG_MINEFAIL) != 0) {
              drawer.drawImage(x, y, size, size, images[TileImage.MINEFAIL.ordinal()], false);
              continue;
            }
            if ((tile & FLAG_MINE) != 0) {
              drawer.drawImage(x, y, size, size, images[TileImage.MINE.ordinal()], false);
              continue;
            }
            // shown tile without flags: draw count of adjacent tiles
            int sum = tile & 0xF; // 0xF >= 8
            drawer.setColor(sum == 0 ? Color.BLACK : Color.getHSBColor(sum / 8f, 1, 1));
            drawer.drawText(x + size / 2f, y + size / 2f, Integer.toString(sum), Font.COMIC, fontSize, true, true);
          }
        }
      }
    };
    
    layerMain.addComponent(50, 25 + (Ui.getHeight() - 25 - (maxDimension - 100)) / 2, maxDimension - 100, maxDimension - 100, minePanel);
    layerMain.addComponent(new Component() {
      @Override
      protected void render(InputState inputState, Drawer drawer) {
        drawer.setColor(Color.GRAY);
        drawer.drawLine(maxDimension + 50, 35, maxDimension + 50, Ui.getHeight());
      }
    });
    layerMain.addComponent(10, 10, Ui.getWidth() - 10, 25, new Label("> left click < : step   >-<   > right click < : flag   >-<   > enter < : restart   >-<   > escape < : quit"));
    layerMain.addComponent(sidePanelOffset, Ui.getHeight() * 8 / 10, 300, 50, new FixedPositionLabel("> time < :") {
      @Override
      protected void render(InputState inputState, Drawer drawer) {
        if (startTime > 0) {
          String text = Long.toString((System.nanoTime() - startTime) / 1000000000L);
          setText("> time < : " + text + "s");
        }
        super.render(inputState, drawer);
      }
    });
    layerMain.addComponent(sidePanelOffset, Ui.getHeight() * 7 / 10, 300, 50, new FixedPositionLabel() {
      @Override
      protected void render(InputState inputState, Drawer drawer) {
        setText("> bombs left < : " + bombsLeft);
        super.render(inputState, drawer);
      }
    });
    layerMain.addComponent(new CaptureComponent(key -> {
      if (key == Key.KEY_ESCAPE) { closeRequested = true; }
      if (key == Key.KEY_ENTER) { play(); }
      return false;
    }));
  }
  
  private void play() {
    if (Ui.getUi().top() != layerMain) {
      prefs.put("username", username);
      layerMain.push();
      playSound(SoundEffect.START);
    }
    
    clicks = 0;
    tilesLeft = field.length - MINE_COUNT;
  }
  
  private void resetBoard() {
    Arrays.fill(field, 0);
    for (int i = 0; i < MINE_COUNT; i++) {
      int pos;
      do {
        pos = random.nextInt(field.length);
      } while ((field[pos] & FLAG_MINE) != 0);
      field[pos] = FLAG_MINE;
      
      // notify neighbours that a mine was added by increasing its count
      incrementTile(pos - GRID_SIZE);
      incrementTile(pos + GRID_SIZE);
      
      if (pos % GRID_SIZE != 0) {
        incrementTile(pos - 1);
        incrementTile(pos - GRID_SIZE - 1);
        incrementTile(pos + GRID_SIZE - 1);
      }
      if (pos % GRID_SIZE != GRID_SIZE - 1) {
        incrementTile(pos - GRID_SIZE + 1);
        incrementTile(pos + GRID_SIZE + 1);
        incrementTile(pos + 1);
      }
    }
    
    startTime = System.nanoTime();
    bombsLeft = MINE_COUNT;
  }
  
  private boolean cascadeCheckTile(int position) {
    if (position < 0 || position >= field.length) { return false; }
    return (field[position] & FLAG_SHOWN) == 0;
  }
  
  private void incrementTile(int position) {
    if (position < 0 || position >= field.length) { return; }
    ++field[position];
  }
  
  private void playSound(SoundEffect soundEffect) {
    if (sounds == null) { return; }
    SoundManager.getSoundManager().playSound(sounds[soundEffect.ordinal()]);
  }
  
  private void loop() {
    while (!closeRequested) {
      Ui.getUi().input();
      Ui.getUi().render();
    }
  }
  
  private void destroy() {
    SoundManager.getSoundManager().destroy();
    for (Texture texture : images) {
      texture.destroy();
    }
    Ui.getUi().destroy();
  }
  
  private enum SoundEffect {
    CLICK("click"), FAIL("fail"), FLAG("flag"), START("start"), SUCCESS("success");
    
    private final String name;
    
    SoundEffect(String name) {
      this.name = name;
    }
  }
  
  private enum TileImage {
    FLAG("flag"), MINE("mine"), MINEFAIL("minefail");
    
    private final String name;
    
    TileImage(String name) {
      this.name = name;
    }
  }
}
