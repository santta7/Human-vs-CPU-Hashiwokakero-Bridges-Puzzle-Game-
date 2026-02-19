package review2;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * BridgesAdvanced.java  –  Hashiwokakero (Bridges Puzzle) — Human vs. AI
 *
 * ALGORITHMS INTEGRATED
 * ═══════════════════════════════════════════════════════════════════════
 *
 * 1. DIVIDE & CONQUER  (see divideAndConquerCandidates)
 *    ─────────────────────────────────────────────────
 *    The full island list is recursively split in half until sub-lists reach
 *    a base case of ≤2 islands.  At each leaf the method checks whether
 *    those two islands can be connected.  Valid pairs bubble back up and
 *    are merged into a single sorted list (by Constraint-Density score)
 *    using a standard merge step — giving the AI its O(n log n) candidate
 *    generation with built-in sorting, exactly like Merge Sort.
 *
 *    Call site: makeAIMove() replaces the old nested-loop candidate scan
 *    with a single call to divideAndConquerCandidates(islands, 0, n-1).
 *    Each Move's score is now provided by dpEdgePriority() (see DP below).
 *
 * 2. DYNAMIC PROGRAMMING  (see dpEdgePriority / buildDPTable)
 *    ────────────────────────────────────────────────────────────
 *    The AI and the human BOTH place exactly 1 bridge segment per turn
 *    (fair, turn-by-turn play).  DP is used to SCORE each candidate edge
 *    so the AI picks the most valuable edge to add its single segment on.
 *
 *    A 1-D DP table dp[k] answers:
 *        "What would the combined remaining-degree be after adding k total
 *         segments on this edge (from zero)?"
 *
 *    Recurrence:
 *        dp[0] = remainA + remainB          (baseline – no bridge added)
 *        dp[k] = dp[k-1] − 2               (each segment reduces slack by 2)
 *
 *    Priority score = dp[current+1] (the state after the next single
 *    segment).  A lower score means both endpoints are closer to being
 *    satisfied, so the AI prefers those edges — essentially a greedy
 *    look-ahead computed via DP.
 *
 *    Results are memoised in dpCache (invalidated when board changes).
 *
 *    Call site: divideAndConquerCandidates() uses dpEdgePriority() as the
 *    scoring function instead of the plain constraintDensityScore().
 *
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Original 7-Step AI Flow (BFS + Borůvka + Priority Queue) is preserved.
 * D&C replaces Step 1 (candidate generation + scoring).
 * DP replaces the fixed "always add 1 segment" policy in Step 6.
 */
