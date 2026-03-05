package project;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * Hashiwokakero (Bridges Puzzle) - Human vs. CPU.
 *
 * CPU move selection is implemented with depth-limited backtracking.
 */
public class Bridges_backtracking extends JPanel
        implements MouseListener, MouseMotionListener, KeyListener {

    // -------------------- CONSTANTS ---------------------
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
    private static final Color CPU_BRIDGE_COLOR     = Color.RED;
    private static final Color BRIDGE_COLOR        = new Color(60, 60, 60);

    public enum Difficulty { EASY, MEDIUM, HARD }
    private Difficulty difficulty = Difficulty.MEDIUM;

    private enum Player { HUMAN, CPU }
    private Player currentPlayer = Player.HUMAN;

    // -------------------- GAME STATE ---------------------
    private final List<Island> islands        = new ArrayList<>();
    private final List<Bridge> bridges        = new ArrayList<>();
    private final List<Bridge> solutionBridges = new ArrayList<>();
    private final Stack<GameState> moveHistory = new Stack<>();

    private Island dragStart = null;
    private Point  mousePos  = null;

    private boolean cpuThinking    = false;
    private int     humanScore    = 0;
    private int     computerScore = 0;

    // =====================================================
    //  DATA STRUCTURES
    // =====================================================

    /** Represents a complete game state for undo functionality. */
    static class GameState {
        List<Bridge> bridges;
        int humanScore;
        int computerScore;
        Player currentPlayer;
        
        GameState(List<Bridge> bridges, int humanScore, int computerScore, Player currentPlayer) {
            this.bridges = new ArrayList<>();
            for (Bridge b : bridges) {
                Bridge copy = new Bridge(b.a, b.b, b.count);
                copy.owner = b.owner;
                this.bridges.add(copy);
            }
            this.humanScore = humanScore;
            this.computerScore = computerScore;
            this.currentPlayer = currentPlayer;
        }
    }

    /** Graph vertex - an island on the grid. */
    static class Island {
        int x, y, required;
        Island(int x, int y, int required) { this.x=x; this.y=y; this.required=required; }
        int screenX() { return x * CELL_SIZE + CELL_SIZE / 2; }
        int screenY() { return y * CELL_SIZE + CELL_SIZE / 2; }
    }

    /** Graph edge - a bridge between two islands. */
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

    /** CPU candidate move with a Constraint-Density score (lower = better). */
    static class Move {
        Island a, b;
        int score;
        Move(Island a, Island b, int score) { this.a=a; this.b=b; this.score=score; }
    }

    /** Temporary move metadata used to undo simulated backtracking steps. */
    static class MoveDelta {
        Bridge bridge;
        boolean created;
        int previousOwner;
        MoveDelta(Bridge bridge, boolean created, int previousOwner) {
            this.bridge = bridge;
            this.created = created;
            this.previousOwner = previousOwner;
        }
    }

    // =====================================================
    //  CONSTRUCTOR
    // =====================================================

    public Bridges_backtracking() {
        setPreferredSize(new Dimension(GRID_W * CELL_SIZE, GRID_H * CELL_SIZE + 30));
        setBackground(BG_COLOR);
        addMouseListener(this);
        addMouseMotionListener(this);
        addKeyListener(this);
        setFocusable(true);
        generatePuzzle();
    }

    public void setDifficulty(Difficulty d) { this.difficulty = d; generatePuzzle(); }

    // =====================================================
    //  PUZZLE GENERATION
    // =====================================================

    private void generatePuzzle() {
        islands.clear(); bridges.clear(); solutionBridges.clear();
        moveHistory.clear();
        currentPlayer = Player.HUMAN; cpuThinking = false;
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
        bridges.clear();
        moveHistory.clear();
        currentPlayer = Player.HUMAN; cpuThinking = false; repaint();
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

    

    // =====================================================
    //  UNDO FUNCTIONALITY
    // =====================================================

    private void saveGameState() {
        moveHistory.push(new GameState(bridges, humanScore, computerScore, currentPlayer));
    }

    public void undoLastMove() {
        if (moveHistory.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No moves to undo.", "Undo",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        GameState state = moveHistory.pop();
        bridges.clear();
        for (Bridge b : state.bridges) {
            Bridge copy = new Bridge(b.a, b.b, b.count);
            copy.owner = b.owner;
            bridges.add(copy);
        }
        humanScore = state.humanScore;
        computerScore = state.computerScore;
        currentPlayer = state.currentPlayer;
        cpuThinking = false;
        repaint();
    }

    // =====================================================
    //  GEOMETRY / VALIDATION HELPERS
    // =====================================================

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

    // =====================================================
    //  BFS / GRAPH UTILITIES
    // =====================================================

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

    // =====================================================
    //  BACKTRACKING SEARCH
    //
    //  Backtracking explores legal bridge placements up to a fixed depth.
    //  Search stops at depth 0 or solved/blocked states and evaluates the board.
    //  Each recursive branch simulates one CPU bridge placement
    //              and keeps the move with the best evaluation score.
    //
    //  The best first move is then applied to the real board.
    // =====================================================

    /**
     * Collects all currently legal bridge moves.
     * Each move stores a simple urgency score based on remaining degrees.
     * Lower score means endpoints are closer to completion.
     */
    private List<Move> collectLegalMoves() {
        List<Move> moves = new ArrayList<>();
        for (int i = 0; i < islands.size(); i++) {
            for (int j = i + 1; j < islands.size(); j++) {
                Island a = islands.get(i);
                Island b = islands.get(j);
                if (!isLegalMove(a, b)) continue;
                int score = (a.required - getBridgeCount(a)) + (b.required - getBridgeCount(b));
                moves.add(new Move(a, b, score));
            }
        }
        return moves;
    }

    private boolean isLegalMove(Island a, Island b) {
        if (getBridgeCount(a) >= a.required) return false;
        if (getBridgeCount(b) >= b.required) return false;
        Bridge ex = findBridge(a, b);
        if (ex != null && ex.count >= MAX_BRIDGES) return false;
        return canConnect(a, b, new ArrayList<>());
    }

    private int getBacktrackingDepth() {
        return switch (difficulty) {
            case EASY -> 1;
            case MEDIUM -> 2;
            case HARD -> 3;
        };
    }

    private Move chooseMoveByBacktracking(int depth) {
        List<Move> moves = collectLegalMoves();
        if (moves.isEmpty()) return null;

        Move best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Move move : moves) {
            MoveDelta delta = applyTemporaryMove(move.a, move.b, 2);
            int score = backtrackScore(depth - 1);
            undoTemporaryMove(delta);

            if (score > bestScore || (score == bestScore && (best == null || move.score < best.score))) {
                bestScore = score;
                best = move;
            }
        }
        return best;
    }

    private int backtrackScore(int depth) {
        if (depth <= 0 || isSolved()) return evaluateBoardState();

        List<Move> moves = collectLegalMoves();
        if (moves.isEmpty()) return evaluateBoardState();

        int best = Integer.MIN_VALUE;
        for (Move move : moves) {
            MoveDelta delta = applyTemporaryMove(move.a, move.b, 2);
            int score = backtrackScore(depth - 1);
            undoTemporaryMove(delta);
            best = Math.max(best, score);
        }
        return best;
    }

    private MoveDelta applyTemporaryMove(Island a, Island b, int owner) {
        Bridge ex = findBridge(a, b);
        if (ex == null) {
            Bridge created = new Bridge(a, b, 1);
            created.owner = owner;
            bridges.add(created);
            return new MoveDelta(created, true, 0);
        }

        int previousOwner = ex.owner;
        ex.count++;
        ex.owner = owner;
        return new MoveDelta(ex, false, previousOwner);
    }

    private void undoTemporaryMove(MoveDelta delta) {
        if (delta.created) {
            bridges.remove(delta.bridge);
            return;
        }
        delta.bridge.count--;
        delta.bridge.owner = delta.previousOwner;
    }

    private int evaluateBoardState() {
        int completed = 0;
        int unmet = 0;
        int overflow = 0;

        for (Island island : islands) {
            int diff = island.required - getBridgeCount(island);
            if (diff == 0) completed++;
            else if (diff > 0) unmet += diff;
            else overflow += -diff;
        }

        int connectivityBonus = isConnected() ? 40 : 0;
        return (completed * 25) - (unmet * 4) - (overflow * 50) + connectivityBonus;
    }

    private void makeCPUMove() {
        if (isSolved()) return;
        Move best = chooseMoveByBacktracking(getBacktrackingDepth());
        if (best != null) addBridgeForCPU(best.a, best.b);
    }

    public void showBacktrackingDemo() {
        if (isSolved()) {
            JOptionPane.showMessageDialog(this,
                    "Puzzle is already solved!", "Backtracking Demo",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int depth = getBacktrackingDepth();
        Move bestMove = chooseMoveByBacktracking(depth);
        if (bestMove != null) {
            String msg = String.format("Backtracking Algorithm Decision:\nIsland A: (%d, %d)\nIsland B: (%d, %d)\nSearch Depth: %d",
                    bestMove.a.x, bestMove.a.y, bestMove.b.x, bestMove.b.y, depth);
            JOptionPane.showMessageDialog(this, msg, "Backtracking Demo", JOptionPane.INFORMATION_MESSAGE);
            addBridgeForCPU(bestMove.a, bestMove.b);
        } else {
            JOptionPane.showMessageDialog(this,
                    "No valid move found by Backtracking.", "Backtracking Demo",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void addBridgeForCPU(Island a, Island b) {
        if (!canConnect(a, b, new ArrayList<>())) return;

        int prevA = getBridgeCount(a), prevB = getBridgeCount(b);

        // The AI always places exactly ONE segment per turn - same as the human.
        // Backtracking already influenced WHICH edge was chosen; it does
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

        if (prevA < a.required && getBridgeCount(a) == a.required) computerScore++;
        if (prevB < b.required && getBridgeCount(b) == b.required) computerScore++;

        repaint();
        if (isSolved()) showSolvedPopup();
    }

    private int addOrRemoveBridgeHuman(Island a, Island b) {
        if (!canConnect(a, b, new ArrayList<>())) return 0;
        
        // Save state before making move
        saveGameState();
        
        int prevA = getBridgeCount(a), prevB = getBridgeCount(b);
        Bridge ex = findBridge(a, b);
        int delta;

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

    // =====================================================
    //  MOUSE INPUT
    // =====================================================

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
        if (cpuThinking || currentPlayer != Player.HUMAN) return;
        requestFocusInWindow();
        Island hit = getIslandAt(e.getX(), e.getY());
        if (hit != null && SwingUtilities.isLeftMouseButton(e)) { dragStart = hit; mousePos = e.getPoint(); }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (cpuThinking || currentPlayer != Player.HUMAN) { dragStart = null; mousePos = null; return; }
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
            currentPlayer = Player.CPU; cpuThinking = true; repaint();
            SwingUtilities.invokeLater(() -> {
                makeCPUMove(); cpuThinking = false; currentPlayer = Player.HUMAN; repaint();
            });
        }
    }

    @Override public void mouseDragged(MouseEvent e) {
        if (cpuThinking || currentPlayer != Player.HUMAN) return;
        if (dragStart != null) { mousePos = e.getPoint(); repaint(); }
    }

    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}
    @Override public void mouseMoved(MouseEvent e) {}

    // =====================================================
    //  KEYBOARD INPUT
    // =====================================================

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_N) { generatePuzzle(); repaint(); }
        if (e.getKeyCode() == KeyEvent.VK_Z && e.isControlDown()) { undoLastMove(); }
    }
    @Override public void keyReleased(KeyEvent e) {}
    @Override public void keyTyped(KeyEvent e) {}

    // =====================================================
    //  RENDERING
    // =====================================================

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw bridges
        g2.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Bridge br : bridges) {
            int x1=br.a.screenX(), y1=br.a.screenY(), x2=br.b.screenX(), y2=br.b.screenY();
            g2.setColor(br.owner==1 ? HUMAN_BRIDGE_COLOR : br.owner==2 ? CPU_BRIDGE_COLOR : BRIDGE_COLOR);
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
        String turn = cpuThinking ? "Computer thinking (Backtracking)..." :
                (currentPlayer == Player.HUMAN ? "Your turn" : "Computer's turn");
        g2.drawString(turn
                + "  |  Human: " + humanScore + " pts"
                + "  |  Computer: " + computerScore + " pts"
                + "  |  N = new puzzle, Ctrl+Z = undo",
                5, getHeight() - 6);
    }

    // =====================================================
    //  MAIN
    // =====================================================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Bridges - Backtracking Edition");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            Bridges_backtracking gamePanel = new Bridges_backtracking();

            JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
            String[] opts = {"7x7 Easy", "7x7 Medium", "7x7 Hard"};
            JComboBox<String> typeBox = new JComboBox<>(opts);
            JButton newBtn     = new JButton("New Game");
            JButton restartBtn = new JButton("Restart");
            JButton solveBtn   = new JButton("Solve");
            JButton undoBtn    = new JButton("Undo (Ctrl+Z)");
            JButton backtrackBtn = new JButton("Backtracking");

            topBar.add(new JLabel("Difficulty:")); topBar.add(typeBox);
            topBar.add(newBtn); topBar.add(restartBtn); topBar.add(solveBtn); topBar.add(undoBtn); topBar.add(backtrackBtn);

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
            undoBtn   .addActionListener(e -> { gamePanel.undoLastMove();    gamePanel.requestFocusInWindow(); });
            backtrackBtn.addActionListener(e -> {
                gamePanel.showBacktrackingDemo();
                gamePanel.requestFocusInWindow();
            });

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








