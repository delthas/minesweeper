package fr.delthas.minesweeper.client;

import fr.delthas.javaui.*;
import fr.delthas.javaui.Button;
import fr.delthas.javaui.Component;
import fr.delthas.javaui.Font;
import fr.delthas.javaui.FontMetrics;
import fr.delthas.javaui.Image;
import fr.delthas.javaui.Label;
import fr.delthas.javaui.TextField;
import fr.delthas.minesweeper.Pair;

import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;

/**
 * The main class for the (small) demo project Minesweeper
 *
 * The project is designed to be very small and concise (~600 lines) and makes use
 * of my library JavaUI to draw images, text, play sound, ... easily.
 *
 * There is no Javadoc for Minesweeper functions since this project is not a library
 * and does not have any public API.
 *
 * @author delthas
 */
public class Minesweeper {
  
  
  // the size of the grid/field (assumed to be square)
  private static final int GRID_SIZE = 13;
  
  // the count of mines to be put in the field
  private static final int MINE_COUNT = (int) (0.15 * GRID_SIZE * GRID_SIZE);
  
  // the url of the server (for leaderboard purposes)
  private static final String SERVER_URL = "http://delthas.fr:5784";
  
  // storing mine data efficiently with a single int:
  // most significant bits are flags (shown? is mine? is flag? is clicked on mine?)
  // least significant bits represent the count of neighbours which are mines (0 <= c <= 8)
  // rationale: use a compact int[] without indirections to improve data locality for caching
  // to check a flag, use e.g. (cell & FLAG_SHOWN != 0) (is the cell shown?)
  // to flip a flag, use e.g. cell ^= FLAG_SHOWN
  // to get the count of nearby cells, keep only the LSBits, e.g. cell & 0xF (because c <= 8 <= 0xF)
  // to increment the count of nearby mine cells, simply increment the cell! e.g. ++cell
  private static final int FLAG_SHOWN = 1 << 29, FLAG_MINE = 1 << 28, FLAG_FLAG = 1 << 27, FLAG_MINEFAIL = 1 << 26;
  
  // the mine field (no int[][] to avoid indirections!)
  // "field(x,y)" is field[x + GRID_SIZE * y]
  private int[] field = new int[GRID_SIZE * GRID_SIZE];
  
  // need an integer queue for uncovering cascade
  // Integer boxing indirection, but performance gains not worth writing a
  // specialized fast int arraydeque implementation
  private ArrayDeque<Integer> cascadeQueue = new ArrayDeque<>(100);
  
  private ExecutorService scoreUploader = Executors.newSingleThreadExecutor();
  private Random random = new Random();
  private Preferences prefs = Preferences.userNodeForPackage(Minesweeper.class);
  
  private Layer layerTitle;
  private Layer layerMain;
  private Component minePanel;
  
  // efficient way of accessing stored images by their name/"handle": SoundEffect/TileImage enums
  private Sound[] sounds = new Sound[SoundEffect.values().length];
  private Texture[] images = new Texture[TileImage.values().length];
  
  private volatile boolean closeRequested = false;
  private long startTime;
  private int bombsLeft;
  private String username;
  private int clicks = 0;
  private int tilesLeft = -1;
  // string: username, long: time to clear in nanos
  private List<Pair<String, Long>> scores;
  private volatile long scoreUpdateTime;
  private volatile Thread scoreUpdateThread;
  // rank -2 is error, -1 is waiting, 0 is uploading, >0 is last uploaded rank
  private volatile int rank = -1;

  // entry point: start a single instance of the game
  public static void main(String[] args) throws Exception {
    new Minesweeper().start();
  }

  private void start() {
    init();
    loop();
    destroy();
  }
  
  private void init() {
    // init score fetching thread
    scoreUpdateThread = new Thread(() -> {
      while(true) {
        List<String> scores = readServer("/top");
        if(scores != null) {
          // response: lines in "<nano_time> <id>" format
          List<Pair<String, Long>> parsed = new ArrayList<>();
          for(String score : scores) {
            int sep = score.indexOf(' ');
            if(sep == -1) continue;
            try {
              long time = Long.parseLong(score.substring(0, sep));
              parsed.add(new Pair<>(score.substring(sep + 1), time));
            } catch(NumberFormatException ex) {
              continue;
            }
          }
          Minesweeper.this.scores = parsed;
          scoreUpdateTime = System.nanoTime();
        }
        // fetch new scores every second
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ex) {
          // shut down the thread on interrupt request
          return;
        }
      }
    });
    scoreUpdateThread.setName("score-update");
    scoreUpdateThread.setDaemon(true);
    scoreUpdateThread.start();
    