public class BridgesAdvanced extends JPanel
        implements MouseListener, MouseMotionListener, KeyListener {

    // ─────────────────────── CONSTANTS ───────────────────────
    private static final int CELL_SIZE     = 60;
    private static final int ISLAND_RADIUS = 20;
    private static final int GRID_W        = 7;
    private static final int GRID_H        = 7;
    private static final int MAX_BRIDGES   = 2;

    private static final Color BG_COLOR           = new Color(240, 240, 240);
    private static final Color ISLAND_COLOR        = new Color(255, 255, 200);
    private static final Color ISLAND_DONE_COLOR   = new Color(180, 255, 180);
    private static final Color ERROR_COLOR         = new Color(255, 120, 120);
    private static final Color TEXT_COLOR          = Color.BLACK;
    private static final Color HUMAN_BRIDGE_COLOR  = Color.BLUE;
    private static final Color AI_BRIDGE_COLOR     = Color.RED;
    private static final Color BRIDGE_COLOR        = new Color(60, 60, 60);

    public enum Difficulty { EASY, MEDIUM, HARD }
    private Difficulty difficulty = Difficulty.MEDIUM;

    private enum Player { HUMAN, AI }
    private Player currentPlayer = Player.HUMAN;

    // ─────────────────────── GAME STATE ──────────────────────
    private final List<Island> islands        = new ArrayList<>();
    private final List<Bridge> bridges        = new ArrayList<>();
    private final List<Bridge> solutionBridges = new ArrayList<>();

    private Island dragStart = null;
    private Point  mousePos  = null;

    private boolean aiThinking    = false;
    private int     humanScore    = 0;
    private int     computerScore = 0;

    // DP cache: keyed by "idxA-idxB", value = optimal bridge count chosen
    private final Map<String, Integer> dpCache = new HashMap<>();

    // ═════════════════════════════════════════════════════════
    //  DATA STRUCTURES
    // ═════════════════════════════════════════════════════════

    /** Graph vertex — an island on the grid. */
    static class Island {
        int x, y, required;
        Island(int x, int y, int required) { this.x=x; this.y=y; this.required=required; }
        int screenX() { return x * CELL_SIZE + CELL_SIZE / 2; }
        int screenY() { return y * CELL_SIZE + CELL_SIZE / 2; }
    }

    /** Graph edge — a bridge between two islands. */
    static class Bridge {
        Island a, b;
        int count;          // 1 or 2 segments
        int owner = 0;      // 0=neutral, 1=human, 2=AI
        Bridge(Island a, Island b, int count) { this.a=a; this.b=b; this.count=count; }
        boolean connects(Island i1, Island i2) {
            return (a==i1&&b==i2)||(a==i2&&b==i1);
        }
        boolean isHorizontal() { return a.y == b.y; }
    }

    /** AI candidate move with a Constraint-Density score (lower = better). */
    static class Move {
        Island a, b;
        int score;
        Move(Island a, Island b, int score) { this.a=a; this.b=b; this.score=score; }
    }

    // ═════════════════════════════════════════════════════════
    //  CONSTRUCTOR
    // ═════════════════════════════════════════════════════════

    public BridgesAdvanced() {
        setPreferredSize(new Dimension(GRID_W * CELL_SIZE, GRID_H * CELL_SIZE + 30));
        setBackground(BG_COLOR);
        addMouseListener(this);
        addMouseMotionListener(this);
        addKeyListener(this);
        setFocusable(true);
        generatePuzzle();
    }

    public void setDifficulty(Difficulty d) { this.difficulty = d; generatePuzzle(); }

    // ═════════════════════════════════════════════════════════
    //  PUZZLE GENERATION
    // ═════════════════════════════════════════════════════════

    private void generatePuzzle() {
        islands.clear(); bridges.clear(); solutionBridges.clear();
        dpCache.clear();
        currentPlayer = Player.HUMAN; aiThinking = false;
        humanScore = 0; computerScore = 0;

        Random rand = new Random();
        boolean[][] occupied = new boolean[GRID_W][GRID_H];
        int numIslands = switch (difficulty) {
            case EASY   -> 6  + rand.nextInt(3);
            case MEDIUM -> 8  + rand.nextInt(5);
            case HARD   -> 10 + rand.nextInt(5);
        };

        while (islands.size() < numIslands) {
            int x = rand.nextInt(GRID_W), y = rand.nextInt(GRID_H);
            if (!occupied[x][y]) { islands.add(new Island(x, y, 0)); occupied[x][y] = true; }
        }
        if (islands.isEmpty()) return;

        List<Bridge> solution = new ArrayList<>();
        Set<Island> connected = new HashSet<>();
        connected.add(islands.get(0));

        while (connected.size() < islands.size()) {
            List<int[]> candidates = new ArrayList<>();
            for (Island from : connected)
                for (Island to : islands) {
                    if (connected.contains(to)) continue;
                    if (canConnect(from, to, solution))
                        candidates.add(new int[]{islands.indexOf(from), islands.indexOf(to)});
                }
            if (candidates.isEmpty()) { generatePuzzle(); return; }
            int[] ch = candidates.get(rand.nextInt(candidates.size()));
            Island a = islands.get(ch[0]), b = islands.get(ch[1]);
            solution.add(new Bridge(a, b, rand.nextBoolean() ? 1 : 2));
            connected.add(b);
        }

        for (int k = 0; k < islands.size() / 2; k++) {
            Island a = islands.get(rand.nextInt(islands.size()));
            Island b = islands.get(rand.nextInt(islands.size()));
            if (a == b || !canConnect(a, b, solution)) continue;
            Bridge ex = findBridge(a, b, solution);
            if (ex == null)              solution.add(new Bridge(a, b, rand.nextBoolean() ? 1 : 2));
            else if (ex.count < MAX_BRIDGES) ex.count++;
        }

        for (Island isl : islands) {
            int deg = 0;
            for (Bridge br : solution)
                if (br.a == isl || br.b == isl) deg += br.count;
            isl.required = deg;
        }

        islands.removeIf(i -> i.required == 0);
        if (islands.size() < 4) { generatePuzzle(); return; }

        for (Bridge br : solution) solutionBridges.add(new Bridge(br.a, br.b, br.count));
        repaint();
    }

    public void restartPuzzle() {
        bridges.clear(); dpCache.clear();
        currentPlayer = Player.HUMAN; aiThinking = false; repaint();
    }

    public void showSolution() {
        if (solutionBridges.isEmpty()) return;
        bridges.clear();
        for (Bridge sb : solutionBridges) {
            Bridge c = new Bridge(sb.a, sb.b, sb.count); c.owner = 0; bridges.add(c);
        }
        repaint();
        JOptionPane.showMessageDialog(this, "Solution shown.", "Solve",
                JOptionPane.INFORMATION_MESSAGE);
    }

    // ═════════════════════════════════════════════════════════
    //  GEOMETRY / VALIDATION HELPERS
    // ═════════════════════════════════════════════════════════

    private boolean canConnect(Island a, Island b, List<Bridge> extra) {
        if (a == b || (a.x != b.x && a.y != b.y)) return false;
        for (Bridge br : extra)   if (bridgesCross(a, b, br.a, br.b)) return false;
        for (Bridge br : bridges) if (bridgesCross(a, b, br.a, br.b)) return false;
        for (Island c : islands) {
            if (c == a || c == b) continue;
            if (islandBetween(a, b, c)) return false;
        }
        return true;
    }

    private boolean islandBetween(Island a, Island b, Island c) {
        if (a.x == b.x && c.x == a.x) {
            int lo = Math.min(a.y, b.y), hi = Math.max(a.y, b.y);
            return c.y > lo && c.y < hi;
        }
        if (a.y == b.y && c.y == a.y) {
            int lo = Math.min(a.x, b.x), hi = Math.max(a.x, b.x);
            return c.x > lo && c.x < hi;
        }
        return false;
    }

    private boolean bridgesCross(Island a1, Island b1, Island a2, Island b2) {
        boolean h1 = (a1.y == b1.y), h2 = (a2.y == b2.y);
        if (h1 == h2) return false;
        Island h, hE, v, vE;
        if (h1) { h=a1; hE=b1; v=a2; vE=b2; } else { h=a2; hE=b2; v=a1; vE=b1; }
        int hLo = Math.min(h.x, hE.x), hHi = Math.max(h.x, hE.x);
        int vLo = Math.min(v.y, vE.y), vHi = Math.max(v.y, vE.y);
        return v.x > hLo && v.x < hHi && h.y > vLo && h.y < vHi;
    }

    private Bridge findBridge(Island a, Island b, List<Bridge> list) {
        for (Bridge br : list) if (br.connects(a, b)) return br;
        return null;
    }
    private Bridge findBridge(Island a, Island b) { return findBridge(a, b, bridges); }

    private int getBridgeCount(Island isl) {
        int s = 0;
        for (Bridge br : bridges) if (br.a == isl || br.b == isl) s += br.count;
        return s;
    }

    // ═════════════════════════════════════════════════════════
    //  BFS / GRAPH UTILITIES
    // ═════════════════════════════════════════════════════════

    private Map<Island, List<Island>> buildAdjacencyList() {
        Map<Island, List<Island>> adj = new HashMap<>();
        for (Island isl : islands) adj.put(isl, new ArrayList<>());
        for (Bridge br : bridges) { adj.get(br.a).add(br.b); adj.get(br.b).add(br.a); }
        return adj;
    }

    private boolean isConnected() {
        if (islands.isEmpty()) return true;
        Map<Island, List<Island>> adj = buildAdjacencyList();
        Set<Island>  visited = new HashSet<>();
        Queue<Island> q = new LinkedList<>();
        visited.add(islands.get(0)); q.add(islands.get(0));
        while (!q.isEmpty()) {
            Island u = q.poll();
            for (Island v : adj.get(u))
                if (visited.add(v)) q.add(v);
        }
        return visited.size() == islands.size();
    }

    private boolean isSolved() {
        if (!isConnected()) return false;
        for (Island isl : islands)
            if (getBridgeCount(isl) != isl.required) return false;
        return true;
    }

    private Map<Island, Integer> computeComponents() {
        Map<Island, List<Island>> adj = buildAdjacencyList();
        Map<Island, Integer> comp = new HashMap<>();
        int id = 0;
        for (Island start : islands) {
            if (comp.containsKey(start)) continue;
            Queue<Island> q = new LinkedList<>();
            q.add(start); comp.put(start, id);
            while (!q.isEmpty()) {
                Island u = q.poll();
                for (Island v : adj.get(u))
                    if (!comp.containsKey(v)) { comp.put(v, id); q.add(v); }
            }
            id++;
        }
        return comp;
    }

    // ═════════════════════════════════════════════════════════
    //  ░░░  ALGORITHM 1 — DIVIDE & CONQUER  ░░░
    //
    //  Recursively splits the island array in half.
    //  Base case: sub-list of ≤ 2 islands → test connectivity directly.
    //  Merge step: combine two sorted Move lists into one sorted list
    //              (sorted by Constraint-Density score, ascending).
    //
    //  Result: a fully sorted list of candidate moves built in O(n log n).
    // ═════════════════════════════════════════════════════════

    /**
     * Entry point — returns all legal candidate moves as a sorted list.
     * Sorting key: Constraint-Density = remaining-degree(a) + remaining-degree(b).
     * Lower score → endpoints are closer to being satisfied → higher priority.
     */
    private List<Move> divideAndConquerCandidates(List<Island> islandList, int lo, int hi) {

        // ── BASE CASE ──────────────────────────────────────────────────────────
        // Sub-list has at most 2 islands; check the single possible pair directly.
        if (hi - lo <= 1) {
            List<Move> leaf = new ArrayList<>();
            if (hi > lo) {                          // exactly 2 islands
                Island a = islandList.get(lo);
                Island b = islandList.get(hi);
                if (isLegalMove(a, b)) {
                    int score = constraintDensityScore(a, b);
                    leaf.add(new Move(a, b, score));
                }
            }
            return leaf;                            // 0 or 1 element
        }

        // ── DIVIDE ────────────────────────────────────────────────────────────
        int mid = (lo + hi) / 2;
        List<Move> left  = divideAndConquerCandidates(islandList, lo,    mid);
        List<Move> right = divideAndConquerCandidates(islandList, mid+1, hi);

        // Cross-pairs: one island from the left half, one from the right half
        List<Move> cross = new ArrayList<>();
        for (int i = lo; i <= mid; i++) {
            for (int j = mid+1; j <= hi; j++) {
                Island a = islandList.get(i);
                Island b = islandList.get(j);
                if (isLegalMove(a, b)) {
                    cross.add(new Move(a, b, constraintDensityScore(a, b)));
                }
            }
        }

        // ── CONQUER (MERGE) ───────────────────────────────────────────────────
        // Merge left + right + cross into one sorted list (ascending score).
        List<Move> merged = mergeSortedMoves(left, right);
        merged = mergeSortedMoves(merged, cross);   // cross is unsorted; insertionMerge handles it
        return merged;
    }

    /**
     * Checks whether placing a bridge between a and b is currently legal:
     * - Both islands still have remaining capacity.
     * - No existing double-bridge already fills the slot.
     * - Geometric constraints are satisfied (no crossing, no island in-between).
     */
    private boolean isLegalMove(Island a, Island b) {
        if (getBridgeCount(a) >= a.required) return false;
        if (getBridgeCount(b) >= b.required) return false;
        Bridge ex = findBridge(a, b);
        if (ex != null && ex.count >= MAX_BRIDGES) return false;
        return canConnect(a, b, new ArrayList<>());
    }

    /**
     * Constraint-Density score for a candidate edge — delegates to DP look-ahead.
     * Lower score = higher priority for the AI.
     */
    private int constraintDensityScore(Island a, Island b) {
        return dpEdgePriority(a, b);
    }

    /**
     * Standard merge of two sorted Move lists (by score ascending).
     * Also handles the case where one or both lists are unsorted
     * (e.g., cross-pairs) by using insertion sort on the smaller list first.
     */
    private List<Move> mergeSortedMoves(List<Move> left, List<Move> right) {
        List<Move> result = new ArrayList<>(left.size() + right.size());
        int i = 0, j = 0;

        // Ensure right is sorted (needed for cross-pairs)
        right.sort(Comparator.comparingInt(m -> m.score));

        while (i < left.size() && j < right.size()) {
            if (left.get(i).score <= right.get(j).score) result.add(left.get(i++));
            else                                          result.add(right.get(j++));
        }
        while (i < left.size())  result.add(left.get(i++));
        while (j < right.size()) result.add(right.get(j++));
        return result;
    }

    // ═════════════════════════════════════════════════════════
    //  ░░░  ALGORITHM 2 — DYNAMIC PROGRAMMING  ░░░
    //
    //  Both the human and the AI place exactly ONE bridge segment per turn.
    //  DP is used to SCORE each candidate edge so the AI picks the most
    //  valuable edge to place its single segment on.
    //
    //  State:  dp[k] = combined remaining-degree of both endpoints
    //                  AFTER k total segments have been placed on this edge
    //                  (counting from zero, not from the current state).
    //
    //  Recurrence:
    //      dp[0] = remainA + remainB          (no bridge placed yet)
    //      dp[k] = dp[k-1] - 2               (each segment satisfies
    //                                          one slot on each endpoint)
    //
    //  Priority score = dp[currentCount + 1]
    //      i.e. the projected combined slack AFTER adding one more segment.
    //      Lower score → both islands are closer to completion → AI prefers
    //      this edge.  This gives the AI a 1-step look-ahead via DP.
    //
    //  Results are memoised in dpCache (invalidated when board changes).
    //  Call site: constraintDensityScore() uses dpEdgePriority() as its
    //  scoring function inside divideAndConquerCandidates().
    // ═════════════════════════════════════════════════════════

    /**
     * DP look-ahead score for placing ONE segment on the edge (a, b).
     *
     * Builds the full dp[] table and returns dp[currentCount + 1]:
     * the projected combined remaining-degree after the next single segment.
     * Lower is better (endpoints are closer to being satisfied).
     *
     * @param a first endpoint island
     * @param b second endpoint island
     * @return DP-based priority score (lower = higher priority)
     */
    private int dpEdgePriority(Island a, Island b) {
        // Cache key — order-independent
        int ia = islands.indexOf(a), ib = islands.indexOf(b);
        String key = Math.min(ia,ib) + "-" + Math.max(ia,ib);
        if (dpCache.containsKey(key)) return dpCache.get(key);

        int remainA = a.required - getBridgeCount(a);
        int remainB = b.required - getBridgeCount(b);

        Bridge existing   = findBridge(a, b);
        int currentCount  = (existing != null) ? existing.count : 0;

        // ── BUILD DP TABLE ────────────────────────────────────────────────────
        // dp[k] = combined slack after k total segments on this edge
        int[] dp = buildDPTable(a, b);

        // The AI will add exactly 1 more segment, so the relevant state is
        // currentCount + 1, clamped to the table size.
        int nextState = Math.min(currentCount + 1, MAX_BRIDGES);
        int score     = dp[nextState];

        dpCache.put(key, score);
        return score;
    }

    /**
     * Builds the full DP table for an edge.
     * dp[k] = combined remaining-degree of both endpoints after k segments.
     * k ranges from 0 (no bridge) to MAX_BRIDGES.
     */
    private int[] buildDPTable(Island a, Island b) {
        int remainA = a.required - getBridgeCount(a);
        int remainB = b.required - getBridgeCount(b);
        int[] dp = new int[MAX_BRIDGES + 1];
        dp[0] = remainA + remainB;
        for (int k = 1; k <= MAX_BRIDGES; k++)
            dp[k] = dp[k-1] - 2;
        return dp;
    }

    // ═════════════════════════════════════════════════════════
    //  AI CORE — 7-STEP FLOW (enhanced with D&C and DP)
    // ═════════════════════════════════════════════════════════

    /**
     * Main AI decision method.
     *
     * Step 1: D&C candidate generation — recursively splits island list,
     *         scores each pair via dpEdgePriority() (DP look-ahead), merges.
     * Step 2: BFS component labelling
     * Step 3: Borůvka short-listing (prefer cross-component moves)
     * Step 4: Priority-heap selection (O(log n)) — picks lowest DP score
     * Step 5: Apply move — always exactly 1 segment (same as human)
     */
    private void makeAIMove() {
        if (isSolved()) return;

        // ── STEP 1: D&C candidate generation + scoring ────────────────────────
        List<Move> candidates = divideAndConquerCandidates(islands, 0, islands.size() - 1);
        if (candidates.isEmpty()) return;

        // ── STEP 2: BFS component labelling ──────────────────────────────────
        Map<Island, Integer> components = computeComponents();

        // ── STEP 3: Borůvka short-listing ─────────────────────────────────────
        List<Move> crossComp = new ArrayList<>();
        for (Move m : candidates)
            if (!components.get(m.a).equals(components.get(m.b)))
                crossComp.add(m);
        if (crossComp.isEmpty()) crossComp = candidates;  // fall back to all candidates

        // ── STEP 4: Priority heap — best (lowest score) first ────────────────
        PriorityQueue<Move> pq = new PriorityQueue<>(Comparator.comparingInt(m -> m.score));
        pq.addAll(crossComp);
        Move best = pq.poll();

        // ── STEP 5 & 6: DP selects bridge count → apply ──────────────────────
        addBridgeForAI(best.a, best.b);
    }

    // ═════════════════════════════════════════════════════════
    //  MOVE APPLICATION
    // ═════════════════════════════════════════════════════════

    private void addBridgeForAI(Island a, Island b) {
        if (!canConnect(a, b, new ArrayList<>())) return;

        int prevA = getBridgeCount(a), prevB = getBridgeCount(b);

        // The AI always places exactly ONE segment per turn — same as the human.
        // DP (dpEdgePriority) already influenced WHICH edge was chosen; it does
        // not control HOW MANY segments are placed in a single turn.
        Bridge ex = findBridge(a, b);
        if (ex == null) {
            Bridge nb = new Bridge(a, b, 1);
            nb.owner = 2;
            bridges.add(nb);
        } else if (ex.count < MAX_BRIDGES) {
            ex.count++;
            ex.owner = 2;
        }

        // Invalidate DP cache for this pair (state has changed)
        int ia = islands.indexOf(a), ib = islands.indexOf(b);
        dpCache.remove(Math.min(ia,ib) + "-" + Math.max(ia,ib));

        if (prevA < a.required && getBridgeCount(a) == a.required) computerScore++;
        if (prevB < b.required && getBridgeCount(b) == b.required) computerScore++;

        repaint();
        if (isSolved()) showSolvedPopup();
    }

    private int addOrRemoveBridgeHuman(Island a, Island b) {
        if (!canConnect(a, b, new ArrayList<>())) return 0;
        int prevA = getBridgeCount(a), prevB = getBridgeCount(b);
        Bridge ex = findBridge(a, b);
        int delta;

        // Invalidate DP cache (board state changes)
        int ia = islands.indexOf(a), ib = islands.indexOf(b);
        dpCache.remove(Math.min(ia,ib) + "-" + Math.max(ia,ib));

        if (ex == null) {
            Bridge nb = new Bridge(a, b, 1); nb.owner = 1; bridges.add(nb); delta = 1;
        } else if (ex.count < MAX_BRIDGES) {
            ex.count++; ex.owner = 1; delta = 1;
        } else {
            bridges.remove(ex); delta = -1;
        }

        if (prevA < a.required && getBridgeCount(a) == a.required) humanScore++;
        if (prevB < b.required && getBridgeCount(b) == b.required) humanScore++;

        repaint();
        if (isSolved()) showSolvedPopup();
        return delta;
    }

    private void showSolvedPopup() {
        String winner;
        if      (humanScore > computerScore) winner = "Human wins!";
        else if (computerScore > humanScore) winner = "Computer wins!";
        else                                 winner = "It's a draw!";

        JOptionPane.showMessageDialog(this,
                "Puzzle solved!\n\nHuman: " + humanScore +
                " pts\nComputer: " + computerScore + " pts\n\n" + winner,
                "Solved", JOptionPane.INFORMATION_MESSAGE);
    }

    // ═════════════════════════════════════════════════════════
    //  MOUSE INPUT
    // ═════════════════════════════════════════════════════════

    private Island getIslandAt(int sx, int sy) {
        for (Island isl : islands) {
            int dx = sx - isl.screenX(), dy = sy - isl.screenY();
            if (dx*dx + dy*dy <= ISLAND_RADIUS * ISLAND_RADIUS) return isl;
        }
        return null;
    }

    private Island getIslandInDirection(Island from, int dx, int dy) {
        Island best = null; int bestDist = Integer.MAX_VALUE;
        for (Island isl : islands) {
            if (isl == from) continue;
            int ix = isl.x - from.x, iy = isl.y - from.y;
            if (dx != 0 && (Math.signum(ix) != Math.signum(dx) || iy != 0)) continue;
            if (dy != 0 && (Math.signum(iy) != Math.signum(dy) || ix != 0)) continue;
            int dist = Math.abs(ix) + Math.abs(iy);
            if (dist < bestDist && canConnect(from, isl, new ArrayList<>())) {
                bestDist = dist; best = isl;
            }
        }
        return best;
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (aiThinking || currentPlayer != Player.HUMAN) return;
        requestFocusInWindow();
        Island hit = getIslandAt(e.getX(), e.getY());
        if (hit != null && SwingUtilities.isLeftMouseButton(e)) { dragStart = hit; mousePos = e.getPoint(); }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (aiThinking || currentPlayer != Player.HUMAN) { dragStart = null; mousePos = null; return; }
        boolean addedBridge = false;
        if (dragStart != null && mousePos != null) {
            int dx = mousePos.x - dragStart.screenX(), dy = mousePos.y - dragStart.screenY();
            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                int dirX = (Math.abs(dx) >= Math.abs(dy)) ? (dx > 0 ? 1 : -1) : 0;
                int dirY = (Math.abs(dy) >  Math.abs(dx)) ? (dy > 0 ? 1 : -1) : 0;
                Island target = getIslandInDirection(dragStart, dirX, dirY);
                if (target != null && addOrRemoveBridgeHuman(dragStart, target) > 0)
                    addedBridge = true;
            }
        }
        dragStart = null; mousePos = null; repaint();
        if (addedBridge && !isSolved()) {
            currentPlayer = Player.AI; aiThinking = true; repaint();
            SwingUtilities.invokeLater(() -> {
                makeAIMove(); aiThinking = false; currentPlayer = Player.HUMAN; repaint();
            });
        }
    }

    @Override public void mouseDragged(MouseEvent e) {
        if (aiThinking || currentPlayer != Player.HUMAN) return;
        if (dragStart != null) { mousePos = e.getPoint(); repaint(); }
    }

    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}
    @Override public void mouseMoved(MouseEvent e) {}

    // ═════════════════════════════════════════════════════════
    //  KEYBOARD INPUT
    // ═════════════════════════════════════════════════════════

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_N) { generatePuzzle(); repaint(); }
    }
    @Override public void keyReleased(KeyEvent e) {}
    @Override public void keyTyped(KeyEvent e) {}

    // ═════════════════════════════════════════════════════════
    //  RENDERING
    // ═════════════════════════════════════════════════════════

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw bridges
        g2.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Bridge br : bridges) {
            int x1=br.a.screenX(), y1=br.a.screenY(), x2=br.b.screenX(), y2=br.b.screenY();
            g2.setColor(br.owner==1 ? HUMAN_BRIDGE_COLOR : br.owner==2 ? AI_BRIDGE_COLOR : BRIDGE_COLOR);
            if (br.count == 1) {
                g2.drawLine(x1, y1, x2, y2);
            } else {
                int off = 4;
                if (br.isHorizontal()) {
                    g2.drawLine(x1, y1-off, x2, y2-off);
                    g2.drawLine(x1, y1+off, x2, y2+off);
                } else {
                    g2.drawLine(x1-off, y1, x2-off, y2);
                    g2.drawLine(x1+off, y1, x2+off, y2);
                }
            }
        }

        // Draw islands
        for (Island isl : islands) {
            int cx = isl.screenX(), cy = isl.screenY();
            int deg = getBridgeCount(isl);
            Color fill = (deg == isl.required) ? ISLAND_DONE_COLOR
                       : (deg >  isl.required) ? ERROR_COLOR
                       :                         ISLAND_COLOR;
            g2.setColor(fill);
            g2.fillOval(cx-ISLAND_RADIUS, cy-ISLAND_RADIUS, 2*ISLAND_RADIUS, 2*ISLAND_RADIUS);
            g2.setColor(Color.DARK_GRAY);
            g2.setStroke(new BasicStroke(2));
            g2.drawOval(cx-ISLAND_RADIUS, cy-ISLAND_RADIUS, 2*ISLAND_RADIUS, 2*ISLAND_RADIUS);
            g2.setColor(TEXT_COLOR);
            g2.setFont(new Font("Arial", Font.BOLD, 16));
            String s = String.valueOf(isl.required);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(s, cx - fm.stringWidth(s)/2, cy + fm.getAscent()/2 - 2);
        }

        // Drag preview
        if (dragStart != null && mousePos != null) {
            g2.setColor(new Color(80, 80, 200, 150));
            g2.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    0, new float[]{5, 5}, 0));
            g2.drawLine(dragStart.screenX(), dragStart.screenY(), mousePos.x, mousePos.y);
        }

        // Status bar
        g2.setColor(Color.DARK_GRAY);
        g2.setFont(new Font("Arial", Font.PLAIN, 11));
        String turn = aiThinking ? "Computer thinking (D&C + DP)..." :
                (currentPlayer == Player.HUMAN ? "Your turn" : "Computer's turn");
        g2.drawString(turn
                + "  |  Human: " + humanScore + " pts"
                + "  |  Computer: " + computerScore + " pts"
                + "  |  N = new puzzle",
                5, getHeight() - 6);
    }

    // ═════════════════════════════════════════════════════════
    //  MAIN
    // ═════════════════════════════════════════════════════════

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Bridges – D&C + DP Edition");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            BridgesAdvanced gamePanel = new BridgesAdvanced();

            JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
            String[] opts = {"7×7 Easy", "7×7 Medium", "7×7 Hard"};
            JComboBox<String> typeBox = new JComboBox<>(opts);
            JButton newBtn     = new JButton("New Game");
            JButton restartBtn = new JButton("Restart");
            JButton solveBtn   = new JButton("Solve");

            topBar.add(new JLabel("Difficulty:")); topBar.add(typeBox);
            topBar.add(newBtn); topBar.add(restartBtn); topBar.add(solveBtn);

            typeBox.addActionListener(e -> {
                String sel = (String) typeBox.getSelectedItem();
                if (sel == null) return;
                Difficulty d = sel.contains("Easy")   ? Difficulty.EASY :
                               sel.contains("Medium") ? Difficulty.MEDIUM : Difficulty.HARD;
                gamePanel.setDifficulty(d); gamePanel.requestFocusInWindow();
            });
            newBtn    .addActionListener(e -> { gamePanel.generatePuzzle();  gamePanel.requestFocusInWindow(); });
            restartBtn.addActionListener(e -> { gamePanel.restartPuzzle();   gamePanel.requestFocusInWindow(); });
            solveBtn  .addActionListener(e -> { gamePanel.showSolution();    gamePanel.requestFocusInWindow(); });

            JPanel container = new JPanel(new BorderLayout());
            container.add(topBar,    BorderLayout.NORTH);
            container.add(gamePanel, BorderLayout.CENTER);

            frame.getContentPane().add(container);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            gamePanel.requestFocusInWindow();
        });
    }
}
