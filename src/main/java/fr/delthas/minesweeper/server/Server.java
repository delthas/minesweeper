package fr.delthas.minesweeper.server;

import fr.delthas.minesweeper.Pair;
import spark.Spark;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A (dead simple) HTTP server for the demo Minesweeper project
 *
 * Simply store a leaderboard of best scores (saving it to disk between runs)
 */
public class Server {
  
  private static final Path scoresPath = Paths.get("scores.txt");
  
  // let's keep scores sorted by increasing clear time
  private TreeSet<Pair<String, Long>> scores;
  
  public static void main(String[] args) {
    new Server().start();
  }
  
  private void start() {
    init();
    stop();
  }
  
  private void init() {
    loadScores();
    listen();
  }
  
  private void listen() {
    // init spark routes
    Spark.port(5784);
    Spark.get("/add", (request, response) -> {
      // score adding route
      String name = request.queryParams("id");
      String scoreString = request.queryParams("score");
      if(name == null || name.isEmpty() || scoreString == null || scoreString.isEmpty()) return "";
      long score;
      try {
        score = Long.parseLong(scoreString);
      } catch(NumberFormatException ex) {
        return "";
      }
      Pair<String, Long> pair = new Pair<>(name, score);
      scores.add(pair);
      // find and return current rank of this play
      int rank = scores.headSet(pair).size() + 1;
      return Integer.toString(rank);
    });
    Spark.get("/top", (request, response) -> {
      // top scores fetching route
      // concise score formatting thanks to java 8 stream api!
      return scores.stream().limit(20).map(p -> p.getSecond() + " " + p.getFirst()).collect(Collectors.joining("\r\n"));
    });
    Spark.get("/stop", (request, response) -> {
      // very secret and secure server stopping route!!
      // actually change to a better authorization method for a real project
      String pwd = request.queryParams("pwd");
      if(!"secret_passw!<<>ord".equals(pwd))
        return "";
      stop();
      return "";
    });
  }
  
  private void loadScores() {
    scores = new TreeSet<>(Comparator.comparing(Pair<String, Long>::getSecond));
    try {
      List<String> lines = Files.readAllLines(scoresPath);
      // scores saving format is simply lines of "<nano_time> <username>"
      for(String line : lines) {
        int sep = line.indexOf(' ');
        if(sep == -1) continue;
        try {
          long score = Long.parseLong(line.substring(0, sep));
          scores.add(new Pair<>(line.substring(sep + 1), score));
        } catch(NumberFormatException ex) {
          continue;
        }
      }
    } catch(IOException ex) {
      System.err.println("Error while loading scores, loading without scores: "+ex.getMessage());
      scores.clear();
    }
  }
  
  private void saveScores() {
    try(BufferedWriter writer = Files.newBufferedWriter(scoresPath)) {
      for(Pair<String, Long> score : scores) {
        writer.append(Long.toString(score.getSecond())).append(' ').append(score.getFirst()).append('\n');
      }
    } catch(IOException ex) {
      System.err.println("Error while saving scores, ignoring save: "+ex.getMessage());
    }
  }
  
  private void stop() {
    Spark.stop();
    saveScores();
  }
}