    // init openal and load sounds into the sound card memory
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
    
    // init opengl and load images into the gpu memory
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
    
    // below: creating all the ui components
    //
    // layerTitle is the title screen layer/panel
    // layerMain is the main game screen
    // all the render/input processing definition is defined here
    
    // == title layer ==
    layerTitle = new Layer();
    
    // good-looking (heh) title screen text/header
    layerTitle.addComponent(0, Ui.getHeight() * 6 / 8, Ui.getWidth(), Ui.getHeight() / 8, new Component() {
      int frame = 0;
      
      @Override
      protected void render(InputState inputState, Drawer drawer) {
        frame = (frame + 1) % 100;
        // make the text and colours change over time!
        String text = (frame % 10) < 5 ? "> MINESWEEPER <" : "< MINESWEEPER >";
        drawer.setColor(Color.getHSBColor(frame / 100f, 1f, 1f));
        drawer.text(getWidth() / 2, getHeight() / 2, text, Font.COMIC, 72).centered(true, true).draw();
      }
    });
    
    // start button
    Button start = new Button("> start <");
    start.setListener((button, x, y) -> play());
    layerTitle.addComponent(Ui.getWidth() * 4 / 10, Ui.getHeight() * 1 / 9, Ui.getWidth() * 2 / 10, Ui.getHeight() / 15, start);
    
    // username text field
    TextField username = new TextField();
    username.setHintText("> enter username <");
    username.setPredicate(u -> {
      start.setEnabled(u != null && !u.isEmpty());
      Minesweeper.this.username = u;
      return true;
    });
    layerTitle.addComponent(Ui.getWidth() * 4 / 10, Ui.getHeight() * 4 / 9, Ui.getWidth() * 2 / 10, Ui.getHeight() / 15, username);
    
    // capture key presses with a fullscreen capture component
    layerTitle.addComponent(new CaptureComponent(key -> {
      if (key == Key.KEY_ESCAPE) { closeRequested = true; }
      if (key == Key.KEY_ENTER) { play(); }
      return false;
    }));
    
    // load username from registry if already entered on last run
    String defaultUsername = prefs.get("username", "");
    if (!defaultUsername.isEmpty()) {
      username.setText(defaultUsername);
    } else {
      start.setEnabled(false);
    }
    
    // show title layer!
    layerTitle.push();
    
    // == main layer ==
    layerMain = new Layer();
    
    // make sure we do not draw the title layer below it
    layerMain.setOpaque(true);
    
    // splitting the screen in two parts:
    // lhs: minefield, rhs: various info (time, scores, ...)
    // since there's no complex layout support, we'll do plenty of layout arithmetic
    // by hand! (actually this is better since we can place components precisely)
    int maxDimension = Integer.min(Ui.getHeight() - 100, Ui.getWidth() - 400);
    int sidePanelOffset = (Ui.getWidth() + maxDimension * 2) / 3 + 50;
    
