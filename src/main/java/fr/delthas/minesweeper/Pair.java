package fr.delthas.minesweeper;

import java.util.Objects;

public class Pair<T, U> {
  
  private T t;
  private U u;
  
  public Pair(T t, U u) {
    this.t = t;
    this.u = u;
  }
  
  public T getFirst() {
    return t;
  }
  
  public U getSecond() {
    return u;
  }
  
  @Override
  public boolean equals(Object o) {
    if (this == o) { return true; }
    if (o == null || getClass() != o.getClass()) { return false; }
    Pair<?, ?> pair = (Pair<?, ?>) o;
    return Objects.equals(t, pair.t) &&
            Objects.equals(u, pair.u);
  }
  
  @Override
  public int hashCode() {
    return Objects.hash(t, u);
  }
}
