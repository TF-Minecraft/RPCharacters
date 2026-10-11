package net.tfminecraft.rpcharacters.plots;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.random.RandomGenerator;

/** Luck draws are zero and luck multipliers are the middle of their range unless values are queued. */
final class ScriptedRandom implements RandomGenerator {
    final Deque<Integer> ints = new ArrayDeque<>();
    final Deque<Double> doubles = new ArrayDeque<>();
    private int counter;

    @Override
    public long nextLong() { return 0; }

    @Override
    public int nextInt(int bound) { return counter++ % bound; }

    @Override
    public int nextInt(int origin, int bound) {
        int value = ints.isEmpty() ? 0 : ints.poll();
        return Math.max(origin, Math.min(bound - 1, value));
    }

    @Override
    public double nextDouble(double origin, double bound) {
        return doubles.isEmpty() ? (origin + bound) / 2 : doubles.poll();
    }
}