    // the main minefield component
    // can render mines, flags, ..., listen to mouse presses on it
    minePanel = new Component() {
      // the inner size (without the 1px border) of a cell
      int size = (int) ((maxDimension - 100 - 201) / GRID_SIZE - 1);
      // the total size of the board
      int totalSize = GRID_SIZE * (size + 1) + 1;
      // the best font size to draw mine neighbor count
      int fontSize = -1;
      
      @Override
      protected boolean pushMouseButton(double x, double y, int button, boolean down, long time) {
        if (!down) { return false; }
        // ignore inputs once we've cleared the board
        if (startTime == 0 && clicks > 0) { return false; }
        // computing which cell we clicked on...
        int i = (int) (x - ((getWidth() - totalSize) / 2));
        int j = (int) (y - ((getHeight() - totalSize) / 2));
        if (i < 0 || i >= totalSize || j < 0 || j >= totalSize || i % (size + 1) == 0 || j % (size + 1) == 0) {
          return false;
        }
        int pos = i / (size + 1) + j / (size + 1) * GRID_SIZE;
        int tile = field[pos];
        if (button == Mouse.MOUSE_LEFT && (tile & FLAG_SHOWN) == 0) {
          // left click! try to uncover the cell
          if (clicks == 0) {
            // on first click, try to create a board with no mines
            // around the cell that was clicked on
            do {
              resetBoard();
            } while (((tile = field[pos]) & FLAG_MINE) != 0 || ((tile & 0xF /* 0xF >= 8 */) != 0));
          }
          clicks++;
          if ((tile & FLAG_MINE) != 0) {
            // woops, clicked on a mine! game over
            field[pos] |= FLAG_MINEFAIL;
            playSound(SoundEffect.FAIL);
            startTime = 0;
            for (int k = 0; k < field.length; k++) {
              field[k] |= FLAG_SHOWN;
            }
          } else {
            // clicked on an unmined cell
            
            // use the cascade algorithm to uncover other unmined cells
            // do not use recursion to avoid stack overflow
            cascadeQueue.add(pos);
            Integer next;
            while ((next = cascadeQueue.poll()) != null) {
              pos = next; // explicit unboxing
              if ((field[pos] & FLAG_SHOWN) != 0) { continue; }
              field[pos] |= FLAG_SHOWN;
              tilesLeft--;
              if ((field[pos] & 0xF) == 0 /* 0xF >= 8 */) {
                // verbose but efficient neighbour search
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
              // no tiles left to uncover! success
              playSound(SoundEffect.SUCCESS);
              uploadScore(Minesweeper.this.username,System.nanoTime() - startTime);
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
          // let's place/remove (actually toggle) a flag
          playSound(SoundEffect.FLAG);
          if ((tile & FLAG_FLAG) != 0) {
            bombsLeft++;
          } else {
            bombsLeft--;
          }
          // fancy bit-flipping
          field[pos] ^= FLAG_FLAG;
          return true;
        }
        return true;
      }
      
      @Override
      protected void render(InputState inputState, Drawer drawer) {
        // on first run try to find the best font size to draw the cell numbers
        // (the largest one that fits inside the cell)
        if (fontSize == -1) {
          fontSize = 20;
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
        drawer.rectangle(0, 0, getWidth(), getHeight()).draw();
        
        // draw pretty rectangles
        double offset = (inputState.getMouseX(this) - getWidth() / 2) * 3 / getWidth();
        double xOffset = offset >= 0 ? 100 * Math.expm1(-offset) : -100 * Math.expm1(offset);
        offset = (inputState.getMouseY(this) - getHeight() / 2) * 3 / getHeight();
        double yOffset = offset >= 0 ? 100 * Math.expm1(-offset) : -100 * Math.expm1(offset);
        int rectangleCount = 10;
        for (int i = rectangleCount; i > 0; i--) {
          drawer.setColor(Color.getHSBColor(0, 0, (rectangleCount - i) * (rectangleCount - i) / (1.2f * rectangleCount * rectangleCount)));
          drawer.rectangle(getWidth() / 2 + xOffset * i / rectangleCount, getHeight() / 2 + yOffset * i / rectangleCount, totalSize, totalSize).centered(true).draw();
          drawer.setColor(Color.BLACK);
          drawer.rectangle(getWidth() / 2 + xOffset * i / rectangleCount, getHeight() / 2 + yOffset * i / rectangleCount, totalSize - 2, totalSize - 2).centered(true).draw();
        }
  
        int xMin = (int) ((getWidth() - totalSize) / 2);
        int xMax = xMin + totalSize;
        int yMin = (int) ((getHeight() - totalSize) / 2);
        int yMax = yMin + totalSize;
        
        // clear main bg
        drawer.setColor(Color.WHITE);
        drawer.rectangle(xMin, yMin, totalSize, totalSize).draw();
        
        // draw lines
        drawer.setColor(Color.BLACK);
        int x = xMin - (size + 1);
        int y = yMin - (size + 1);
        for (int i = 0; i <= GRID_SIZE + 1; i++) {
          x += size + 1;
          y += size + 1;
          drawer.line(x, yMin, x, yMax).draw();
          drawer.line(xMin, y, xMax, y).draw();
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
              // hidden cells
              drawer.setColor(Color.GRAY);
              drawer.rectangle(x, y, size, size).draw();
              if ((tile & FLAG_FLAG) != 0) {
                // flagged cell? let's draw a flag
                drawer.image(x, y, images[TileImage.FLAG.ordinal()]).size(size, size).draw();
                continue;
              }
              continue;
            }
            // shown cells
            if ((tile & FLAG_MINEFAIL) != 0) {
              // cell with a mine that was stepped on
              drawer.image(x, y, images[TileImage.MINEFAIL.ordinal()]).size(size, size).draw();
              continue;
            }
            if ((tile & FLAG_MINE) != 0) {
              // cell with a mine that was not stepped on (shown on game clear/fail)
              drawer.image(x, y, images[TileImage.MINE.ordinal()]).size(size, size).draw();
              continue;
            }
            // shown cell with no mine: draw count of adjacent tiles
            int sum = tile & 0xF; // 0xF >= 8
            drawer.setColor(sum == 0 ? Color.BLACK : Color.getHSBColor(sum / 8f, 1, 1));
            drawer.text(x + size / 2f, y + size / 2f, Integer.toString(sum), Font.COMIC, fontSize).centered(true, true).draw();
          }
        }
      }
    };
    layerMain.addComponent(50, 25 + (Ui.getHeight() - 25 - (maxDimension - 100)) / 2, maxDimension - 100, maxDimension - 100, minePanel);
    
    // simple vertical sep line
    layerMain.addComponent(new Component() {
      @Override
      protected void render(InputState inputState, Drawer drawer) {
        drawer.setColor(Color.GRAY);
        drawer.line(maxDimension + 50, 0, maxDimension + 50, Ui.getHeight()).draw();
      }
    });
  
    // add a helper label
    layerMain.addComponent(sidePanelOffset, Ui.getHeight() * 8 / 10, 300, 50, new FixedPositionLabel("> to restart < : press enter"));
    
    // add an "elapsed time" label
    layerMain.addComponent(sidePanelOffset, Ui.getHeight() * 7 / 10, 300, 50, new FixedPositionLabel() {
      @Override
      protected void render(InputState inputState, Drawer drawer) {
        if(clicks == 0) {
          setText("> time < :");
        } else if (startTime > 0) {
          String text = String.format("%.1f", (System.nanoTime() - startTime) / 1e9);
          setText("> time < : " + text + "s");
        }
        super.render(inputState, drawer);
      }
    });
    
    // add a "bombs left" label
    layerMain.addComponent(sidePanelOffset, Ui.getHeight() * 6 / 10, 300, 50, new FixedPositionLabel() {
      @Override
      protected void render(InputState inputState, Drawer drawer) {
        setText("> bombs left < : " + bombsLeft);
        super.render(inputState, drawer);
      }
    });
    
    // add a leaderboard label
    layerMain.addComponent(sidePanelOffset, Ui.getHeight() * 1 / 10, 300, Ui.getHeight() * 4 / 10, new Component() {
      private long lastUpdateTime = 0;
      private int frame;
      private List<String> cachedNames = new ArrayList<>();
      private List<String> cachedScores = new ArrayList<>();
      private float lineHeight = Ui.getUi().getFontMetrics(Font.COMIC, 16).getLineHeight();
      @Override
      protected void render(InputState inputState, Drawer drawer) {
        frame++;
        if(scoreUpdateTime == 0) {
          // draw a pretty animation if the scores weren't successfully fetched yet
          float brightness = (100 - frame % 200) * (100 - frame % 200) / 10000f;
          drawer.setColor(Color.getHSBColor(0, 0, brightness));
          drawer.text(getWidth() / 2, getHeight() / 2, (frame % 20) < 10 ? ">loading scores<" : "<loading scores>", Font.COMIC, 16).centered(true, true).draw();
          drawer.setColor(Color.getHSBColor(0, 0, 0.8f - brightness / 2));
          drawer.ring(getWidth() / 2, getHeight() / 2, 150 - 20 * brightness, 10 * brightness + 1).centered(true).draw();
        } else {
          if(lastUpdateTime < scoreUpdateTime) {
            cachedNames.clear();
            cachedScores.clear();
            for(Pair<String, Long> score : scores) {
              // cache the String.format call into a String for performance
              // (probably not very important though)
              cachedNames.add(score.getFirst());
              cachedScores.add(String.format(":   %.1fs", score.getSecond() / 1e9));
            }
            lastUpdateTime = scoreUpdateTime;
          }
          drawer.setColor(Color.WHITE);
          drawer.rectangle(0, 0, getWidth(), getHeight()).draw();
          drawer.setColor(Color.BLACK);
          drawer.rectangle(1, 1, getWidth() - 2, getHeight() - 2).draw();
          drawer.setColor(Color.WHITE);
          drawer.text(getWidth() / 2, getHeight() - 50, ">top scores<", Font.COMIC, 16).centered(true, false).draw();
          double y = getHeight() - 50 - 2 * lineHeight;
          for(int i=0;i<cachedScores.size() && y > 0; i++, y -= lineHeight) {
            drawer.text(20, y, cachedNames.get(i), Font.COMIC, 16).draw();
            drawer.text(getWidth() / 2 - 10, y, cachedScores.get(i), Font.COMIC, 16).draw();
          }
        }
      }
    });
    
    // add a "score uploading" label
    layerMain.addComponent(sidePanelOffset, Ui.getHeight() * 1 / 10, 300, Ui.getHeight() * 1 / 10, new Component() {
      private long lastChangeTime;
      private int lastRank = -1;
      private int frame = 0;
      @Override
      protected void render(InputState inputState, Drawer drawer) {
        long time = System.nanoTime();
        if(rank != -1) {
          // the rank status changed!
          lastRank = rank;
          lastChangeTime = time;
          rank = -1;
          frame = 0;
        }
        if(lastRank == -1 || time - lastChangeTime > 15000000000L)
          return;
        String text;
        if(lastRank == -2) { // error
          text = "error while uploading score";
        } else if(lastRank == 0) { // uploading
          text = ">uploading score<";
        } else { // lastRank > 0 -> rank on the last play
          text = "congrats! your score ranked #" + lastRank+"!!";
        }
        // fancy text animation!!
        text = (frame++ % 100 < 50) ? (">" + text + "<") : ("<" + text + ">");
        // fade-out animation!!
        float brightness = (time - lastChangeTime > 9000000000L) ? 1 - (time - lastChangeTime - 9000000000L) / 6000000000f: 1;
        drawer.setColor(Color.getHSBColor(0, 0, brightness));
        drawer.text(getWidth() / 2, getHeight() / 2, text, Font.COMIC, 16).centered(true, true).draw();
      }
    });
    
    // usual capture screenwide component to capture keypresses
    layerMain.addComponent(new CaptureComponent(key -> {
      if (key == Key.KEY_ESCAPE) { closeRequested = true; }
      if (key == Key.KEY_ENTER || key == Key.KEY_KP_ENTER) { play(); }
      return false;
    }));
  }
  
  private void uploadScore(String id, long time) {
    rank = 0; // uploading
    scoreUploader.submit(() -> {
      List<String> response = readServer("/add?id="+id+"&score="+time);
      if(response == null || response.isEmpty()) {
        rank = -2; // error
        return;
      }
      try {
        rank = Integer.parseInt(response.get(0)); // rank on the last play
      } catch(NumberFormatException ex) {
        rank = -2; // error
      }
    });
  }
  
  private void play() {
    if (Ui.getUi().top() != layerMain) {
      // coming from the title screen
      // save username in the registry
      prefs.put("username", username);
      layerMain.push();
      playSound(SoundEffect.START);
    } else {
      playSound(SoundEffect.CLICK);
    }
    // reset game variables
    clicks = 0;
    startTime = 0;
    tilesLeft = field.length - MINE_COUNT;
    bombsLeft = MINE_COUNT;
    Arrays.fill(field, 0);
  }
  
  private void resetBoard() {
    Arrays.fill(field, 0);
    // add mines on the board
    for (int i = 0; i < MINE_COUNT; i++) {
      int pos;
      do {
        pos = random.nextInt(field.length);
      } while ((field[pos] & FLAG_MINE) != 0);
      field[pos] = FLAG_MINE;
      
      // notify neighbours that a mine was added by increasing its count, verbose but efficient
      
      // notice that despite the efficient mine information storage,
      // incrementing the nearby mine count is still a simple inc operation
      
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
    // small auxiliary function to make the mine notification less verbose
    // too bad that java doesn't let us declare inner functions!!
    if (position < 0 || position >= field.length) { return; }
    ++field[position];
  }
  
  private void playSound(SoundEffect soundEffect) {
    // ignore gracefully if we failed loading sounds
    if (sounds == null) { return; }
    SoundManager.getSoundManager().playSound(sounds[soundEffect.ordinal()]);
  }
  
  private void loop() {
    // main loop!
    while (!closeRequested) {
      Ui.getUi().input();
      Ui.getUi().render();
    }
  }
  
  private void destroy() {
    // destory all native resources nicely
    SoundManager.getSoundManager().destroy();
    for (Texture texture : images) {
      texture.destroy();
    }
    Ui.getUi().destroy();
    scoreUpdateThread.interrupt();
    scoreUploader.shutdownNow();
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
  
  private static List<String> readServer(String path) {
    // small wrapper over HTTP GET requests for the custom telnet-like-over-http server api
    // json could be a reasonable choice in the long term
    List<String> lines = new ArrayList<>();
    try(BufferedReader reader = new BufferedReader(new InputStreamReader(new URL(SERVER_URL + path).openStream()))) {
      String line;
      while((line = reader.readLine()) != null) {
        lines.add(line);
      }
    } catch(IOException ex) {
      return null;
    }
    return lines;
  }
}
